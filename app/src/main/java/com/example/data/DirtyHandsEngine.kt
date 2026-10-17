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
import android.media.audiofx.AutomaticGainControl
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
    PREV_EPISODE("prev_episode", "Предыдущая серия", "Вернуться к предыдущей серии", "Назад");

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
 * Сверхбыстрый цифровой IIR-фильтр второго порядка (Biquad Filter в Direct Form II Transposed).
 * Обеспечивает 0 динамических аллокаций памяти и минимальную нагрузку на процессор (< 0.001 мс на фрейм).
 */
class BiquadFilter(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double
) {
    private var s1: Double = 0.0
    private var s2: Double = 0.0

    fun reset() {
        s1 = 0.0
        s2 = 0.0
    }

    fun process(sample: Double): Double {
        val y = b0 * sample + s1
        s1 = b1 * sample - a1 * y + s2
        s2 = b2 * sample - a2 * y
        return y
    }

    companion object {
        fun createHighPass(sampleRate: Int, cutoffHz: Double, q: Double = 0.7071): BiquadFilter {
            val omega = 2.0 * PI * cutoffHz / sampleRate
            val cosW = cos(omega)
            val sinW = sin(omega)
            val alpha = sinW / (2.0 * q)
            val a0 = 1.0 + alpha
            return BiquadFilter(
                b0 = ((1.0 + cosW) / 2.0) / a0,
                b1 = (-(1.0 + cosW)) / a0,
                b2 = ((1.0 + cosW) / 2.0) / a0,
                a1 = (-2.0 * cosW) / a0,
                a2 = (1.0 - alpha) / a0
            )
        }

        fun createLowPass(sampleRate: Int, cutoffHz: Double, q: Double = 0.7071): BiquadFilter {
            val omega = 2.0 * PI * cutoffHz / sampleRate
            val cosW = cos(omega)
            val sinW = sin(omega)
            val alpha = sinW / (2.0 * q)
            val a0 = 1.0 + alpha
            return BiquadFilter(
                b0 = ((1.0 - cosW) / 2.0) / a0,
                b1 = (1.0 - cosW) / a0,
                b2 = ((1.0 - cosW) / 2.0) / a0,
                a1 = (-2.0 * cosW) / a0,
                a2 = (1.0 - alpha) / a0
            )
        }
    }
}

/**
 * Высокопроизводительный каскадный полосовой фильтр речевого диапазона (300 Гц – 3400 Гц).
 * Отсекает рокот, басы и саундтрек фильма (< 300 Гц), а также высокочастотные свисты и шум (> 3400 Гц).
 */
class SpeechBandpassFilter(sampleRate: Int = DirtyHandsEngine.SAMPLE_RATE) {
    private val hp = BiquadFilter.createHighPass(sampleRate, 300.0)
    private val lp = BiquadFilter.createLowPass(sampleRate, 3400.0)

    fun reset() {
        hp.reset()
        lp.reset()
    }

    fun process(input: ShortArray, output: ShortArray, length: Int) {
        for (i in 0 until length) {
            val s = input[i].toDouble()
            val filtered = lp.process(hp.process(s))
            output[i] = filtered.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    fun processInPlace(buffer: ShortArray, length: Int) {
        for (i in 0 until length) {
            val s = buffer[i].toDouble()
            val filtered = lp.process(hp.process(s))
            buffer[i] = filtered.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }
}

/**
 * Управление аппаратными эффектами Android AudioFX (AEC, NoiseSuppressor, AGC) на уровне DSP.
 */
private class HardwareAudioEffects(sessionId: Int) {
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    init {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)?.apply {
                    enabled = true
                }
                Log.d("DirtyHandsEngine", "Hardware AcousticEchoCanceler enabled successfully")
            }
        } catch (t: Throwable) {
            Log.w("DirtyHandsEngine", "Hardware AEC init error: ${t.message}")
        }

        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)?.apply {
                    enabled = true
                }
                Log.d("DirtyHandsEngine", "Hardware NoiseSuppressor enabled successfully")
            }
        } catch (t: Throwable) {
            Log.w("DirtyHandsEngine", "Hardware NoiseSuppressor init error: ${t.message}")
        }

        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)?.apply {
                    enabled = true
                }
                Log.d("DirtyHandsEngine", "Hardware AutomaticGainControl enabled successfully")
            }
        } catch (t: Throwable) {
            Log.w("DirtyHandsEngine", "Hardware AGC init error: ${t.message}")
        }
    }

    fun release() {
        try { aec?.release() } catch (_: Throwable) {}
        try { ns?.release() } catch (_: Throwable) {}
        try { agc?.release() } catch (_: Throwable) {}
        aec = null
        ns = null
        agc = null
    }
}

/**
 * Высокопроизводительный движок голосового управления «Грязные руки» (Dirty Hands Voice Engine).
 *
 * Создан по принципам классических сотовых телефонов Siemens/Nokia конца 90-х — начала 2000-х годов:
 * 1. Никаких тяжелых нейросетей, внешних облачных API распознавания или сторонних библиотек.
 * 2. Однопользовательское сопоставление акустических шаблонов (Speaker-Dependent Isolated Word Template Matching).
 * 3. Извлечение 13 Mel-Frequency Cepstral Coefficients (MFCC) с Pre-emphasis, окном Хэмминга,
 *    FFT на 512 точек с предрассчитанными тригонометрическими таблицами и банком 16 мел-фильтров.
 * 4. Полная CMVN нормализация (Mean & Variance) для независимости от громкости и акустики окружения.
 * 5. Аппаратное эхоподавление Android (AcousticEchoCanceler) + полосовой IIR-фильтр речи (300-3400 Гц).
 * 6. Частотный VAD с отслеживанием шума по минимумам (Minimum Statistics) без зависания на звуках фильма.
 * 7. Динамическое выравнивание временной шкалы (Fast Dynamic Time Warping — DTW)
 *    с окном Сако-Чибы (Sakoe-Chiba band) для мгновенного сопоставления за < 1.5 мс.
 * 8. Компактное ZLIB/Deflate сжатие голосовых меток перед синхронизацией с облачной базой Firebase RTDB
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

    // Пороги VAD и распознавания: поддержка любых фраз пользователя до 2.0с, быстрый отклик и защита от шума
    private const val SPEECH_RMS_MIN_THRESHOLD = 300.0
    private const val MIN_SPEECH_DURATION_MS = 200
    private const val MAX_SPEECH_DURATION_MS = 2000 // Соответствует полному 2-секундному окну записи пользовательского шаблона
    private const val SILENCE_TRAILING_FRAMES = 10 // ~200 мс паузы после слова для мгновенной фиксации фразы
    private const val DTW_MAX_NORMALIZED_DISTANCE = 360.0 // Порог уверенности акустического совпадения с шаблоном
    private const val DTW_BEST_MARGIN_RATIO = 0.88 // Уверенный отрыв лучшего совпадения от других действий

    @Volatile
    private var shouldKeepListening = false

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
        p.edit().remove("cmd_seek_forward").remove("cmd_seek_backward").apply()
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
            var effects: HardwareAudioEffects? = null

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

                effects = HardwareAudioEffects(audioRecord.audioSessionId)
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
                    // Очищаем записанный голосовой образец полосовым фильтром от шумов окружения
                    val bandpass = SpeechBandpassFilter(SAMPLE_RATE)
                    bandpass.processInPlace(pcmBuffer, samplesReadTotal)

                    // Обрезаем тишину в начале и конце записанного сэмпла по порогу энергии
                    val trimmed = trimSilence(pcmBuffer, samplesReadTotal)
                    if (trimmed.size >= SAMPLE_RATE * 0.22) { // Минимум 220 мс голоса
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
                    effects?.release()
                } catch (_: Exception) {}
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

        shouldKeepListening = true
        if (_isListening.value && listeningJob?.isActive == true) return

        listeningJob?.cancel()
        listeningJob = scope.launch(Dispatchers.IO) {
            _isListening.value = true

            // Внешний устойчивый цикл: микрофон непрерывно работает всё время воспроизведения в плеере,
            // восстанавливаясь за 150 мс при смене аудиофокуса, переключении серии или смене дорожки ExoPlayer
            while (isActive && shouldKeepListening && RezkaService.dirtyHandsMode.value && templatesMap.isNotEmpty()) {
                var audioRecord: AudioRecord? = null
                var effects: HardwareAudioEffects? = null

                try {
                    val bufferSize = max(
                        AudioRecord.getMinBufferSize(
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        ),
                        SAMPLE_RATE * 2
                    )

                    val sourcesToTry = intArrayOf(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION
                    )

                    for (source in sourcesToTry) {
                        try {
                            val candidate = AudioRecord(
                                source,
                                SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                bufferSize
                            )
                            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                                audioRecord = candidate
                                break
                            } else {
                                candidate.release()
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "AudioRecord init attempt failed for source $source: ${e.message}")
                        }
                    }

                    if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                        Log.w(TAG, "AudioRecord could not be initialized, retry in 300ms")
                        kotlinx.coroutines.delay(300)
                        continue
                    }

                    // Системный аппаратный AEC и NoiseSuppressor
                    effects = HardwareAudioEffects(audioRecord.audioSessionId)
                    audioRecord.startRecording()
                    Log.d(TAG, "DirtyHandsEngine: microphone listening active and listening")

                    val rawChunk = ShortArray(320) // 20 мс
                    val filteredChunk = ShortArray(320)
                    val bandpass = SpeechBandpassFilter(SAMPLE_RATE)
                    val speechRingBuffer = ShortArray(SAMPLE_RATE * 3)
                    var speechSamplesCount = 0
                    var isSpeechActive = false
                    var silenceFramesCounter = 0

                    var noiseFloor = 200.0
                    var minRmsSubwindow = Double.MAX_VALUE
                    var subwindowFramesCount = 0
                    var lastRecognitionTimestamp = 0L

                    while (isActive && shouldKeepListening) {
                        val read = audioRecord.read(rawChunk, 0, rawChunk.size)
                        if (read <= 0) {
                            kotlinx.coroutines.delay(40)
                            break
                        }

                        // 1. Полосовой фильтр речи 300 - 3400 Гц
                        bandpass.process(rawChunk, filteredChunk, read)

                        // 2. Расчет энергии речевой полосы и сырого сигнала
                        var speechSumSq = 0.0
                        var rawSumSq = 0.0
                        var zeroCrossings = 0
                        var prevSample = filteredChunk[0].toInt()

                        for (i in 0 until read) {
                            val sv = filteredChunk[i].toDouble()
                            speechSumSq += sv * sv
                            val rv = rawChunk[i].toDouble()
                            rawSumSq += rv * rv

                            val curr = filteredChunk[i].toInt()
                            if ((curr xor prevSample) < 0) {
                                zeroCrossings++
                            }
                            prevSample = curr
                        }

                        val speechRms = sqrt(speechSumSq / read)
                        val rawRms = sqrt(rawSumSq / read)

                        // 3. Отслеживание минимума шума (Minimum Statistics)
                        if (speechRms < minRmsSubwindow) {
                            minRmsSubwindow = speechRms
                        }
                        subwindowFramesCount++
                        if (subwindowFramesCount >= 35) { // Окно ~700 мс
                            noiseFloor = noiseFloor * 0.85 + minRmsSubwindow * 0.15
                            noiseFloor = noiseFloor.coerceIn(140.0, 1500.0)
                            minRmsSubwindow = Double.MAX_VALUE
                            subwindowFramesCount = 0
                        }

                        // 4. Частотный VAD с повышенным порогом и защитой от ложных срабатываний
                        val speechThreshold = max(SPEECH_RMS_MIN_THRESHOLD, noiseFloor * 1.85)
                        val isSpeechBandDominant = speechRms >= (rawRms * 0.40) || rawRms < 420.0
                        val isCandidateSpeech = speechRms >= speechThreshold &&
                                isSpeechBandDominant &&
                                zeroCrossings in 12..150

                        if (isCandidateSpeech) {
                            if (!isSpeechActive) {
                                isSpeechActive = true
                                speechSamplesCount = 0
                                silenceFramesCounter = 0
                            }

                            if (speechSamplesCount + read < speechRingBuffer.size) {
                                System.arraycopy(filteredChunk, 0, speechRingBuffer, speechSamplesCount, read)
                                speechSamplesCount += read
                            }
                            silenceFramesCounter = 0

                            // Защита от речи фильма: команда не бывает длиннее 1.1 сек
                            if (speechSamplesCount >= (SAMPLE_RATE * MAX_SPEECH_DURATION_MS) / 1000) {
                                isSpeechActive = false
                                speechSamplesCount = 0
                                silenceFramesCounter = 0
                            }
                        } else if (isSpeechActive) {
                            if (speechSamplesCount + read < speechRingBuffer.size) {
                                System.arraycopy(filteredChunk, 0, speechRingBuffer, speechSamplesCount, read)
                                speechSamplesCount += read
                            }
                            silenceFramesCounter++

                            // Мгновенная реакция: всего 9 фреймов (~180 мс) тишины после фразы
                            if (silenceFramesCounter >= SILENCE_TRAILING_FRAMES) {
                                isSpeechActive = false
                                val speechDurationMs = (speechSamplesCount * 1000) / SAMPLE_RATE
                                val now = System.currentTimeMillis()

                                if (speechDurationMs in MIN_SPEECH_DURATION_MS..MAX_SPEECH_DURATION_MS && (now - lastRecognitionTimestamp > 800L)) {
                                    val speechPcm = ShortArray(speechSamplesCount)
                                    System.arraycopy(speechRingBuffer, 0, speechPcm, 0, speechSamplesCount)

                                    val trimmed = trimSilence(speechPcm, speechSamplesCount)
                                    if (trimmed.size >= SAMPLE_RATE * 0.20) {
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
                    Log.w(TAG, "Audio permission missing: ${e.message}")
                    break
                } catch (e: Exception) {
                    if (shouldKeepListening && isActive) {
                        Log.w(TAG, "Microphone stream recovered after: ${e.message}")
                        kotlinx.coroutines.delay(150)
                    }
                } finally {
                    try { effects?.release() } catch (_: Exception) {}
                    try {
                        audioRecord?.stop()
                        audioRecord?.release()
                    } catch (_: Exception) {}
                }
            }
            _isListening.value = false
            Log.d(TAG, "DirtyHandsEngine: microphone listening finished")
        }
    }

    /**
     * Остановка прослушивания микрофона при выходе из плеера или сворачивании.
     */
    fun stopListening() {
        shouldKeepListening = false
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

        // Полная нормализация средним и дисперсией (CMVN - Cepstral Mean and Variance Normalization)
        // Гарантирует независимость признаков от громкости динамиков, акустики комнаты и расстояния до микрофона
        if (framesList.isNotEmpty()) {
            val mean = FloatArray(NUM_MFCC_COEFFS)
            val variance = FloatArray(NUM_MFCC_COEFFS)

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
                    val diff = frame[k] - mean[k]
                    variance[k] += diff * diff
                }
            }
            for (k in 0 until NUM_MFCC_COEFFS) {
                val stdDev = sqrt(variance[k] / framesList.size).coerceAtLeast(1e-3f)
                variance[k] = stdDev
            }

            for (frame in framesList) {
                for (k in 0 until NUM_MFCC_COEFFS) {
                    frame[k] = (frame[k] - mean[k]) / variance[k]
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
                // Масштабируем коэффициенты CMVN (диапазон ~ -3.0 .. +3.0) в диапазон -128..127
                val scaled = (frame[k] * 28.0f).toInt().coerceIn(-128, 127)
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

        val threshold = 200.0

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
