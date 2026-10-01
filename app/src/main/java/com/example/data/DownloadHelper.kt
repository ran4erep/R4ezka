package com.example.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat

object DownloadHelper {
    private const val TAG = "DownloadHelper"

    /**
     * Возвращает выделенный каталог оффлайн библиотеки:
     * /Android/data/<package_name>/files/offline
     */
    fun getOfflineDirectory(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "offline")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Возвращает каталог для локальных постеров оффлайн библиотеки
     */
    fun getOfflinePostersDirectory(context: Context): File {
        val dir = File(getOfflineDirectory(context), "posters")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Возвращает выделенный подкаталог конкретного фильма/сериала внутри оффлайн библиотеки:
     * /Android/data/<package_name>/files/offline/<Название фильма или сериала>/
     */
    fun getOfflineMediaFolder(context: Context, title: String, itemId: String): File {
        val cleanTitle = sanitizeFilename(title).ifEmpty { "media_${sanitizeFilename(itemId)}" }
        val dir = File(getOfflineDirectory(context), cleanTitle)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Надежно находит и разрешает реальный видеофайл на диске, включая проверку
     * подпапок, проверку курсора DownloadManager и автоматическое перемещение
     * из корня files/ в выделенную подпапку offline/<itemTitle>/
     */
    fun resolveOfflineVideoFile(entity: OfflineMediaEntity, context: Context): File? {
        val cleanTitle = sanitizeFilename(entity.title).ifEmpty { "media_${sanitizeFilename(entity.itemId)}" }
        val itemDir = getOfflineMediaFolder(context, entity.title, entity.itemId)
        val directFile = File(entity.videoPath)
        val fileName = directFile.name
        val inSubFolder = File(itemDir, fileName)

        // 1. Проверяем файл в правильной выделенной подпапке
        if (inSubFolder.exists() && inSubFolder.length() > 0) {
            return inSubFolder
        }

        // 2. Проверяем прямой сохраненный путь
        if (directFile.exists() && directFile.length() > 0) {
            if (directFile.parentFile?.absolutePath != itemDir.absolutePath) {
                try {
                    if (!itemDir.exists()) itemDir.mkdirs()
                    if (directFile.renameTo(inSubFolder)) {
                        return inSubFolder
                    }
                } catch (_: Exception) {}
            }
            return directFile
        }

        // 3. Проверяем курсор системного DownloadManager (если файл скачивался через него)
        if (entity.downloadId > 0L) {
            try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                if (dm != null) {
                    val cursor = dm.query(DownloadManager.Query().setFilterById(entity.downloadId))
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val uriIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            if (uriIdx >= 0) {
                                val uriStr = c.getString(uriIdx)
                                if (!uriStr.isNullOrBlank()) {
                                    val dmFile = try {
                                        val pUri = Uri.parse(uriStr)
                                        if (pUri.scheme == "file") File(pUri.path ?: "") else File(uriStr)
                                    } catch (_: Exception) { null }

                                    if (dmFile != null && dmFile.exists() && dmFile.length() > 0) {
                                        try {
                                            if (!itemDir.exists()) itemDir.mkdirs()
                                            if (dmFile.renameTo(inSubFolder)) {
                                                return inSubFolder
                                            }
                                        } catch (_: Exception) {}
                                        return dmFile
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val offlineDir = getOfflineDirectory(context)

        // 4. Проверяем в корне offline/
        val inOfflineRoot = File(offlineDir, fileName)
        if (inOfflineRoot.exists() && inOfflineRoot.length() > 0) {
            try {
                if (!itemDir.exists()) itemDir.mkdirs()
                if (inOfflineRoot.renameTo(inSubFolder)) {
                    return inSubFolder
                }
            } catch (_: Exception) {}
            return inOfflineRoot
        }

        // 5. Проверяем в корне external files (куда DownloadManager мог ошибочно сбросить)
        val rootFilesDir = context.getExternalFilesDir(null)
        if (rootFilesDir != null) {
            val inRootFiles = File(rootFilesDir, fileName)
            if (inRootFiles.exists() && inRootFiles.length() > 0) {
                try {
                    if (!itemDir.exists()) itemDir.mkdirs()
                    if (inRootFiles.renameTo(inSubFolder)) {
                        return inSubFolder
                    }
                } catch (_: Exception) {}
                return inRootFiles
            }

            // Ищем любые подходящие mp4 файлы по паттерну в rootFilesDir
            val epPattern = if (entity.season > 0) "s${entity.season}e${entity.episode}" else ""
            val candidate = rootFilesDir.listFiles()?.firstOrNull { f ->
                f.isFile && f.extension.equals("mp4", ignoreCase = true) &&
                (if (epPattern.isNotEmpty()) f.name.contains(epPattern, ignoreCase = true) else f.name.contains(cleanTitle, ignoreCase = true))
            }
            if (candidate != null && candidate.length() > 0) {
                try {
                    if (!itemDir.exists()) itemDir.mkdirs()
                    if (candidate.renameTo(inSubFolder)) {
                        return inSubFolder
                    }
                } catch (_: Exception) {}
                return candidate
            }
        }

        return null
    }

    /**
     * Обычное скачивание в общую публичную папку Загрузки (Download)
     */
    fun downloadStream(
        context: Context,
        title: String,
        subtitle: String = "",
        quality: String,
        translatorName: String = "",
        streamUrl: String
    ) {
        if (streamUrl.isBlank()) {
            Toast.makeText(context, "Ссылка на поток не найдена", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (downloadManager == null) {
                Toast.makeText(context, "DownloadManager недоступен", Toast.LENGTH_SHORT).show()
                return
            }

            val cleanTitle = sanitizeFilename(title)
            val cleanSubtitle = sanitizeFilename(subtitle)
            val cleanQuality = sanitizeFilename(quality)

            val fileName = buildString {
                append(cleanTitle)
                if (cleanSubtitle.isNotEmpty()) {
                    append(" - ").append(cleanSubtitle)
                }
                if (cleanQuality.isNotEmpty()) {
                    append(" (").append(cleanQuality).append(")")
                }
                append(".mp4")
            }

            val finalUrl = extractDirectMp4(streamUrl)

            val request = DownloadManager.Request(Uri.parse(finalUrl)).apply {
                setTitle(fileName)
                val desc = if (translatorName.isNotEmpty()) "Озвучка: $translatorName" else "Rezka Cinema"
                setDescription(desc)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                addRequestHeader("User-Agent", RezkaService.USER_AGENT)
                addRequestHeader("Referer", RezkaService.currentBaseUrl)
            }

            downloadManager.enqueue(request)
            Toast.makeText(context, "Загрузка начата", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при запуске загрузки: ${e.message}", e)
            Toast.makeText(context, "Ошибка при запуске загрузки", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Скачивание файла непосредственно в нашу внутреннюю оффлайн библиотеку
     * /Android/data/<package_name>/files/offline с сохранением всех метаданных в Room
     */
    fun downloadToOfflineLibrary(
        context: Context,
        detail: RezkaDetail,
        seasonId: Int,
        episodeId: String,
        translator: Translator,
        quality: String,
        streamUrl: String,
        repository: RezkaRepository
    ) {
        if (streamUrl.isBlank()) {
            Toast.makeText(context, "Ссылка на поток не найдена", Toast.LENGTH_SHORT).show()
            return
        }

        val isSeries = detail.type == RezkaType.SERIES
        val offlineDir = getOfflineDirectory(context)
        val cleanTitle = sanitizeFilename(detail.title)
        val cleanQuality = sanitizeFilename(quality)
        val itemDir = getOfflineMediaFolder(context, detail.title, detail.id)

        val uniqueEntityId = if (isSeries) {
            "${detail.id}_${seasonId}_${episodeId}_${cleanQuality}"
        } else {
            "${detail.id}_${cleanQuality}"
        }

        val fileName = if (isSeries) {
            "${cleanTitle}_s${seasonId}e${episodeId}_(${cleanQuality}).mp4"
        } else {
            "${cleanTitle}_(${cleanQuality}).mp4"
        }

        val targetFile = File(itemDir, fileName)
        val finalUrl = extractDirectMp4(streamUrl)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Кэширование постера локально в папку релиза для гарантированного оффлайн рендеринга
                var localPosterPath = ""
                if (detail.imageUrl.isNotBlank()) {
                    val posterFile = File(itemDir, "poster.jpg")
                    if (posterFile.exists() && posterFile.length() > 0) {
                        localPosterPath = posterFile.absolutePath
                    } else {
                        try {
                            val conn = URL(detail.imageUrl).openConnection() as HttpURLConnection
                            conn.setRequestProperty("User-Agent", RezkaService.USER_AGENT)
                            conn.setRequestProperty("Referer", RezkaService.currentBaseUrl)
                            conn.connectTimeout = 8000
                            conn.readTimeout = 8000
                            conn.doInput = true
                            conn.connect()
                            if (conn.responseCode in 200..299) {
                                conn.inputStream.use { input ->
                                    FileOutputStream(posterFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                if (posterFile.exists() && posterFile.length() > 0) {
                                    localPosterPath = posterFile.absolutePath
                                }
                            }
                        } catch (pe: Exception) {
                            Log.w(TAG, "Не удалось сохранить постер локально: ${pe.message}")
                        }
                    }
                }

                // 2. Запуск фоновой загрузки через системный DownloadManager в выделенную папку
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                var downloadId = -1L

                if (downloadManager != null) {
                    val mediaFolderName = itemDir.name
                    val relativeSubPath = "offline/$mediaFolderName/$fileName"
                    if (!itemDir.exists()) {
                        itemDir.mkdirs()
                    }

                    val request = DownloadManager.Request(Uri.parse(finalUrl)).apply {
                        setTitle(if (isSeries) "${detail.title} (С$seasonId Э$episodeId)" else detail.title)
                        setDescription("Оффлайн библиотека • ${translator.name} ($quality)")
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                        addRequestHeader("User-Agent", RezkaService.USER_AGENT)
                        addRequestHeader("Referer", RezkaService.currentBaseUrl)
                        try {
                            setDestinationInExternalFilesDir(context, null, relativeSubPath)
                        } catch (_: Exception) {
                            setDestinationUri(Uri.fromFile(targetFile))
                        }
                    }
                    downloadId = downloadManager.enqueue(request)
                }

                // 3. Сохранение полных метаданных в JSON и Room БД
                val detailJson = RezkaDetailJsonEngine.toJson(detail)
                try {
                    val itemMetaFile = File(itemDir, "metadata.json")
                    itemMetaFile.writeText(detailJson)
                    val globalMetaFile = File(offlineDir, "metadata_${sanitizeFilename(detail.id)}.json")
                    globalMetaFile.writeText(detailJson)
                } catch (me: Exception) {
                    Log.w(TAG, "Не удалось сохранить JSON метаданных на диск: ${me.message}")
                }

                val entity = OfflineMediaEntity(
                    id = uniqueEntityId,
                    itemId = detail.id,
                    title = detail.title,
                    subtitle = if (isSeries) "Сезон $seasonId, Серия $episodeId" else detail.year,
                    imageUrl = detail.imageUrl,
                    localPosterPath = localPosterPath,
                    videoPath = targetFile.absolutePath,
                    type = detail.type.name,
                    genres = detail.genres.joinToString(", "),
                    year = detail.year,
                    country = detail.country,
                    season = if (isSeries) seasonId else 0,
                    episode = if (isSeries) episodeId else "",
                    translatorId = translator.id,
                    translatorName = translator.name,
                    quality = quality,
                    fileSizeBytes = if (targetFile.exists()) targetFile.length() else 0L,
                    downloadId = downloadId,
                    downloadStatus = 0,
                    createdAt = System.currentTimeMillis(),
                    description = detail.description,
                    detailJson = detailJson
                )

                repository.insertOfflineMedia(entity)
                repository.updateOfflineDetailJson(detail.id, detailJson)

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Добавлено в оффлайн библиотеку", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка сохранения в оффлайн библиотеку", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Удаляет файл оффлайн медиа и его запись в Room
     */
    suspend fun deleteOfflineMedia(context: Context, entity: OfflineMediaEntity, repository: RezkaRepository) {
        withContext(Dispatchers.IO) {
            try {
                val resolvedFile = resolveOfflineVideoFile(entity, context)
                if (resolvedFile != null && resolvedFile.exists()) {
                    resolvedFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка удаления видеофайла: ${e.message}")
            }

            repository.deleteOfflineMediaById(entity.id)

            // Проверяем, остались ли другие серии этого фильма/сериала
            val remaining = repository.getOfflineMediaByItemId(entity.itemId)
            if (remaining.isEmpty()) {
                try {
                    val itemFolder = getOfflineMediaFolder(context, entity.title, entity.itemId)
                    if (itemFolder.exists()) {
                        itemFolder.deleteRecursively()
                    }
                } catch (_: Exception) {}
                try {
                    val offlineDir = getOfflineDirectory(context)
                    val metaFile = File(offlineDir, "metadata_${sanitizeFilename(entity.itemId)}.json")
                    if (metaFile.exists()) {
                        metaFile.delete()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Полная очистка оффлайн библиотеки
     */
    suspend fun clearAllOffline(context: Context, repository: RezkaRepository) {
        withContext(Dispatchers.IO) {
            try {
                val dir = getOfflineDirectory(context)
                if (dir.exists()) {
                    dir.deleteRecursively()
                    dir.mkdirs()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка очистки каталога: ${e.message}")
            }
            repository.clearAllOfflineMedia()
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 Б"
        val df = DecimalFormat("#,##0.#")
        return when {
            bytes >= 1024 * 1024 * 1024 -> "${df.format(bytes.toDouble() / (1024 * 1024 * 1024))} ГБ"
            bytes >= 1024 * 1024 -> "${df.format(bytes.toDouble() / (1024 * 1024))} МБ"
            bytes >= 1024 -> "${df.format(bytes.toDouble() / 1024)} КБ"
            else -> "$bytes Б"
        }
    }

    fun sanitizeFilename(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    }

    private fun extractDirectMp4(streamUrl: String): String {
        return when {
            streamUrl.contains(":hls:manifest.m3u8") -> streamUrl.substringBefore(":hls:manifest.m3u8")
            streamUrl.contains(".mp4:") -> streamUrl.substringBefore(":")
            else -> streamUrl
        }
    }
}
