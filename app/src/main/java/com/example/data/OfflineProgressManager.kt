package com.example.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class OfflineEpisodeProgress(
    val season: Int,
    val episode: String,
    val progressMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

data class OfflineItemProgress(
    val itemId: String,
    val lastSeason: Int = 0,
    val lastEpisode: String = "",
    val lastTranslatorId: String = "",
    val lastTranslatorName: String = "",
    val progressMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
    val episodes: Map<String, OfflineEpisodeProgress> = emptyMap()
)

/**
 * Локальный менеджер сохранения прогресса оффлайн просмотра.
 * Сохраняет позицию воспроизведения, сезон и серию в отдельный файл progress.json
 * в папке скачанного элемента на устройстве, полностью изолированно от общей онлайн-истории.
 */
object OfflineProgressManager {
    private const val TAG = "OfflineProgressManager"
    private const val PROGRESS_FILE_NAME = "progress.json"

    // Быстрый кэш в оперативной памяти (O(1)) для минимизации нагрузки на CPU и диск
    private val memoryCache = ConcurrentHashMap<String, OfflineItemProgress>()

    private fun getProgressFile(context: Context, itemId: String, title: String): File {
        val folder = DownloadHelper.getOfflineMediaFolder(context, title, itemId)
        return File(folder, PROGRESS_FILE_NAME)
    }

    private fun getFallbackProgressFile(context: Context, itemId: String): File {
        val cleanId = DownloadHelper.sanitizeFilename(itemId)
        return File(DownloadHelper.getOfflineDirectory(context), "progress_${cleanId}.json")
    }

    fun getOfflineProgress(context: Context, itemId: String, title: String = ""): OfflineItemProgress? {
        val cacheKey = itemId.ifEmpty { title }
        memoryCache[cacheKey]?.let { return it }

        // Ищем progress.json в папке элемента
        val primaryFile = getProgressFile(context, itemId, title)
        val fileToRead = if (primaryFile.exists()) {
            primaryFile
        } else {
            val fallback = getFallbackProgressFile(context, itemId)
            if (fallback.exists()) fallback else null
        } ?: return null

        return try {
            val content = fileToRead.readText()
            if (content.isBlank()) return null
            val json = JSONObject(content)

            val epsMap = mutableMapOf<String, OfflineEpisodeProgress>()
            val epsJson = json.optJSONObject("episodes")
            if (epsJson != null) {
                val keys = epsJson.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val epObj = epsJson.optJSONObject(k)
                    if (epObj != null) {
                        epsMap[k] = OfflineEpisodeProgress(
                            season = epObj.optInt("season", 0),
                            episode = epObj.optString("episode", ""),
                            progressMs = epObj.optLong("progressMs", 0L),
                            durationMs = epObj.optLong("durationMs", 0L),
                            updatedAt = epObj.optLong("updatedAt", 0L)
                        )
                    }
                }
            }

            val result = OfflineItemProgress(
                itemId = json.optString("itemId", itemId),
                lastSeason = json.optInt("lastSeason", 0),
                lastEpisode = json.optString("lastEpisode", ""),
                lastTranslatorId = json.optString("lastTranslatorId", ""),
                lastTranslatorName = json.optString("lastTranslatorName", ""),
                progressMs = json.optLong("progressMs", 0L),
                durationMs = json.optLong("durationMs", 0L),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                episodes = epsMap
            )
            memoryCache[cacheKey] = result
            result
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения оффлайн прогресса: ${e.message}")
            null
        }
    }

    fun getOfflineEpisodeProgress(
        context: Context,
        itemId: String,
        season: Int,
        episode: String,
        title: String = ""
    ): Long {
        val prog = getOfflineProgress(context, itemId, title) ?: return 0L
        if (season > 0) {
            val epKey = "${season}_${episode}"
            val epProg = prog.episodes[epKey]
            if (epProg != null) {
                if (epProg.durationMs > 0 && epProg.progressMs >= epProg.durationMs - 5000L) {
                    return 0L // Если досмотрено почти до конца — с начала
                }
                return epProg.progressMs
            }
        }
        if (prog.durationMs > 0 && prog.progressMs >= prog.durationMs - 5000L) {
            return 0L
        }
        return prog.progressMs
    }

    fun saveOfflineProgress(
        context: Context,
        itemId: String,
        title: String,
        season: Int,
        episode: String,
        translatorId: String,
        translatorName: String,
        progressMs: Long,
        durationMs: Long
    ) {
        val cacheKey = itemId.ifEmpty { title }
        val current = memoryCache[cacheKey] ?: getOfflineProgress(context, itemId, title)

        val updatedEpisodes = current?.episodes?.toMutableMap() ?: mutableMapOf()
        if (season > 0 && episode.isNotEmpty()) {
            val epKey = "${season}_${episode}"
            updatedEpisodes[epKey] = OfflineEpisodeProgress(
                season = season,
                episode = episode,
                progressMs = progressMs,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis()
            )
        }

        val updated = OfflineItemProgress(
            itemId = itemId,
            lastSeason = if (season > 0) season else current?.lastSeason ?: 0,
            lastEpisode = if (episode.isNotEmpty()) episode else current?.lastEpisode ?: "",
            lastTranslatorId = translatorId.ifEmpty { current?.lastTranslatorId ?: "" },
            lastTranslatorName = translatorName.ifEmpty { current?.lastTranslatorName ?: "" },
            progressMs = progressMs,
            durationMs = durationMs,
            updatedAt = System.currentTimeMillis(),
            episodes = updatedEpisodes
        )
        memoryCache[cacheKey] = updated

        // Фоновая запись на диск в отдельный progress.json
        try {
            val targetFile = getProgressFile(context, itemId, title)
            val parent = targetFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }

            val json = JSONObject().apply {
                put("itemId", updated.itemId)
                put("lastSeason", updated.lastSeason)
                put("lastEpisode", updated.lastEpisode)
                put("lastTranslatorId", updated.lastTranslatorId)
                put("lastTranslatorName", updated.lastTranslatorName)
                put("progressMs", updated.progressMs)
                put("durationMs", updated.durationMs)
                put("updatedAt", updated.updatedAt)

                val epsJson = JSONObject()
                updated.episodes.forEach { (k, v) ->
                    epsJson.put(k, JSONObject().apply {
                        put("season", v.season)
                        put("episode", v.episode)
                        put("progressMs", v.progressMs)
                        put("durationMs", v.durationMs)
                        put("updatedAt", v.updatedAt)
                    })
                }
                put("episodes", epsJson)
            }

            targetFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения оффлайн прогресса в файл: ${e.message}")
        }
    }
}
