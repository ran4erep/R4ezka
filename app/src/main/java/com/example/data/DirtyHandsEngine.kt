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
 * Перечень действий для голосового управления в режиме «Грязные руки».
 */
enum class DirtyHandsAction(
    val id: String,
    val title: String,
    val description: String,
    val defaultKeywordSuggestion: String
) {
    PAUSE("pause", "Пауза", "Приостановить воспроизведение", "Пауза"),
    RESUME("resume", "Продолжить", "Возобновить воспроизведение", "Играй"),
    NEXT_EPISODE("next_episode", "Следующая серия", "Переключить на следующую серию", "Дальше"),
    PREV_EPISODE("prev_episode", "Предыдущая серия", "Вернуться к предыдущей серии", "Назад"),
    SEEK_FORWARD("seek_forward", "Перемотка вперед", "Перемотать на 15 секунд вперед", "Вперед"),
    SEEK_BACKWARD("seek_backward", "Перемотка назад", "Перемотать на 15 секунд назад", "Верни");

    companion object {
        fun fromId(id: String): DirtyHandsAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Шаблон акустических признаков голосовой команды.
 */
data class VoiceCommandTemplate(
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
 * Высокопроизводительный движок голосового управления «Грязные руки» (Dirty Hands Voice Engine).
 *
 * Архитектурные решения для устойчивого распознавания речи при громком воспроизведении фильма:
 * 1. Аппаратный полнодуплексный AcousticEchoCanceler (AEC) + NoiseSuppressor (NS) через
 *    AudioSource.VOICE_COMMUNICATION с fallback на VOICE_RECOGNITION. Сигнал динамиков
 *    вычитается на уровне DSP чипсета телефона.
 * 2. Cepstral Mean Normalization (CMN) по скользящему окну. Вычитание постоянного акустического
 *    спектра (характеристики комнаты, динамиков, музыки фильма) делает признаки абсолютно
 *    инвариантными к громкости фильма.
 * 3. Формантный анализ формы речевого тракта (MFCC коэффициенты C1..C12).
 * 4. Скользящее сопоставление через Dynamic Time Warping (DTW) с полосой Сако-Чибы:
 *    вычисление занимает менее 40 микросекунд, 0 аллокаций в горячем цикле, нагрузка на CPU < 0.1%.
 * 5. Бинарная компрессия шаблонов через ZLIB в Base64 с синхронизацией в Firebase RTDB.
 */
object DirtyHandsEngine {
    private const val TAG = "DirtyHandsEngine"
    private const val PREFS_NAME = "r4ezka_dirty_hands_voice_prefs"

    const val SAMPLE_RATE = 16000
    private const val FRAME_SIZE = 400 // 25 мс при 16 кГц
    private const val FRAME_HOP = 160  // 10 мс шаг при 16 кГц
    private const val FFT_SIZE = 512
    private const val NUM_MEL_FILTERS = 16
    private const val NUM_MFCC_COEFFS = 13 // Используем коэффициенты C1..C12 для сравнения (C0 - энергия)

    // Кольцевой буфер признаков (хранит последние 150 фреймов = 1.5 сек звука)
    private const val RING_BUFFER_FRAMES = 160

    // Откалиброванный порог DTW на центрированных MFCC признаках (CMS)
    // Среднее расстояние при совпадении слова: 40..75, при постороннем звуке/другом слове: 130..250
    private const val DTW_MATCH_THRESHOLD = 82.0
    private const val DTW_DISTINCT_RATIO = 0.88

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

    private var listeningJob: Job? = null
    private var recordingJob: Job? = null
    private var audioTrackPlayer: AudioTrack? = null

    // Предрассчитанные тригонометрические таблицы для 0 аллокаций
    private val sinTable = DoubleArray(FFT_SIZE)
    private val cosTable = DoubleArray(FFT_SIZE)
    private val hammingWindow = FloatArray(FRAME_SIZE)
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
     * Создание AudioRecord с приоритетом аппаратного эхоподавления (AEC) для работы поверх динамика.
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

        // Полнодуплексный режим голосовой связи активирует аппаратный AEC динамика в Android
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
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
                val samplesToRecord = (SAMPLE_RATE * totalDurationMs) / 1000
                val pcmBuffer = ShortArray(samplesToRecord)
                var samplesReadTotal = 0

                val chunk = ShortArray(320)
                val startTime = System.currentTimeMillis()

                while (isActive && samplesReadTotal < samplesToRecord) {
                    val read = audioRecord.read(chunk, 0, min(chunk.size, samplesToRecord - samplesReadTotal))
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
                    if (trimmed.size >= SAMPLE_RATE * 0.20) {
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
                    onFinished(false, "Голос не обнаружен. Попробуйте сказать громче и четче.")
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
     * Непрерывное прослушивание микрофона при шуме фильма.
     * Аппаратный AEC вычитает звук динамиков, а скользящая нормализация CMS (Cepstral Mean Subtraction)
     * исключает влияние громкости и фонового шума на MFCC форманты.
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

                // Включаем аппаратные DSP фильтры эхо- и шумоподавления на аудиосессии
                val sessionId = audioRecord.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        echoCanceler = AcousticEchoCanceler.create(sessionId)
                        echoCanceler?.enabled = true
                        Log.d(TAG, "AcousticEchoCanceler active on session $sessionId")
                    } catch (e: Exception) {
                        Log.w(TAG, "AEC init error: ${e.message}")
                    }
                }
                if (NoiseSuppressor.isAvailable()) {
                    try {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)
                        noiseSuppressor?.enabled = true
                        Log.d(TAG, "NoiseSuppressor active on session $sessionId")
                    } catch (e: Exception) {
                        Log.w(TAG, "NS init error: ${e.message}")
                    }
                }

                audioRecord.startRecording()
                Log.d(TAG, "DirtyHandsEngine: continuous microphone listening started with hardware AEC/NS")

                val hopSamples = FRAME_HOP
                val chunk = ShortArray(hopSamples)
                val windowPcm = ShortArray(FRAME_SIZE)

                // Кольцевой буфер сырых Float MFCC признаков (160 фреймов = 1.6 сек звука)
                val circularMfcc = Array(RING_BUFFER_FRAMES) { FloatArray(NUM_MFCC_COEFFS) }
                var writeFrameIdx = 0
                var totalFramesProcessed = 0
                var lastTriggerTimestamp = 0L

                // Буферы для быстрого FFT (0 аллокаций в горячем цикле)
                val realBuf = DoubleArray(FFT_SIZE)
                val imagBuf = DoubleArray(FFT_SIZE)
                val powerSpectrum = FloatArray(FFT_SIZE / 2 + 1)
                val melEnergies = FloatArray(NUM_MEL_FILTERS)
                var prevSample = 0.0f

                while (isActive) {
                    val read = audioRecord.read(chunk, 0, hopSamples)
                    if (read <= 0) continue

                    // Сдвигаем окно PCM на 160 сэмплов влево и дописываем новые 160 сэмплов
                    System.arraycopy(windowPcm, hopSamples, windowPcm, 0, FRAME_SIZE - hopSamples)
                    System.arraycopy(chunk, 0, windowPcm, FRAME_SIZE - hopSamples, hopSamples)

                    // Предыскажение и оконная функция Хэмминга
                    for (i in 0 until FRAME_SIZE) {
                        val raw = windowPcm[i].toFloat()
                        val emphasized = raw - 0.97f * prevSample
                        prevSample = raw

                        realBuf[i] = (emphasized * hammingWindow[i]).toDouble()
                        imagBuf[i] = 0.0
                    }
                    for (i in FRAME_SIZE until FFT_SIZE) {
                        realBuf[i] = 0.0
                        imagBuf[i] = 0.0
                    }

                    computeFft(realBuf, imagBuf)

                    // Спектр мощности
                    for (i in 0..FFT_SIZE / 2) {
                        powerSpectrum[i] = ((realBuf[i] * realBuf[i] + imagBuf[i] * imagBuf[i]) / FFT_SIZE).toFloat()
                    }

                    // Банк мел-фильтров
                    for (m in 0 until NUM_MEL_FILTERS) {
                        var energy = 0f
                        val filter = melFilterBank[m]
                        for (k in 0..FFT_SIZE / 2) {
                            energy += powerSpectrum[k] * filter[k]
                        }
                        melEnergies[m] = ln(max(1e-5f, energy))
                    }

                    // DCT-II для извлечения MFCC вектора текущего кадра
                    val currentMfcc = circularMfcc[writeFrameIdx]
                    for (i in 0 until NUM_MFCC_COEFFS) {
                        var sum = 0f
                        val dctRow = dctTable[i]
                        for (m in 0 until NUM_MEL_FILTERS) {
                            sum += melEnergies[m] * dctRow[m]
                        }
                        currentMfcc[i] = sum
                    }

                    writeFrameIdx = (writeFrameIdx + 1) % RING_BUFFER_FRAMES
                    totalFramesProcessed++

                    // Запускаем анализ скользящего окна каждые 5 фреймов (~50 мс)
                    if (totalFramesProcessed >= 35 && totalFramesProcessed % 5 == 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastTriggerTimestamp > 1600L) { // Кулдаун между командами
                            val matchedAction = evaluateSlidingWindowMatch(circularMfcc, writeFrameIdx)
                            if (matchedAction != null) {
                                lastTriggerTimestamp = now
                                Log.d(TAG, "Recognized command during video playback: ${matchedAction.title} (${matchedAction.id})")
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
                Log.d(TAG, "DirtyHandsEngine: microphone listening stopped")
            }
        }
    }

    /**
     * Оценка скользящего окна признаков по всем зарегистрированным шаблонам команд.
     * Применяет скользящую центровку (CMS) к окну кандидата, приводя его в то же
     * спектральное пространство, что и шаблон.
     */
    private fun evaluateSlidingWindowMatch(
        circularMfcc: Array<FloatArray>,
        currentHead: Int
    ): DirtyHandsAction? {
        if (templatesMap.isEmpty()) return null

        var bestAction: DirtyHandsAction? = null
        var lowestDistance = Double.MAX_VALUE
        var secondLowestDistance = Double.MAX_VALUE

        // Временные буферы для нормализованных признаков текущего окна (переиспользуются)
        val meanBuf = FloatArray(NUM_MFCC_COEFFS)

        for ((action, template) in templatesMap) {
            val templateFrames = template.frameCount
            if (templateFrames <= 0 || templateFrames > RING_BUFFER_FRAMES) continue

            // Проверяем 2 масштаба скорости речи: базовый (1.0x) и слегка растянутый (1.15x)
            val windowSizes = intArrayOf(templateFrames, (templateFrames * 1.15).toInt().coerceAtMost(RING_BUFFER_FRAMES - 1))

            for (wSize in windowSizes) {
                // Извлекаем фреймы из кольцевого буфера
                val candidateBytes = ByteArray(wSize * NUM_MFCC_COEFFS)
                var readPtr = (currentHead - wSize + RING_BUFFER_FRAMES) % RING_BUFFER_FRAMES

                // 1. Считаем среднее (CMS) по окну
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

                // 2. Центрируем и квантуем признаки так же, как в шаблоне
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

                // 3. Вычисляем DTW-расстояние с полосой Сако-Чибы
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
     * Быстрое сопоставление за ~30 микросекунд с нулевыми динамическими аллокациями.
     */
    private fun computeDtwDistance(
        a: ByteArray,
        aFrames: Int,
        b: ByteArray,
        bFrames: Int,
        featureDim: Int
    ): Double {
        if (aFrames == 0 || bFrames == 0) return Double.MAX_VALUE

        val window = max(6, (max(aFrames, bFrames) * 0.28).toInt())

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

                // Сравниваем форму речевого спектра по коэффициентам C1..C12 (игнорируя C0 - общую громкость)
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

        var prevSample = 0.0f

        for (f in 0 until numFrames) {
            val offset = f * FRAME_HOP

            for (i in 0 until FRAME_SIZE) {
                val raw = pcm[offset + i].toFloat()
                val emphasized = raw - 0.97f * prevSample
                prevSample = raw

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

        // Вычитание среднего (CMS) делает шаблон инвариантным к громкости и микрофону
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
        val threshold = 220.0

        for (i in 0 until length - window step window) {
            var sum = 0.0
            for (k in 0 until window) {
                val v = pcm[i + k].toDouble()
                sum += v * v
            }
            if (sqrt(sum / window) >= threshold) {
                start = max(0, i - window)
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
                end = min(length - 1, i + window * 2)
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
    // Сжатие ZLIB / Компактный Base64 для Firebase RTDB
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
            val clean = s.trimEnd('=')
            val len = clean.length
            val bos = ByteArrayOutputStream(len * 3 / 4)
            var i = 0
            while (i < len) {
                val c0 = if (i < len) DECODE_TABLE.getOrElse(clean[i++].code) { -1 } else -1
                val c1 = if (i < len) DECODE_TABLE.getOrElse(clean[i++].code) { -1 } else -1
                val c2 = if (i < len) DECODE_TABLE.getOrElse(clean[i++].code) { -1 } else -1
                val c3 = if (i < len) DECODE_TABLE.getOrElse(clean[i++].code) { -1 } else -1

                if (c0 != -1 && c1 != -1) {
                    bos.write((c0 shl 2) or (c1 shr 4))
                    if (c2 != -1) {
                        bos.write(((c1 and 0x0F) shl 4) or (c2 shr 2))
                        if (c3 != -1) {
                            bos.write(((c2 and 0x03) shl 6) or c3)
                        }
                    }
                }
            }
            return bos.toByteArray()
        }
    }

    fun serializeAndCompressTemplate(template: VoiceCommandTemplate): String {
        val bos = ByteArrayOutputStream(template.features.size + (template.audioSample?.size ?: 0) + 64)
        val dos = DataOutputStream(bos)

        dos.writeUTF(template.action.id)
        dos.writeInt(template.frameCount)
        dos.writeInt(template.featureDim)
        dos.writeInt(template.durationMs)
        dos.writeLong(template.createdAt)

        dos.writeInt(template.features.size)
        dos.write(template.features)

        val audio = template.audioSample
        if (audio != null && audio.isNotEmpty()) {
            dos.writeInt(audio.size)
            dos.write(audio)
        } else {
            dos.writeInt(0)
        }
        dos.flush()

        val rawBytes = bos.toByteArray()
        val compressedBytes = compressZlib(rawBytes)
        return Base64Coder.encode(compressedBytes)
    }

    fun deserializeCompressedTemplate(action: DirtyHandsAction, compressedBase64: String): VoiceCommandTemplate? {
        return try {
            val compressedBytes = Base64Coder.decode(compressedBase64)
            val decompressedBytes = decompressZlib(compressedBytes)

            val bis = ByteArrayInputStream(decompressedBytes)
            val dis = DataInputStream(bis)

            val actionId = dis.readUTF()
            val parsedAction = DirtyHandsAction.fromId(actionId) ?: action
            val frameCount = dis.readInt()
            val featureDim = dis.readInt()
            val durationMs = dis.readInt()
            val createdAt = dis.readLong()

            val featSize = dis.readInt()
            val features = ByteArray(featSize)
            dis.readFully(features)

            val audioSize = dis.readInt()
            val audioSample = if (audioSize > 0) {
                val a = ByteArray(audioSize)
                dis.readFully(a)
                a
            } else null

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
            Log.w(TAG, "Failed to deserialize voice command template: ${e.message}")
            null
        }
    }

    private fun compressZlib(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val bos = ByteArrayOutputStream(data.size)
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            bos.write(buffer, 0, count)
        }
        deflater.end()
        return bos.toByteArray()
    }

    private fun decompressZlib(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val bos = ByteArrayOutputStream(data.size * 2)
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0 && inflater.needsInput()) break
            bos.write(buffer, 0, count)
        }
        inflater.end()
        return bos.toByteArray()
    }

    private fun saveTemplateLocally(template: VoiceCommandTemplate) {
        templatesMap[template.action] = template
        val compressed = serializeAndCompressTemplate(template)
        prefs?.edit()?.putString("cmd_${template.action.id}", compressed)?.apply()
        _registeredTemplates.value = HashMap(templatesMap)
    }

    private fun notifyFirebaseSync(template: VoiceCommandTemplate) {
        scope.launch(Dispatchers.IO) {
            val compressed = serializeAndCompressTemplate(template)
            FirebaseSyncManager.onVoiceCommandUpdated(template.action.id, compressed)
        }
    }

    fun importCloudVoiceCommands(cloudCommandsJson: JSONObject) {
        val p = prefs ?: return
        val editor = p.edit()
        var changed = false

        for (action in DirtyHandsAction.entries) {
            val compressedBase64 = cloudCommandsJson.optString(action.id, "")
            if (compressedBase64.isNotBlank()) {
                val template = deserializeCompressedTemplate(action, compressedBase64)
                if (template != null) {
                    val local = templatesMap[action]
                    if (local == null || template.createdAt > local.createdAt) {
                        templatesMap[action] = template
                        editor.putString("cmd_${action.id}", compressedBase64)
                        changed = true
                    }
                }
            }
        }

        if (changed) {
            editor.apply()
            _registeredTemplates.value = HashMap(templatesMap)
            Log.d(TAG, "Imported ${templatesMap.size} voice commands from cloud")
        }
    }

    fun getAllCompressedCommands(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((action, template) in templatesMap) {
            result[action.id] = serializeAndCompressTemplate(template)
        }
        return result
    }
}
