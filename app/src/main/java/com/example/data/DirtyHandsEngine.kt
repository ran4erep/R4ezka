package com.example.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Перечень действий плеера для голосового управления.
 * Пользователь сам решает, какое слово или фразу записать на каждое действие.
 */
enum class DirtyHandsAction(
    val id: String,
    val title: String,
    val description: String
) {
    PAUSE("pause", "Пауза", "Приостановить воспроизведение"),
    RESUME("resume", "Продолжить", "Возобновить воспроизведение"),
    NEXT_EPISODE("next_episode", "Следующая серия", "Переключить на следующую серию"),
    PREV_EPISODE("prev_episode", "Предыдущая серия", "Вернуться к предыдущей серии"),
    SEEK_FORWARD("seek_forward", "Перемотка вперед", "Перемотать на 15 секунд вперед"),
    SEEK_BACKWARD("seek_backward", "Перемотка назад", "Перемотать на 15 секунд назад");

    companion object {
        fun fromId(id: String): DirtyHandsAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Шаблон голосовой команды.
 * Содержит компактную матрицу спектральных признаков (MFCC + CMS),
 * квантованную в байты, и локальную аудиодорожку для прослушивания.
 */
class VoiceCommandTemplate(
    val action: DirtyHandsAction,
    val frameCount: Int,
    val featureDim: Int,
    val features: ByteArray,
    val audioSample: ByteArray? = null,
    val durationMs: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VoiceCommandTemplate
        return action == other.action && createdAt == other.createdAt
    }

    override fun hashCode(): Int = action.hashCode() * 31 + createdAt.hashCode()
}

/**
 * Высокопроизводительный движок голосового управления «Грязные руки».
 *
 * Оптимизации:
 * - Использует VOICE_RECOGNITION как основной стабильный источник без конфликтов с ExoPlayer
 * - Скользящая Cepstral Mean Normalization (CMS) нивелирует громкость фильма и акустику помещения
 * - VAD (Voice Activity Detection) отсекает тишину и фоновый звук фильма, исключая нагрузку на CPU
 * - Быстрый DTW с полосой Сако-Чибы и мульти-масштабным темпом речи (0.8x - 1.25x)
 * - 0 аллокаций памяти в основном цикле обработки
 */
object DirtyHandsEngine {
    private const val TAG = "DirtyHandsEngine"
    private const val PREFS_NAME = "r4ezka_dirty_hands_voice_prefs"

    const val SAMPLE_RATE = 16000
    private const val FRAME_SIZE = 400 // 25 мс при 16 кГц
    private const val FRAME_HOP = 160  // 10 мс шаг при 16 кГц
    private const val FFT_SIZE = 512
    private const val NUM_MEL_FILTERS = 16
    private const val NUM_MFCC_COEFFS = 13 // C0..C12

    // Кольцевой буфер признаков (хранит последние 160 фреймов = 1.6 сек звука)
    private const val RING_BUFFER_FRAMES = 160

    // Пороги сопоставления DTW
    private const val DTW_MATCH_THRESHOLD = 115.0
    private const val DTW_DISTINCT_RATIO = 0.93

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var prefs: SharedPreferences? = null

    private val templatesMap = ConcurrentHashMap<DirtyHandsAction, VoiceCommandTemplate>()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _registeredTemplates = MutableStateFlow<Map<DirtyHandsAction, VoiceCommandTemplate>>(emptyMap())
    val registeredTemplates: StateFlow<Map<DirtyHandsAction, VoiceCommandTemplate>> = _registeredTemplates.asStateFlow()

    private val _commandEvents = MutableSharedFlow<DirtyHandsAction>(extraBufferCapacity = 4)
    val commandEvents: SharedFlow<DirtyHandsAction> = _commandEvents.asSharedFlow()

    private var recordingJob: Job? = null
    private var listeningJob: Job? = null
    private var audioTrackPlayer: AudioTrack? = null

    // Предрассчитанные математические таблицы (0 аллокаций в реальном времени)
    private val hammingWindow = FloatArray(FRAME_SIZE)
    private val cosTable = DoubleArray(FFT_SIZE)
    private val sinTable = DoubleArray(FFT_SIZE)
    private val melFilterBank = Array(NUM_MEL_FILTERS) { FloatArray(FFT_SIZE / 2 + 1) }
    private val dctTable = Array(NUM_MFCC_COEFFS) { FloatArray(NUM_MEL_FILTERS) }

    init {
        initMathTables()
    }

    private fun initMathTables() {
        for (i in 0 until FRAME_SIZE) {
            hammingWindow[i] = (0.54 - 0.46 * cos(2.0 * PI * i / (FRAME_SIZE - 1))).toFloat()
        }

        for (i in 0 until FFT_SIZE) {
            val angle = -2.0 * PI * i / FFT_SIZE
            cosTable[i] = cos(angle)
            sinTable[i] = sin(angle)
        }

        fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

        val minMel = hzToMel(100.0)
        val maxMel = hzToMel(7000.0)
        val melStep = (maxMel - minMel) / (NUM_MEL_FILTERS + 1)

        val binFreqs = IntArray(NUM_MEL_FILTERS + 2)
        for (i in 0 until NUM_MEL_FILTERS + 2) {
            val mel = minMel + i * melStep
            val hz = melToHz(mel)
            binFreqs[i] = ((FFT_SIZE + 1) * hz / SAMPLE_RATE).toInt().coerceIn(0, FFT_SIZE / 2)
        }

        for (m in 0 until NUM_MEL_FILTERS) {
            val left = binFreqs[m]
            val center = binFreqs[m + 1]
            val right = binFreqs[m + 2]

            for (k in left until center) {
                if (center > left) {
                    melFilterBank[m][k] = (k - left).toFloat() / (center - left)
                }
            }
            for (k in center until right) {
                if (right > center) {
                    melFilterBank[m][k] = (right - k).toFloat() / (right - center)
                }
            }
        }

        for (i in 0 until NUM_MFCC_COEFFS) {
            for (m in 0 until NUM_MEL_FILTERS) {
                dctTable[i][m] = cos(PI * i * (m + 0.5) / NUM_MEL_FILTERS).toFloat()
            }
        }
    }

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadSavedTemplates()
        }
    }

    private fun loadSavedTemplates() {
        val p = prefs ?: return
        val map = mutableMapOf<DirtyHandsAction, VoiceCommandTemplate>()
        for (action in DirtyHandsAction.entries) {
            val raw = p.getString("cmd_${action.id}", null) ?: continue
            try {
                val template = deserializeCompressedTemplate(action, raw)
                if (template != null) {
                    templatesMap[action] = template
                    map[action] = template
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load template for ${action.id}: ${e.message}")
            }
        }
        _registeredTemplates.value = map
    }

    fun isActionConfigured(action: DirtyHandsAction): Boolean = templatesMap.containsKey(action)

    fun getTemplate(action: DirtyHandsAction): VoiceCommandTemplate? = templatesMap[action]

    /**
     * Создание AudioRecord.
     * VOICE_RECOGNITION гарантирует стабильный захват аудио без перевода ОС в телефонный режим.
     */
    @SuppressLint("MissingPermission")
    private fun createOptimizedAudioRecord(): AudioRecord? {
        val bufferSize = max(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            SAMPLE_RATE * 2
        )

        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        for (src in sources) {
            try {
                val record = AudioRecord(
                    src,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "Initialized AudioRecord with source $src")
                    return record
                } else {
                    record.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create AudioRecord with source $src: ${e.message}")
            }
        }
        return null
    }

    /**
     * Запись персонального голосового образца для действия.
     */
    @SuppressLint("MissingPermission")
    fun startRecordingCommand(
        action: DirtyHandsAction,
        onProgress: (remainingMs: Int, volume: Float) -> Unit,
        onFinished: (Boolean, String?) -> Unit
    ) {
        cancelCurrentRecording()
        stopListening()

        recordingJob = scope.launch(Dispatchers.IO) {
            _isRecording.value = true
            var audioRecord: AudioRecord? = null

            try {
                audioRecord = createOptimizedAudioRecord()

                if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) {
                        onFinished(false, "Не удалось запустить микрофон")
                    }
                    return@launch
                }

                audioRecord.startRecording()

                val totalDurationMs = 2000
                val totalSamplesNeeded = (SAMPLE_RATE * (totalDurationMs / 1000f)).toInt()
                val pcmBuffer = ShortArray(totalSamplesNeeded)
                var samplesReadTotal = 0

                val chunk = ShortArray(640)
                val startTime = System.currentTimeMillis()

                while (isActive && samplesReadTotal < totalSamplesNeeded) {
                    val toRead = min(chunk.size, totalSamplesNeeded - samplesReadTotal)
                    val read = audioRecord.read(chunk, 0, toRead)
                    if (read > 0) {
                        System.arraycopy(chunk, 0, pcmBuffer, samplesReadTotal, read)
                        samplesReadTotal += read

                        var sumSq = 0.0
                        for (i in 0 until read) {
                            val v = chunk[i].toDouble()
                            sumSq += v * v
                        }
                        val rms = sqrt(sumSq / read)
                        val normLevel = (rms / 2500.0).toFloat().coerceIn(0f, 1f)
                        _audioLevel.value = normLevel

                        val elapsed = (System.currentTimeMillis() - startTime).toInt()
                        val remaining = max(0, totalDurationMs - elapsed)
                        withContext(Dispatchers.Main) {
                            onProgress(remaining, normLevel)
                        }
                    }
                }

                if (samplesReadTotal > SAMPLE_RATE / 4) {
                    val trimmed = trimSilence(pcmBuffer, samplesReadTotal)
                    if (trimmed.size >= SAMPLE_RATE * 0.15) {
                        val features = extractMfccFeatures(trimmed)
                        if (features.isNotEmpty()) {
                            val pcmBytes = shortArrayToByteArray(trimmed)
                            val quantized = flattenAndQuantizeFeatures(features)

                            val template = VoiceCommandTemplate(
                                action = action,
                                frameCount = features.size,
                                featureDim = NUM_MFCC_COEFFS,
                                features = quantized,
                                audioSample = pcmBytes,
                                durationMs = (trimmed.size * 1000) / SAMPLE_RATE,
                                createdAt = System.currentTimeMillis()
                            )

                            saveTemplateLocally(template)
                            notifyFirebaseSync(template)

                            withContext(Dispatchers.Main) {
                                _audioLevel.value = 0f
                                onFinished(true, null)
                            }
                            return@launch
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    _audioLevel.value = 0f
                    onFinished(false, "Голос не обнаружен. Попробуйте сказать громче.")
                }
            } catch (e: SecurityException) {
                withContext(Dispatchers.Main) {
                    onFinished(false, "Отсутствует разрешение на запись аудио")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recording command", e)
                withContext(Dispatchers.Main) {
                    onFinished(false, e.message ?: "Ошибка записи")
                }
            } finally {
                _isRecording.value = false
                _audioLevel.value = 0f
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {}
            }
        }
    }

    fun cancelCurrentRecording() {
        recordingJob?.cancel()
        recordingJob = null
        _isRecording.value = false
        _audioLevel.value = 0f
    }

    fun playRecordedSample(action: DirtyHandsAction) {
        val template = templatesMap[action] ?: return
        val sample = template.audioSample ?: return

        scope.launch(Dispatchers.IO) {
            try {
                audioTrackPlayer?.release()
                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(max(minBuf, sample.size))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(sample, 0, sample.size)
                track.play()
                audioTrackPlayer = track
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play voice sample: ${e.message}")
            }
        }
    }

    fun deleteCommand(action: DirtyHandsAction) {
        templatesMap.remove(action)
        val p = prefs ?: return
        p.edit().remove("cmd_${action.id}").apply()
        _registeredTemplates.value = HashMap(templatesMap)

        scope.launch(Dispatchers.IO) {
            FirebaseSyncManager.onVoiceCommandDeleted(action.id)
        }
    }

    /**
     * Непрерывное прослушивание микрофона при воспроизведении видео.
     */
    @SuppressLint("MissingPermission")
    fun startListening(context: Context, onCommandRecognized: (DirtyHandsAction) -> Unit) {
        init(context)

        if (!RezkaService.dirtyHandsMode.value || templatesMap.isEmpty()) {
            return
        }

        if (_isListening.value) return

        listeningJob?.cancel()
        listeningJob = scope.launch(Dispatchers.IO) {
            _isListening.value = true
            var audioRecord: AudioRecord? = null
            var echoCanceler: AcousticEchoCanceler? = null
            var noiseSuppressor: NoiseSuppressor? = null

            try {
                audioRecord = createOptimizedAudioRecord()
                if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioRecord not initialized")
                    return@launch
                }

                val sessionId = audioRecord.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        echoCanceler = AcousticEchoCanceler.create(sessionId)
                        echoCanceler?.enabled = true
                    } catch (_: Exception) {}
                }
                if (NoiseSuppressor.isAvailable()) {
                    try {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)
                        noiseSuppressor?.enabled = true
                    } catch (_: Exception) {}
                }

                audioRecord.startRecording()
                Log.d(TAG, "DirtyHandsEngine: continuous listening started")

                val hopSamples = FRAME_HOP
                val chunk = ShortArray(hopSamples)
                val windowPcm = ShortArray(FRAME_SIZE)

                // Кольцевой буфер MFCC признаков
                val circularMfcc = Array(RING_BUFFER_FRAMES) { FloatArray(NUM_MFCC_COEFFS) }
                val circularEnergy = FloatArray(RING_BUFFER_FRAMES)
                var writeFrameIdx = 0
                var totalFramesProcessed = 0
                var lastTriggerTimestamp = 0L

                val realBuf = DoubleArray(FFT_SIZE)
                val imagBuf = DoubleArray(FFT_SIZE)
                val powerSpectrum = FloatArray(FFT_SIZE / 2 + 1)
                val melEnergies = FloatArray(NUM_MEL_FILTERS)

                while (isActive) {
                    val read = audioRecord.read(chunk, 0, hopSamples)
                    if (read <= 0) continue

                    // Сдвигаем PCM окно
                    System.arraycopy(windowPcm, hopSamples, windowPcm, 0, FRAME_SIZE - hopSamples)
                    System.arraycopy(chunk, 0, windowPcm, FRAME_SIZE - hopSamples, hopSamples)

                    // Рассчитываем энергию текущего кадра
                    var frameRms = 0.0
                    for (i in 0 until FRAME_SIZE) {
                        val s = windowPcm[i].toDouble()
                        frameRms += s * s
                    }
                    val frameEnergy = sqrt(frameRms / FRAME_SIZE).toFloat()

                    // Предыскажение и оконная функция Хэмминга
                    for (i in 0 until FRAME_SIZE) {
                        val prev = if (i > 0) windowPcm[i - 1].toFloat() else windowPcm[0].toFloat()
                        val raw = windowPcm[i].toFloat()
                        val emphasized = raw - 0.97f * prev
                        realBuf[i] = (emphasized * hammingWindow[i]).toDouble()
                        imagBuf[i] = 0.0
                    }
                    for (i in FRAME_SIZE until FFT_SIZE) {
                        realBuf[i] = 0.0
                        imagBuf[i] = 0.0
                    }

                    computeFft(realBuf, imagBuf)

                    for (i in 0..FFT_SIZE / 2) {
                        powerSpectrum[i] = ((realBuf[i] * realBuf[i] + imagBuf[i] * imagBuf[i]) / FFT_SIZE).toFloat()
                    }

                    for (m in 0 until NUM_MEL_FILTERS) {
                        var energy = 0f
                        val filter = melFilterBank[m]
                        for (k in 0..FFT_SIZE / 2) {
                            energy += powerSpectrum[k] * filter[k]
                        }
                        melEnergies[m] = ln(max(1e-5f, energy))
                    }

                    val currentMfcc = circularMfcc[writeFrameIdx]
                    for (i in 0 until NUM_MFCC_COEFFS) {
                        var sum = 0f
                        val dctRow = dctTable[i]
                        for (m in 0 until NUM_MEL_FILTERS) {
                            sum += melEnergies[m] * dctRow[m]
                        }
                        currentMfcc[i] = sum
                    }
                    circularEnergy[writeFrameIdx] = frameEnergy

                    writeFrameIdx = (writeFrameIdx + 1) % RING_BUFFER_FRAMES
                    totalFramesProcessed++

                    // Запускаем анализ каждые 4 фрейма (~40 мс)
                    if (totalFramesProcessed >= 30 && totalFramesProcessed % 4 == 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastTriggerTimestamp > 1400L) {
                            val matchedAction = evaluateSlidingWindowMatch(
                                circularMfcc = circularMfcc,
                                circularEnergy = circularEnergy,
                                currentHead = writeFrameIdx
                            )
                            if (matchedAction != null) {
                                lastTriggerTimestamp = now
                                Log.d(TAG, "Recognized command: ${matchedAction.title} (${matchedAction.id})")
                                withContext(Dispatchers.Main) {
                                    onCommandRecognized(matchedAction)
                                    _commandEvents.tryEmit(matchedAction)
                                }
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Recording audio permission missing: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error in continuous listening loop", e)
            } finally {
                _isListening.value = false
                try {
                    echoCanceler?.release()
                    noiseSuppressor?.release()
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {}
                Log.d(TAG, "DirtyHandsEngine: listening stopped")
            }
        }
    }

    /**
     * Оценка скользящего окна признаков по всем зарегистрированным шаблонам команд.
     */
    private fun evaluateSlidingWindowMatch(
        circularMfcc: Array<FloatArray>,
        circularEnergy: FloatArray,
        currentHead: Int
    ): DirtyHandsAction? {
        if (templatesMap.isEmpty()) return null

        var bestAction: DirtyHandsAction? = null
        var lowestDistance = Double.MAX_VALUE
        var secondLowestDistance = Double.MAX_VALUE

        val meanBuf = FloatArray(NUM_MFCC_COEFFS)

        for ((action, template) in templatesMap) {
            val templateFrames = template.frameCount
            if (templateFrames <= 0 || templateFrames > RING_BUFFER_FRAMES) continue

            // Проверяем 5 вариантов темпа речи
            val scales = floatArrayOf(0.82f, 0.92f, 1.0f, 1.12f, 1.25f)

            for (scale in scales) {
                val wSize = (templateFrames * scale).toInt().coerceIn(15, RING_BUFFER_FRAMES - 1)
                val readPtr = (currentHead - wSize + RING_BUFFER_FRAMES) % RING_BUFFER_FRAMES

                // 1. Быстрый VAD по энергии окна: игнорируем тишину
                var totalEnergy = 0f
                var ep = readPtr
                for (f in 0 until wSize) {
                    totalEnergy += circularEnergy[ep]
                    ep = (ep + 1) % RING_BUFFER_FRAMES
                }
                val avgEnergy = totalEnergy / wSize
                if (avgEnergy < 150f) {
                    continue // Слишком тихо, речи нет
                }

                // 2. Считаем среднее (CMS) по окну
                val candidateBytes = ByteArray(wSize * NUM_MFCC_COEFFS)
                meanBuf.fill(0f)
                var p = readPtr
                for (f in 0 until wSize) {
                    val frame = circularMfcc[p]
                    for (k in 0 until NUM_MFCC_COEFFS) {
                        meanBuf[k] += frame[k]
                    }
                    p = (p + 1) % RING_BUFFER_FRAMES
                }
                for (k in 0 until NUM_MFCC_COEFFS) {
                    meanBuf[k] /= wSize
                }

                // 3. Центрируем и квантуем признаки
                p = readPtr
                for (f in 0 until wSize) {
                    val frame = circularMfcc[p]
                    val offset = f * NUM_MFCC_COEFFS
                    for (k in 0 until NUM_MFCC_COEFFS) {
                        val centered = frame[k] - meanBuf[k]
                        val scaled = (centered * 12.0f).toInt().coerceIn(-128, 127)
                        candidateBytes[offset + k] = scaled.toByte()
                    }
                    p = (p + 1) % RING_BUFFER_FRAMES
                }

                // 4. Вычисляем DTW-расстояние
                val dist = computeDtwDistance(
                    a = candidateBytes,
                    aFrames = wSize,
                    b = template.features,
                    bFrames = template.frameCount,
                    featureDim = NUM_MFCC_COEFFS
                )

                if (dist < lowestDistance) {
                    secondLowestDistance = lowestDistance
                    lowestDistance = dist
                    bestAction = action
                } else if (dist < secondLowestDistance && action != bestAction) {
                    secondLowestDistance = dist
                }
            }
        }

        if (bestAction != null && lowestDistance <= DTW_MATCH_THRESHOLD) {
            val isDistinct = secondLowestDistance == Double.MAX_VALUE ||
                    (lowestDistance / secondLowestDistance <= DTW_DISTINCT_RATIO)
            if (isDistinct) {
                Log.d(TAG, "Command matched: ${bestAction.title}, dist=$lowestDistance (threshold=$DTW_MATCH_THRESHOLD)")
                return bestAction
            }
        }

        return null
    }

    fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        _isListening.value = false
    }

    /**
     * Dynamic Time Warping (DTW) с полосой Сако-Чибы.
     */
    private fun computeDtwDistance(
        a: ByteArray,
        aFrames: Int,
        b: ByteArray,
        bFrames: Int,
        featureDim: Int
    ): Double {
        if (aFrames == 0 || bFrames == 0) return Double.MAX_VALUE

        val window = max(8, (max(aFrames, bFrames) * 0.35).toInt())

        var prevRow = DoubleArray(bFrames + 1) { Double.MAX_VALUE }
        var currRow = DoubleArray(bFrames + 1) { Double.MAX_VALUE }

        prevRow[0] = 0.0

        for (i in 1..aFrames) {
            currRow.fill(Double.MAX_VALUE)
            val aOffset = (i - 1) * featureDim

            val jStart = max(1, i - window)
            val jEnd = min(bFrames, i + window)

            for (j in jStart..jEnd) {
                val bOffset = (j - 1) * featureDim

                // Сравниваем спектральные коэффициенты C1..C12 (игнорируя C0)
                var frameDist = 0
                for (k in 1 until featureDim) {
                    val diff = a[aOffset + k].toInt() - b[bOffset + k].toInt()
                    frameDist += abs(diff)
                }

                val minPrev = min(prevRow[j], min(currRow[j - 1], prevRow[j - 1]))
                if (minPrev != Double.MAX_VALUE) {
                    currRow[j] = frameDist + minPrev
                }
            }

            val temp = prevRow
            prevRow = currRow
            currRow = temp
        }

        val totalCost = prevRow[bFrames]
        if (totalCost == Double.MAX_VALUE) return Double.MAX_VALUE

        return totalCost / (aFrames + bFrames)
    }

    /**
     * Извлечение MFCC признаков с вычитанием среднего (CMS) для записи шаблона.
     */
    private fun extractMfccFeatures(pcm: ShortArray): List<FloatArray> {
        val totalSamples = pcm.size
        if (totalSamples < FRAME_SIZE) return emptyList()

        val numFrames = (totalSamples - FRAME_SIZE) / FRAME_HOP + 1
        val framesList = ArrayList<FloatArray>(numFrames)

        val realBuf = DoubleArray(FFT_SIZE)
        val imagBuf = DoubleArray(FFT_SIZE)
        val powerSpectrum = FloatArray(FFT_SIZE / 2 + 1)
        val melEnergies = FloatArray(NUM_MEL_FILTERS)

        for (f in 0 until numFrames) {
            val offset = f * FRAME_HOP

            for (i in 0 until FRAME_SIZE) {
                val prev = if (i > 0) pcm[offset + i - 1].toFloat() else pcm[offset].toFloat()
                val raw = pcm[offset + i].toFloat()
                val emphasized = raw - 0.97f * prev

                realBuf[i] = (emphasized * hammingWindow[i]).toDouble()
                imagBuf[i] = 0.0
            }
            for (i in FRAME_SIZE until FFT_SIZE) {
                realBuf[i] = 0.0
                imagBuf[i] = 0.0
            }

            computeFft(realBuf, imagBuf)

            for (i in 0..FFT_SIZE / 2) {
                powerSpectrum[i] = ((realBuf[i] * realBuf[i] + imagBuf[i] * imagBuf[i]) / FFT_SIZE).toFloat()
            }

            for (m in 0 until NUM_MEL_FILTERS) {
                var energy = 0f
                val filter = melFilterBank[m]
                for (k in 0..FFT_SIZE / 2) {
                    energy += powerSpectrum[k] * filter[k]
                }
                melEnergies[m] = ln(max(1e-5f, energy))
            }

            val mfcc = FloatArray(NUM_MFCC_COEFFS)
            for (i in 0 until NUM_MFCC_COEFFS) {
                var sum = 0f
                val dctRow = dctTable[i]
                for (m in 0 until NUM_MEL_FILTERS) {
                    sum += melEnergies[m] * dctRow[m]
                }
                mfcc[i] = sum
            }

            framesList.add(mfcc)
        }

        // Вычитание среднего (CMS)
        if (framesList.isNotEmpty()) {
            val mean = FloatArray(NUM_MFCC_COEFFS)
            for (frame in framesList) {
                for (k in 0 until NUM_MFCC_COEFFS) {
                    mean[k] += frame[k]
                }
            }
            for (k in 0 until NUM_MFCC_COEFFS) {
                mean[k] /= framesList.size
            }
            for (frame in framesList) {
                for (k in 0 until NUM_MFCC_COEFFS) {
                    frame[k] -= mean[k]
                }
            }
        }

        return framesList
    }

    private fun computeFft(real: DoubleArray, imag: DoubleArray) {
        val n = FFT_SIZE
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val step = n / len
            for (i in 0 until n step len) {
                for (k in 0 until halfLen) {
                    val tableIndex = k * step
                    val uR = cosTable[tableIndex]
                    val uI = sinTable[tableIndex]

                    val vR = real[i + k + halfLen] * uR - imag[i + k + halfLen] * uI
                    val vI = real[i + k + halfLen] * uI + imag[i + k + halfLen] * uR

                    real[i + k + halfLen] = real[i + k] - vR
                    imag[i + k + halfLen] = imag[i + k] - vI
                    real[i + k] += vR
                    imag[i + k] += vI
                }
            }
            len = len shl 1
        }
    }

    private fun flattenAndQuantizeFeatures(frames: List<FloatArray>): ByteArray {
        val count = frames.size
        val out = ByteArray(count * NUM_MFCC_COEFFS)
        for (f in 0 until count) {
            val frame = frames[f]
            val offset = f * NUM_MFCC_COEFFS
            for (k in 0 until NUM_MFCC_COEFFS) {
                val scaled = (frame[k] * 12.0f).toInt().coerceIn(-128, 127)
                out[offset + k] = scaled.toByte()
            }
        }
        return out
    }

    private fun trimSilence(pcm: ShortArray, length: Int): ShortArray {
        val window = 320
        var start = 0
        var end = length - 1
        val threshold = 200.0

        for (i in 0 until length - window step window) {
            var sum = 0.0
            for (k in 0 until window) {
                val v = pcm[i + k].toDouble()
                sum += v * v
            }
            if (sqrt(sum / window) >= threshold) {
                start = max(0, i - 1600) // запас 100 мс в начале
                break
            }
        }

        for (i in (length - window) downTo start step window) {
            var sum = 0.0
            for (k in 0 until window) {
                val v = pcm[i + k].toDouble()
                sum += v * v
            }
            if (sqrt(sum / window) >= threshold) {
                end = min(length - 1, i + window + 1600) // запас 100 мс в конце
                break
            }
        }

        val trimmedLen = max(0, end - start + 1)
        val result = ShortArray(trimmedLen)
        if (trimmedLen > 0) {
            System.arraycopy(pcm, start, result, 0, trimmedLen)
        }
        return result
    }

    private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val s = shorts[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    // =========================================================================
    // Сжатие ZLIB / Base64 для облака Firebase
    // =========================================================================

    object Base64Coder {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        private val DECODE_TABLE = IntArray(128) { -1 }.apply {
            for (i in ALPHABET.indices) {
                this[ALPHABET[i].code] = i
            }
        }

        fun encode(data: ByteArray): String {
            val sb = java.lang.StringBuilder((data.size * 4 + 2) / 3)
            var i = 0
            while (i < data.size) {
                val b0 = data[i++].toInt() and 0xFF
                val b1 = if (i < data.size) data[i++].toInt() and 0xFF else -1
                val b2 = if (i < data.size) data[i++].toInt() and 0xFF else -1

                sb.append(ALPHABET[b0 shr 2])
                if (b1 != -1) {
                    sb.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
                    if (b2 != -1) {
                        sb.append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)])
                        sb.append(ALPHABET[b2 and 0x3F])
                    } else {
                        sb.append(ALPHABET[(b1 and 0x0F) shl 2])
                        sb.append('=')
                    }
                } else {
                    sb.append(ALPHABET[(b0 and 0x03) shl 4])
                    sb.append("==")
                }
            }
            return sb.toString()
        }

        fun decode(s: String): ByteArray {
            val clean = s.trim().replace("\n", "").replace("\r", "")
            val len = clean.length
            if (len == 0) return ByteArray(0)
            val padding = when {
                clean.endsWith("==") -> 2
                clean.endsWith("=") -> 1
                else -> 0
            }
            val outLen = (len * 3) / 4 - padding
            val out = ByteArray(outLen)
            var outIdx = 0
            var i = 0
            while (i < len) {
                val c0 = clean[i++]
                val c1 = clean[i++]
                val c2 = if (i < len) clean[i++] else '='
                val c3 = if (i < len) clean[i++] else '='

                val b0 = if (c0.code < 128) DECODE_TABLE[c0.code] else -1
                val b1 = if (c1.code < 128) DECODE_TABLE[c1.code] else -1
                val b2 = if (c2 != '=' && c2.code < 128) DECODE_TABLE[c2.code] else 0
                val b3 = if (c3 != '=' && c3.code < 128) DECODE_TABLE[c3.code] else 0

                val triple = (b0 shl 18) or (b1 shl 12) or (b2 shl 6) or b3

                if (outIdx < outLen) out[outIdx++] = ((triple shr 16) and 0xFF).toByte()
                if (outIdx < outLen && c2 != '=') out[outIdx++] = ((triple shr 8) and 0xFF).toByte()
                if (outIdx < outLen && c3 != '=') out[outIdx++] = (triple and 0xFF).toByte()
            }
            return out
        }
    }

    private fun serializeTemplate(template: VoiceCommandTemplate): String {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeInt(1) // version
        dos.writeUTF(template.action.id)
        dos.writeInt(template.frameCount)
        dos.writeInt(template.featureDim)
        dos.writeInt(template.features.size)
        dos.write(template.features)
        dos.writeInt(template.durationMs)
        dos.writeLong(template.createdAt)

        val sample = template.audioSample
        if (sample != null && sample.isNotEmpty()) {
            dos.writeInt(sample.size)
            dos.write(sample)
        } else {
            dos.writeInt(0)
        }
        dos.flush()

        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(bos.toByteArray())
        deflater.finish()
        val compressedOut = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            compressedOut.write(buf, 0, count)
        }
        deflater.end()

        return Base64Coder.encode(compressedOut.toByteArray())
    }

    private fun serializeFeaturesOnlyForCloud(template: VoiceCommandTemplate): String {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeInt(1) // version
        dos.writeUTF(template.action.id)
        dos.writeInt(template.frameCount)
        dos.writeInt(template.featureDim)
        dos.writeInt(template.features.size)
        dos.write(template.features)
        dos.writeInt(template.durationMs)
        dos.writeLong(template.createdAt)
        dos.writeInt(0) // 0 байт аудио для облака
        dos.flush()

        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(bos.toByteArray())
        deflater.finish()
        val compressedOut = ByteArrayOutputStream()
        val buf = ByteArray(512)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            compressedOut.write(buf, 0, count)
        }
        deflater.end()

        return Base64Coder.encode(compressedOut.toByteArray())
    }

    private fun deserializeCompressedTemplate(action: DirtyHandsAction, base64: String): VoiceCommandTemplate? {
        return try {
            val compressed = Base64Coder.decode(base64)
            val inflater = Inflater()
            inflater.setInput(compressed)
            val decompressedOut = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count == 0 && inflater.needsInput()) break
                decompressedOut.write(buf, 0, count)
            }
            inflater.end()

            val dis = DataInputStream(ByteArrayInputStream(decompressedOut.toByteArray()))
            val ver = dis.readInt()
            val actionId = dis.readUTF()
            val parsedAction = DirtyHandsAction.fromId(actionId) ?: action
            val frameCount = dis.readInt()
            val featureDim = dis.readInt()
            val featSize = dis.readInt()
            val features = ByteArray(featSize)
            dis.readFully(features)
            val durationMs = dis.readInt()
            val createdAt = dis.readLong()

            var audioSample: ByteArray? = null
            if (dis.available() > 0) {
                val sampleSize = dis.readInt()
                if (sampleSize > 0) {
                    val sBuf = ByteArray(sampleSize)
                    dis.readFully(sBuf)
                    audioSample = sBuf
                }
            }

            VoiceCommandTemplate(
                action = parsedAction,
                frameCount = frameCount,
                featureDim = featureDim,
                features = features,
                audioSample = audioSample,
                durationMs = durationMs,
                createdAt = createdAt
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize template: ${e.message}")
            null
        }
    }

    private fun saveTemplateLocally(template: VoiceCommandTemplate) {
        templatesMap[template.action] = template
        val p = prefs ?: return
        val serialized = serializeTemplate(template)
        p.edit().putString("cmd_${template.action.id}", serialized).apply()
        _registeredTemplates.value = HashMap(templatesMap)
    }

    private fun notifyFirebaseSync(template: VoiceCommandTemplate) {
        val cloudPayload = serializeFeaturesOnlyForCloud(template)
        FirebaseSyncManager.onVoiceCommandUpdated(template.action.id, cloudPayload)
    }

    fun getAllCompressedCommands(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((action, template) in templatesMap) {
            result[action.id] = serializeFeaturesOnlyForCloud(template)
        }
        return result
    }

    fun importCloudVoiceCommands(json: JSONObject) {
        val p = prefs ?: return
        val editor = p.edit()
        var updated = false

        for (action in DirtyHandsAction.entries) {
            if (json.has(action.id)) {
                val raw = json.optString(action.id)
                if (raw.isNotEmpty() && !templatesMap.containsKey(action)) {
                    val template = deserializeCompressedTemplate(action, raw)
                    if (template != null) {
                        templatesMap[action] = template
                        editor.putString("cmd_${action.id}", serializeTemplate(template))
                        updated = true
                    }
                }
            }
        }

        if (updated) {
            editor.apply()
            _registeredTemplates.value = HashMap(templatesMap)
            Log.d(TAG, "Imported ${templatesMap.size} voice commands from cloud")
        }
    }
}
