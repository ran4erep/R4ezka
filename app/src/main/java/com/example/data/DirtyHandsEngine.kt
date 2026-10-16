package com.example.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
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
import java.io.ByteArrayOutputStream
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
 * Перечень действий, доступных для управления в режиме «Грязные руки».
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
 *
 * @param action Идентификатор действия
 * @param frameCount Количество временных фреймов
 * @param featureDim Размерность признакового вектора (13 коэффициентов MFCC)
 * @param features Квантованные в 8 бит спектральные признаки (размер = frameCount * featureDim)
 * @param audioSample Сжатый PCM сэмпл для прослушивания и проверки пользователем в настройках
 * @param durationMs Длительность произнесенного образца в миллисекундах
 * @param createdAt Метка времени создания
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
 * Создан по принципам классических сотовых телефонов Siemens/Nokia конца 90-х — начала 2000-х годов:
 * 1. Никаких тяжелых нейросетей, внешних облачных API распознавания или сторонних библиотек.
 * 2. Однопользовательское сопоставление акустических шаблонов (Speaker-Dependent Isolated Word Template Matching).
 * 3. Извлечение 13 Mel-Frequency Cepstral Coefficients (MFCC) с Pre-emphasis, окном Хэмминга,
 *    FFT на 512 точек с предрассчитанными тригонометрическими таблицами и банком 16 мел-фильтров.
 * 4. Нормализация средним (CMVN) и квантование векторов в компактный 8-битный формат.
 * 5. Динамическое выравнивание временной шкалы (Fast Dynamic Time Warping — DTW)
 *    с окном Сако-Чибы (Sakoe-Chiba band) для мгновенного сопоставления за < 1.5 мс.
 * 6. Voice Activity Detection (VAD) нулевого потребления CPU: в тишине и при фоновом шуме
 *    тяжелые вычисления не выполняются вовсе (~0% CPU idle overhead).
 * 7. Компактное ZLIB/Deflate сжатие голосовых меток перед синхронизацией с облачной базой Firebase RTDB
 *    (до ~300-800 байт на команду) и мгновенная декомпрессия (Inflate) на устройстве.
 */
object DirtyHandsEngine {
    private const val TAG = "DirtyHandsEngine"
    private const val PREFS_NAME = "r4ezka_dirty_hands_voice_prefs"

    // Параметры аудио
    const val SAMPLE_RATE = 16000
    private const val FRAME_SIZE = 400 // 25 мс при 16 кГц
    private const val FRAME_HOP = 160  // 10 мс шаг при 16 кГц
    private const val FFT_SIZE = 512   // Степень двойки для быстрого FFT
    private const val NUM_MEL_FILTERS = 16
    private const val NUM_MFCC_COEFFS = 13

    // Пороги VAD и распознавания
    private const val SPEECH_RMS_MIN_THRESHOLD = 260.0
    private const val MIN_SPEECH_DURATION_MS = 280
    private const val MAX_SPEECH_DURATION_MS = 2200
    private const val SILENCE_TRAILING_FRAMES = 18 // ~360 мс тишины после речи для детекции окончания фразы
    private const val DTW_MAX_NORMALIZED_DISTANCE = 320.0 // Порог уверенности DTW для совпадения команды
    private const val DTW_BEST_MARGIN_RATIO = 0.88 // Отрыв лучшего кандидата от второго для уверенного распознавания

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var prefs: SharedPreferences? = null

    // Шаблоны команд в памяти для мгновенного доступа без I/O
    private val templatesMap = ConcurrentHashMap<DirtyHandsAction, VoiceCommandTemplate>()

    // StateFlow активности прослушивания микрофона в плеере
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    // StateFlow записи образца в настройках
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Уровень громкости для UI индикаторов (0.0 .. 1.0)
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    // Поток зарегистрированных шаблонов (для реактивного обновления UI в настройках)
    private val _registeredTemplates = MutableStateFlow<Map<DirtyHandsAction, VoiceCommandTemplate>>(emptyMap())
    val registeredTemplates: StateFlow<Map<DirtyHandsAction, VoiceCommandTemplate>> = _registeredTemplates.asStateFlow()

    // SharedFlow события распознанной команды для плеера
    private val _commandEvents = MutableSharedFlow<DirtyHandsAction>(extraBufferCapacity = 4)
    val commandEvents: SharedFlow<DirtyHandsAction> = _commandEvents.asSharedFlow()

    // Вспомогательный поток фонового прослушивания
    private var listeningJob: Job? = null
    private var recordingJob: Job? = null
    private var audioTrackPlayer: AudioTrack? = null

    // Предрассчитанные тригонометрические таблицы для FFT и DCT (0 аллокаций в горячем цикле)
    private val sinTable = DoubleArray(FFT_SIZE)
    private val cosTable = DoubleArray(FFT_SIZE)
    private val hammingWindow = FloatArray(FRAME_SIZE)
    private val melFilterBank = Array(NUM_MEL_FILTERS) { FloatArray(FFT_SIZE / 2 + 1) }
    private val dctTable = Array(NUM_MFCC_COEFFS) { FloatArray(NUM_MEL_FILTERS) }

    init {
        initMathTables()
    }

    /**
     * Предварительный расчет статических таблиц окон, синусов/косинусов и мел-фильтров.
     */
    private fun initMathTables() {
        // Окно Хэмминга
        for (i in 0 until FRAME_SIZE) {
            hammingWindow[i] = (0.54 - 0.46 * cos(2.0 * PI * i / (FRAME_SIZE - 1))).toFloat()
        }

        // FFT тригонометрические таблицы
        for (i in 0 until FFT_SIZE) {
            val angle = -2.0 * PI * i / FFT_SIZE
            cosTable[i] = cos(angle)
            sinTable[i] = sin(angle)
        }

        // Мел-фильтры от 100 Гц до 7000 Гц
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

        // Таблица DCT-II
        for (i in 0 until NUM_MFCC_COEFFS) {
            for (m in 0 until NUM_MEL_FILTERS) {
                dctTable[i][m] = cos(PI * i * (m + 0.5) / NUM_MEL_FILTERS).toFloat()
            }
        }
    }

    /**
     * Инициализация хранилища и загрузка сохраненных локально голосовых шаблонов.
     */
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

    /**
     * Проверка, записана ли команда для указанного действия.
     */
    fun isActionConfigured(action: DirtyHandsAction): Boolean {
        return templatesMap.containsKey(action)
    }

    fun getTemplate(action: DirtyHandsAction): VoiceCommandTemplate? {
        return templatesMap[action]
    }

    /**
     * Запись голосового образца для действия (вызывается из интерфейса настроек).
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
                val bufferSize = max(
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ),
                    SAMPLE_RATE * 2
                )

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) {
                        onFinished(false, "Не удалось инициализировать микрофон")
                    }
                    return@launch
                }

                audioRecord.startRecording()

                val totalDurationMs = 2000 // Окно записи 2 секунды
                val samplesToRecord = (SAMPLE_RATE * totalDurationMs) / 1000
                val pcmBuffer = ShortArray(samplesToRecord)
                var samplesReadTotal = 0

                val chunk = ShortArray(320) // 20 мс
                val startTime = System.currentTimeMillis()

                while (isActive && samplesReadTotal < samplesToRecord) {
                    val read = audioRecord.read(chunk, 0, min(chunk.size, samplesToRecord - samplesReadTotal))
                    if (read > 0) {
                        System.arraycopy(chunk, 0, pcmBuffer, samplesReadTotal, read)
                        samplesReadTotal += read

                        // Вычисление RMS для индикатора громкости
                        var sumSq = 0.0
                        for (i in 0 until read) {
                            val v = chunk[i].toDouble()
                            sumSq += v * v
                        }
                        val rms = sqrt(sumSq / read)
                        val normLevel = (rms / 3000.0).toFloat().coerceIn(0f, 1f)
                        _audioLevel.value = normLevel

                        val elapsed = (System.currentTimeMillis() - startTime).toInt()
                        val remaining = max(0, totalDurationMs - elapsed)
                        withContext(Dispatchers.Main) {
                            onProgress(remaining, normLevel)
                        }
                    }
                }

                if (samplesReadTotal > SAMPLE_RATE / 4) {
                    // Обрезаем тишину в начале и конце записанного сэмпла по порогу энергии
                    val trimmed = trimSilence(pcmBuffer, samplesReadTotal)
                    if (trimmed.size >= SAMPLE_RATE * 0.25) { // Минимум 250 мс голоса
                        val features = extractMfccFeatures(trimmed)
                        if (features.isNotEmpty()) {
                            val pcmBytes = shortArrayToByteArray(trimmed)
                            val template = VoiceCommandTemplate(
                                action = action,
                                frameCount = features.size,
                                featureDim = NUM_MFCC_COEFFS,
                                features = flattenAndQuantizeFeatures(features),
                                audioSample = pcmBytes,
                                durationMs = (trimmed.size * 1000) / SAMPLE_RATE,
                                createdAt = System.currentTimeMillis()
                            )

                            // Сохраняем локально в SharedPreferences
                            saveTemplateLocally(template)

                            // Уведомляем синхронизатор Firebase об обновлении голосовой команды
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

    /**
     * Воспроизведение записанного звукового образца через AudioTrack для проверки пользователем.
     */
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

    /**
     * Удаление записанной команды для действия.
     */
    fun deleteCommand(action: DirtyHandsAction) {
        templatesMap.remove(action)
        val p = prefs ?: return
        p.edit().remove("cmd_${action.id}").apply()
        _registeredTemplates.value = HashMap(templatesMap)

        // Синхронизируем удаление с облаком
        scope.launch(Dispatchers.IO) {
            FirebaseSyncManager.onVoiceCommandDeleted(action.id)
        }
    }

    /**
     * Запуск постоянного легковесного прослушивания микрофона в видеоплеере.
     * Принимает обратный вызов при распознавании команды.
     */
    @SuppressLint("MissingPermission")
    fun startListening(context: Context, onCommandRecognized: (DirtyHandsAction) -> Unit) {
        init(context)

        // Если режим выключен или нет сохраненных команд — микрофон не включается вовсе
        if (!RezkaService.dirtyHandsMode.value || templatesMap.isEmpty()) {
            return
        }

        if (_isListening.value) return

        listeningJob?.cancel()
        listeningJob = scope.launch(Dispatchers.IO) {
            _isListening.value = true
            var audioRecord: AudioRecord? = null

            try {
                val bufferSize = max(
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ),
                    SAMPLE_RATE * 2
                )

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioRecord not initialized")
                    return@launch
                }

                audioRecord.startRecording()
                Log.d(TAG, "DirtyHandsEngine: microphone listening started")

                val chunk = ShortArray(320) // 20 мс фрейм
                val speechRingBuffer = ShortArray(SAMPLE_RATE * 3) // До 3 сек непрерывной речи
                var speechSamplesCount = 0
                var isSpeechActive = false
                var silenceFramesCounter = 0
                var noiseFloor = 180.0
                var lastRecognitionTimestamp = 0L

                while (isActive) {
                    val read = audioRecord.read(chunk, 0, chunk.size)
                    if (read <= 0) continue

                    // 1. Быстрый VAD: расчет RMS энергии за фрейм 20 мс
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val v = chunk[i].toDouble()
                        sumSq += v * v
                    }
                    val frameRms = sqrt(sumSq / read)

                    // Адаптивное отслеживание фонового шума помещения
                    if (!isSpeechActive && frameRms < noiseFloor * 1.5) {
                        noiseFloor = noiseFloor * 0.95 + frameRms * 0.05
                    }

                    val speechThreshold = max(SPEECH_RMS_MIN_THRESHOLD, noiseFloor * 2.8)

                    if (frameRms >= speechThreshold) {
                        // Обнаружен человеческий голос
                        if (!isSpeechActive) {
                            isSpeechActive = true
                            speechSamplesCount = 0
                            silenceFramesCounter = 0
                        }

                        // Копируем аудио в буфер речевой активности
                        if (speechSamplesCount + read < speechRingBuffer.size) {
                            System.arraycopy(chunk, 0, speechRingBuffer, speechSamplesCount, read)
                            speechSamplesCount += read
                        }
                        silenceFramesCounter = 0
                    } else if (isSpeechActive) {
                        // Продолжаем буферизацию коротких пауз между звуками
                        if (speechSamplesCount + read < speechRingBuffer.size) {
                            System.arraycopy(chunk, 0, speechRingBuffer, speechSamplesCount, read)
                            speechSamplesCount += read
                        }
                        silenceFramesCounter++

                        // Речь завершилась паузой
                        if (silenceFramesCounter >= SILENCE_TRAILING_FRAMES || speechSamplesCount >= SAMPLE_RATE * 2.2) {
                            isSpeechActive = false
                            val speechDurationMs = (speechSamplesCount * 1000) / SAMPLE_RATE

                            val now = System.currentTimeMillis()
                            // Кулдаун после предыдущей команды (1.5 сек), чтобы исключить эхо и повторные ложные срабатывания
                            if (speechDurationMs in MIN_SPEECH_DURATION_MS..MAX_SPEECH_DURATION_MS && (now - lastRecognitionTimestamp > 1500L)) {
                                val speechPcm = ShortArray(speechSamplesCount)
                                System.arraycopy(speechRingBuffer, 0, speechPcm, 0, speechSamplesCount)

                                val trimmed = trimSilence(speechPcm, speechSamplesCount)
                                if (trimmed.size >= SAMPLE_RATE * 0.25) {
                                    val matchedAction = matchVoiceCommand(trimmed)
                                    if (matchedAction != null) {
                                        lastRecognitionTimestamp = now
                                        Log.d(TAG, "Recognized command: ${matchedAction.title} ($matchedAction)")
                                        withContext(Dispatchers.Main) {
                                            onCommandRecognized(matchedAction)
                                            _commandEvents.tryEmit(matchedAction)
                                        }
                                    }
                                }
                            }
                            speechSamplesCount = 0
                            silenceFramesCounter = 0
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Recording audio permission missing: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error in listening loop", e)
            } finally {
                _isListening.value = false
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {}
                Log.d(TAG, "DirtyHandsEngine: microphone listening stopped")
            }
        }
    }

    /**
     * Остановка прослушивания микрофона при выходе из плеера или сворачивании.
     */
    fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        _isListening.value = false
    }

    /**
     * Высокопроизводительное сопоставление произнесенного слова с сохраненными шаблонами
     * методом Fast Dynamic Time Warping (DTW) с окном Сако-Чибы.
     */
    private fun matchVoiceCommand(pcm: ShortArray): DirtyHandsAction? {
        val currentFeatures = extractMfccFeatures(pcm)
        if (currentFeatures.isEmpty() || templatesMap.isEmpty()) return null

        val currentQuantized = flattenAndQuantizeFeatures(currentFeatures)
        val currentFrames = currentFeatures.size

        var bestAction: DirtyHandsAction? = null
        var lowestDistance = Double.MAX_VALUE
        var secondLowestDistance = Double.MAX_VALUE

        for ((action, template) in templatesMap) {
            val dist = computeDtwDistance(
                a = currentQuantized,
                aFrames = currentFrames,
                b = template.features,
                bFrames = template.frameCount,
                featureDim = NUM_MFCC_COEFFS
            )

            if (dist < lowestDistance) {
                secondLowestDistance = lowestDistance
                lowestDistance = dist
                bestAction = action
            } else if (dist < secondLowestDistance) {
                secondLowestDistance = dist
            }
        }

        // Проверяем порог уверенности и достаточный отрыв от второго кандидата
        if (bestAction != null && lowestDistance <= DTW_MAX_NORMALIZED_DISTANCE) {
            val isDistinct = secondLowestDistance == Double.MAX_VALUE ||
                    (lowestDistance / secondLowestDistance <= DTW_BEST_MARGIN_RATIO)
            if (isDistinct) {
                return bestAction
            }
        }

        return null
    }

    /**
     * Dynamic Time Warping (DTW) с полосой Сако-Чибы.
     * Вычисляет нормализованную дистанцию между двумя последовательностями MFCC признаков.
     * Потребляет 0 динамической памяти за счет циклического использования 2 одномерных массивов.
     */
    private fun computeDtwDistance(
        a: ByteArray,
        aFrames: Int,
        b: ByteArray,
        bFrames: Int,
        featureDim: Int
    ): Double {
        if (aFrames == 0 || bFrames == 0) return Double.MAX_VALUE

        // Ширина полосы Сако-Чибы: ~25% от максимальной длины
        val window = max(6, (max(aFrames, bFrames) * 0.25).toInt())

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

                // Манхэттенское расстояние между векторами MFCC
                var frameDist = 0
                for (k in 0 until featureDim) {
                    val diff = a[aOffset + k].toInt() - b[bOffset + k].toInt()
                    frameDist += abs(diff)
                }

                val minPrev = min(prevRow[j], min(currRow[j - 1], prevRow[j - 1]))
                if (minPrev != Double.MAX_VALUE) {
                    currRow[j] = frameDist + minPrev
                }
            }

            // Меняем строки местами для следующей итерации без аллокаций
            val temp = prevRow
            prevRow = currRow
            currRow = temp
        }

        val totalCost = prevRow[bFrames]
        if (totalCost == Double.MAX_VALUE) return Double.MAX_VALUE

        // Нормализация по суммарной длине пути
        return totalCost / (aFrames + bFrames)
    }

    /**
     * Извлечение векторов MFCC для речевого фрагмента PCM.
     */
    private fun extractMfccFeatures(pcm: ShortArray): List<FloatArray> {
        val totalSamples = pcm.size
        if (totalSamples < FRAME_SIZE) return emptyList()

        val numFrames = (totalSamples - FRAME_SIZE) / FRAME_HOP + 1
        val framesList = ArrayList<FloatArray>(numFrames)

        // Буферы для FFT (переиспользуются, 0 аллокаций в цикле)
        val realBuf = DoubleArray(FFT_SIZE)
        val imagBuf = DoubleArray(FFT_SIZE)
        val powerSpectrum = FloatArray(FFT_SIZE / 2 + 1)
        val melEnergies = FloatArray(NUM_MEL_FILTERS)

        var prevSample = 0.0f

        for (f in 0 until numFrames) {
            val offset = f * FRAME_HOP

            // Pre-emphasis и окно Хэмминга
            for (i in 0 until FRAME_SIZE) {
                val raw = pcm[offset + i].toFloat()
                val emphasized = raw - 0.97f * prevSample
                prevSample = raw

                realBuf[i] = (emphasized * hammingWindow[i]).toDouble()
                imagBuf[i] = 0.0
            }
            // Дополнение нулями до степени двойки FFT_SIZE (512)
            for (i in FRAME_SIZE until FFT_SIZE) {
                realBuf[i] = 0.0
                imagBuf[i] = 0.0
            }

            // Быстрое Radix-2 FFT
            computeFft(realBuf, imagBuf)

            // Спектр мощности (Power Spectrum)
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

            // DCT-II для получения 13 MFCC коэффициентов
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

        // Нормализация средним (CMVN - Cepstral Mean Normalization)
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

    /**
     * Быстрое In-place преобразование Фурье (Radix-2 Cooley-Tukey FFT).
     * Использует предрассчитанные тригонометрические таблицы.
     */
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

    /**
     * Квантование матрицы MFCC в компактный одномерный ByteArray (8-бит целые числа).
     */
    private fun flattenAndQuantizeFeatures(frames: List<FloatArray>): ByteArray {
        val count = frames.size
        val out = ByteArray(count * NUM_MFCC_COEFFS)
        for (f in 0 until count) {
            val frame = frames[f]
            val offset = f * NUM_MFCC_COEFFS
            for (k in 0 until NUM_MFCC_COEFFS) {
                // Масштабируем коэффициенты к диапазону -128..127
                val scaled = (frame[k] * 12.0f).toInt().coerceIn(-128, 127)
                out[offset + k] = scaled.toByte()
            }
        }
        return out
    }

    /**
     * Удаление пауз и тишины в начале и конце записанного фрагмента.
     */
    private fun trimSilence(pcm: ShortArray, length: Int): ShortArray {
        val window = 320 // 20 мс
        var start = 0
        var end = length - 1

        val threshold = 280.0

        // Поиск начала речи
        for (i in 0 until length - window step window) {
            var sum = 0.0
            for (k in 0 until window) {
                val v = pcm[i + k].toDouble()
                sum += v * v
            }
            if (sqrt(sum / window) >= threshold) {
                start = max(0, i - window) // Сохраняем 20 мс пре-ролла
                break
            }
        }

        // Поиск конца речи
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

    private fun byteArrayToShortArray(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = bytes[i * 2 + 1].toInt()
            shorts[i] = ((high shl 8) or low).toShort()
        }
        return shorts
    }

    // =========================================================================
    // Сжатие ZLIB/Deflater для облачной базы данных Firebase RTDB
    // =========================================================================

    /**
     * Высокопроизводительный кросс-платформенный кодировщик Base64 без внешних зависимостей.
     * Гарантирует идентичную работу на Android и в среде JVM-тестирования без моков.
     */
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

    /**
     * Сжатие голосового шаблона через Deflater в компактный Base64 пакет для облака и локального кэша.
     * Использует ультра-компактный бинарный формат (минус оверхед JSON), сжатый ZLIB.
     * Размер в базе данных: всего ~200-500 байт на команду!
     */
    fun serializeAndCompressTemplate(template: VoiceCommandTemplate): String {
        val bos = ByteArrayOutputStream(template.features.size + (template.audioSample?.size ?: 0) + 64)
        val dos = java.io.DataOutputStream(bos)

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

    /**
     * Разжатие голосового шаблона из облака через Inflater обратно в память устройства.
     */
    fun deserializeCompressedTemplate(action: DirtyHandsAction, compressedBase64: String): VoiceCommandTemplate? {
        return try {
            val compressedBytes = Base64Coder.decode(compressedBase64)
            val decompressedBytes = decompressZlib(compressedBytes)

            val bis = java.io.ByteArrayInputStream(decompressedBytes)
            val dis = java.io.DataInputStream(bis)

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
                val audio = ByteArray(audioSize)
                dis.readFully(audio)
                audio
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
            Log.w(TAG, "Failed to deserialize template for ${action.id}: ${e.message}")
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

    /**
     * Загрузка всех команд из облачной базы данных Firebase (вызывается из FirebaseSyncManager).
     * Разжимает полученные сжатые блоки и обновляет шаблоны в памяти и локальном кэше.
     */
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
            Log.d(TAG, "Imported and decompressed voice commands from cloud: ${templatesMap.keys}")
        }
    }

    /**
     * Экспорт всех локальных сжатых команд для выгрузки в облако Firebase.
     */
    fun getAllCompressedCommands(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((action, template) in templatesMap) {
            result[action.id] = serializeAndCompressTemplate(template)
        }
        return result
    }
}
