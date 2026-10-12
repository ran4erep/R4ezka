package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Высокопроизводительный движок логирования проверок серий в текстовый файл.
 * 
 * Оптимизации:
 * 1. Выполнение I/O операций асинхронно на Dispatchers.IO с минимальным потреблением CPU и памяти.
 * 2. Хранение исключительно в изолированном хранилище приложения без засорения системной папки Загрузки (Downloads).
 * 3. Защита от разрастания файла (ротация/стриминг буфера до 512 КБ).
 * 4. Безопасная передача через системный FileProvider (Intent.ACTION_SEND) без создания мусорных копий.
 */
object SeriesUpdateLogger {
    private const val TAG = "SeriesUpdateLogger"
    private const val LOG_FILE_NAME = "series_checks_log.txt"
    private const val PUBLIC_LOG_FILE_NAME = "rezka_series_checks_log.txt"
    private const val MAX_LOG_FILE_SIZE = 512 * 1024 // 512 KB макс. размер

    private val fileMutex = Mutex()
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    private fun getTimestamp(): String {
        return dateFormat.get()?.format(Date()) ?: System.currentTimeMillis().toString()
    }

    /**
     * Получает основной текстовый файл логов в изолированном хранилище приложения.
     * Ни при каких обстоятельствах не размещается в публичной папке Загрузки.
     */
    fun getLogFile(context: Context): File {
        val internalFile = File(context.filesDir, LOG_FILE_NAME)
        val extDir = context.getExternalFilesDir(null)
        val extFile = extDir?.let { File(it, LOG_FILE_NAME) }
        return when {
            internalFile.exists() -> internalFile
            extFile != null && extFile.exists() -> extFile
            else -> internalFile
        }
    }

    /**
     * Инициализирует файл логов в изолированном каталоге и очищает старый лог из Загрузок,
     * если он был создан предыдущими версиями.
     */
    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            try {
                // Удаляем остаточный файл из папки Загрузки, чтобы не засорять устройство
                cleanupLegacyDownloadsLog(context)

                val file = getLogFile(context)
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    val header = buildString {
                        append("====================================================\n")
                        append("🎬 HDRezka Client — Журнал фоновых проверок серий\n")
                        append("Файл создан: ${getTimestamp()}\n")
                        append("Путь к файлу: ${file.absolutePath}\n")
                        append("====================================================\n\n")
                    }
                    file.writeText(header, Charsets.UTF_8)
                    Log.i(TAG, "Файл логов инициализирован в изолированном хранилище: ${file.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка инициализации файла логов: ${e.message}", e)
            }
        }
    }

    private fun checkAndAutoClearDaily(file: File) {
        if (!file.exists()) return
        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastModDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(file.lastModified()))
        if (todayDate != lastModDate) {
            val header = buildString {
                append("====================================================\n")
                append("🎬 HDRezka Client — Журнал работы (Авто-очистка за прошлый день: $lastModDate)\n")
                append("Файл обновлён: ${getTimestamp()}\n")
                append("====================================================\n\n")
            }
            file.writeText(header, Charsets.UTF_8)
            Log.i(TAG, "Лог автоматически очищен при наступлении нового дня ($todayDate)")
        }
    }

    /**
     * Записывает строку в текстовый лог приложения без создания копий в Загрузках
     */
    suspend fun appendLog(context: Context, message: String) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            try {
                val file = getLogFile(context)
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    file.writeText("=== HDRezka Log ===\nCreated: ${getTimestamp()}\n\n", Charsets.UTF_8)
                } else {
                    checkAndAutoClearDaily(file)
                }

                // Ротация: если размер файла превысил лимит, обрезаем старые строки
                if (file.length() > MAX_LOG_FILE_SIZE) {
                    trimLogFile(file)
                }

                val entry = "[${getTimestamp()}] $message\n"
                file.appendText(entry, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка записи в лог-файл: ${e.message}", e)
            }
        }
    }

    /**
     * Подробная запись ошибки работы видео-парсера или получения ссылок на видеопоток
     */
    suspend fun logParserError(
        context: Context? = null,
        title: String,
        itemId: String,
        translatorId: String = "",
        season: Int = 0,
        episode: String = "",
        endpointUrl: String,
        requestParams: String = "",
        httpCode: Int? = null,
        responseBody: String? = null,
        errorMessage: String
    ) = withContext(Dispatchers.IO) {
        val ctx = context ?: try { com.example.RezkaApplication.instance } catch (_: Exception) { null }
        if (ctx == null) return@withContext

        val logEntry = buildString {
            append("⚠️ ОШИБКА ПАРСИНГА ВИДЕОПОТОКА: \"$title\" (ID: $itemId)\n")
            val transText = if (translatorId.isNotBlank() && translatorId != "0") "ID озвучки $translatorId" else "По умолчанию"
            val seText = if (season > 0) " | Сезон $season, Серия $episode" else ""
            append("   ├─ Параметры: $transText$seText\n")
            append("   ├─ URL запроса: $endpointUrl\n")
            if (requestParams.isNotEmpty()) {
                append("   ├─ Параметры POST: $requestParams\n")
            }
            append("   ├─ Ответ сервера: HTTP ${httpCode ?: "Сбой сети / Неизвестно"}\n")
            if (!responseBody.isNullOrBlank()) {
                val cleanBody = responseBody.trim()
                val snippet = if (cleanBody.length > 800) cleanBody.take(800) + "... [обрезано ${cleanBody.length} символов]" else cleanBody
                append("   ├─ Тело ответа HDRezka: $snippet\n")
            }
            append("   └─ ❌ Причина ошибки: $errorMessage")
        }
        appendLog(ctx, logEntry)
    }

    /**
     * Подробная запись ошибки воспроизведения в видеоплеере ExoPlayer
     */
    suspend fun logPlaybackError(
        context: Context? = null,
        title: String,
        subtitle: String = "",
        streamUrl: String,
        errorCodeName: String,
        errorCode: Int,
        errorMessage: String,
        fallbackInfo: String? = null
    ) = withContext(Dispatchers.IO) {
        val ctx = context ?: try { com.example.RezkaApplication.instance } catch (_: Exception) { null }
        if (ctx == null) return@withContext

        val logEntry = buildString {
            val subText = if (subtitle.isNotEmpty()) " - $subtitle" else ""
            append("❌ ОШИБКА ВОСПРОИЗВЕДЕНИЯ ВИДЕО (ExoPlayer): \"$title\"$subText\n")
            append("   ├─ Ссылка на поток: $streamUrl\n")
            append("   ├─ Код ошибки: $errorCodeName (Код $errorCode)\n")
            append("   ├─ Сообщение ошибки: $errorMessage\n")
            if (!fallbackInfo.isNullOrBlank()) {
                append("   └─ Действие: $fallbackInfo")
            } else {
                append("   └─ Воспроизведение остановлено из-за ошибки сервера или сети.")
            }
        }
        appendLog(ctx, logEntry)
    }

    /**
     * Начинает сессию проверки подписок
     */
    suspend fun logCheckStarted(context: Context, totalSubscriptionsCount: Int) {
        val msg = buildString {
            append("🔄 СОВЕРШАЮ ПРОВЕРКУ ПОДПИСОК НА НОВЫЕ СЕРИИ\n")
            append("   ├─ Время запуска: ${getTimestamp()}\n")
            append("   └─ Всего отслеживаемых тайтлов в базе: $totalSubscriptionsCount")
        }
        appendLog(context, msg)
    }

    /**
     * Записывает подробный отчет о проверке конкретного сериала/фильма
     */
    suspend fun logItemChecked(
        context: Context,
        title: String,
        type: String,
        translatorId: String,
        currentSeason: Int,
        currentEpisode: Int,
        lastEpName: String,
        expectedSeason: Int,
        expectedEpisode: Int,
        foundSeason: Int,
        foundEpisode: Int,
        foundEpName: String,
        isNewFound: Boolean,
        errorMessage: String? = null
    ) {
        val isMovie = type.equals("MOVIE", ignoreCase = true) || (currentSeason == 0 && currentEpisode == 0 && lastEpName.contains("фильм", ignoreCase = true))
        
        val logEntry = buildString {
            if (isMovie) {
                append("🎬 ПРОВЕРИЛ ФИЛЬМ: \"$title\"\n")
            } else {
                append("📺 ПРОВЕРИЛ СЕРИАЛ: \"$title\"\n")
            }
            
            val transText = if (translatorId.isNotBlank() && translatorId != "0") "ID озвучки $translatorId" else "По умолчанию / Основная"
            append("   ├─ Озвучка: $transText\n")

            if (isMovie) {
                val currentStatus = if (currentSeason > 0 || currentEpisode > 0) "Фильм был выпущен" else "В ожидании выхода"
                append("   ├─ Текущий статус: $currentStatus\n")
                append("   ├─ Ожидается: Релиз видеофайла на сайте\n")
            } else {
                val curEpStr = if (currentSeason == 0 && currentEpisode == 0) "В ожидании первого сезона" else "$currentSeason сезон, $currentEpisode серия ($lastEpName)"
                append("   ├─ Текущая серия в базе: $curEpStr\n")
                
                val expEpStr = if (expectedSeason == 0 && expectedEpisode == 0) "1 сезон, 1 серия" else "$expectedSeason сезон, $expectedEpisode серия"
                append("   ├─ Ожидается серия: $expEpStr\n")
            }

            if (!errorMessage.isNullOrBlank()) {
                append("   └─ ❌ Ошибка проверки: $errorMessage")
            } else if (isNewFound) {
                if (isMovie) {
                    append("   └─ 🔥 РЕЛИЗ СОСТОЯЛСЯ! Фильм стал доступен для просмотра!")
                } else {
                    append("   └─ 🔥 НАЙДЕНА НОВАЯ СЕРИЯ: $foundSeason сезон, $foundEpisode серия (\"$foundEpName\")!")
                }
            } else {
                if (isMovie) {
                    append("   └─ ℹ️ Фильм пока не вышел (изменений нет)")
                } else {
                    val foundStr = if (foundSeason == 0 && foundEpisode == 0) "Серии пока отсутствуют" else "$foundSeason сезон, $foundEpisode серия"
                    append("   └─ ℹ️ Новых серий нет (На сайте: $foundStr)")
                }
            }
        }
        appendLog(context, logEntry)
    }

    /**
     * Завершает сессию проверки подписок
     */
    suspend fun logCheckFinished(context: Context, updatedCount: Int) {
        val msg = buildString {
            append("✅ ПРОВЕРКА ПОДПИСОК ЗАВЕРШЕНА\n")
            append("   ├─ Время окончания: ${getTimestamp()}\n")
            append("   └─ Найдено обновлений / новых серий: $updatedCount\n")
            append("----------------------------------------------------")
        }
        appendLog(context, msg)
    }

    /**
     * Возвращает полный текст файла лога
     */
    suspend fun readLogContent(context: Context): String = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            try {
                val file = getLogFile(context)
                if (file.exists()) {
                    return@withContext file.readText(Charsets.UTF_8)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка чтения лога: ${e.message}", e)
            }
            return@withContext "Лог-файл пока пуст или не создан."
        }
    }

    /**
     * Очищает содержимое файла логов в изолированном хранилище
     */
    suspend fun clearLog(context: Context) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            try {
                val file = getLogFile(context)
                val header = buildString {
                    append("====================================================\n")
                    append("🎬 HDRezka Client — Журнал фоновых проверок серий\n")
                    append("Журнал очищен: ${getTimestamp()}\n")
                    append("====================================================\n\n")
                }
                file.writeText(header, Charsets.UTF_8)
                // Удаляем остаточный файл из папки Загрузки, если он там присутствовал
                cleanupLegacyDownloadsLog(context)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка очистки лога: ${e.message}", e)
            }
        }
    }

    /**
     * Безопасный экспорт логов через системный диалог передачи файлов,
     * исключающий засорение папки Загрузки (Downloads).
     */
    suspend fun exportToPublicDownloads(context: Context): String = withContext(Dispatchers.Main) {
        shareLogFile(context)
        "Лог открыт в диалоге отправки (без сохранения в папку Загрузки)"
    }

    /**
     * Поделиться файлом лога через системный диалог Intent.ACTION_SEND
     * с использованием безопасного FileProvider
     */
    fun shareLogFile(context: Context) {
        try {
            val file = getLogFile(context)
            if (!file.exists()) {
                return
            }

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Лог проверок серий HDRezka")
                putExtra(Intent.EXTRA_TEXT, "Текстовый лог фоновых проверок новых серий сериалов")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Поделиться логом проверок")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отправки лог-файла: ${e.message}", e)
        }
    }

    /**
     * Удаляет устаревший лог-файл из публичной папки Загрузки (Downloads),
     * если он был создан предыдущими версиями приложения.
     * Запускается асинхронно с нулевым влиянием на UI и минимальной нагрузкой на CPU.
     */
    private fun cleanupLegacyDownloadsLog(context: Context) {
        try {
            // 1. Удаление файла напрямую из файловой системы, если он существует
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir != null && downloadsDir.exists()) {
                val legacyFile = File(downloadsDir, PUBLIC_LOG_FILE_NAME)
                if (legacyFile.exists()) {
                    val deleted = legacyFile.delete()
                    Log.i(TAG, "Очистка папки Загрузки: файл $PUBLIC_LOG_FILE_NAME удален = $deleted")
                }
            }

            // 2. На Android Q+ удаляем запись через ContentResolver MediaStore
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    context.contentResolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                        arrayOf(PUBLIC_LOG_FILE_NAME)
                    )
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось выполнить очистку устаревшего лога из Downloads: ${e.message}")
        }
    }

    /**
     * Высокопроизводительная обрезка разросшегося файла логов
     * с использованием потоковых буферов без избыточных аллокаций памяти.
     */
    private fun trimLogFile(file: File) {
        try {
            val lines = file.bufferedReader(Charsets.UTF_8).useLines { it.toList() }
            if (lines.size > 800) {
                val keptLines = lines.takeLast(600)
                val newHeader = "=== Log Truncated at ${getTimestamp()} (kept last ${keptLines.size} lines) ===\n\n"
                file.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(newHeader)
                    for (line in keptLines) {
                        writer.write(line)
                        writer.newLine()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось обрезать разросшийся лог: ${e.message}")
        }
    }
}
