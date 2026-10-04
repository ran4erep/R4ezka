package com.example.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    object Idle : UpdateState
    data class UpdateAvailable(val latestVersion: String, val downloadUrl: String, val changelog: String?) : UpdateState
    data class Downloading(val progress: Float, val currentBytes: Long, val totalBytes: Long) : UpdateState
    data class ReadyToInstall(val apkFile: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

object UpdateManager {
    private const val TAG = "UpdateManager"

    private val client = OkHttpClient.Builder()
        .dns(SafeDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    data class ReleaseEntry(
        val tag: String,
        val changelog: String
    )

    fun isNewerVersion(current: String, latest: String): Boolean {
        val curClean = current.trim().removePrefix("v").removePrefix("V")
        val latClean = latest.trim().removePrefix("v").removePrefix("V")
        if (curClean == latClean) return false

        val curParts = curClean.split(".")
        val latParts = latClean.split(".")

        val maxLength = maxOf(curParts.size, latParts.size)
        for (i in 0 until maxLength) {
            val curVal = curParts.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val latVal = latParts.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            if (latVal > curVal) return true
            if (curVal > latVal) return false
        }
        return false
    }

    fun buildAggregatedChangelog(releases: List<ReleaseEntry>): String? {
        if (releases.isEmpty()) return null

        val nonEmpty = releases.map {
            it.copy(changelog = it.changelog.replace("\r\n", "\n").trim())
        }.filter { it.changelog.isNotBlank() }

        if (nonEmpty.isEmpty()) return null

        // Если только одна доступная версия новее текущей — сохраняем исходный текст без изменений
        if (nonEmpty.size == 1) {
            return nonEmpty.first().changelog
        }

        // Если версий несколько (пользователь пропустил промежуточные релизы) — конкатенируем с заголовками версий
        val sb = StringBuilder(nonEmpty.sumOf { it.changelog.length + 32 })
        for (rel in nonEmpty) {
            if (sb.isNotEmpty()) {
                sb.append("\n\n")
            }
            val tag = rel.tag.trim()
            val cleanTag = tag.removePrefix("v").removePrefix("V")
            val body = rel.changelog

            val firstLine = body.lineSequence().firstOrNull()?.trim().orEmpty()
            val alreadyHasHeader = firstLine.startsWith(tag, ignoreCase = true) ||
                    firstLine.startsWith("v$cleanTag", ignoreCase = true) ||
                    firstLine.startsWith(cleanTag, ignoreCase = true) ||
                    firstLine.contains(tag, ignoreCase = true)

            if (!alreadyHasHeader && tag.isNotBlank()) {
                sb.append(tag).append(":\n")
            }
            sb.append(body)
        }
        return sb.toString().trim().ifBlank { null }
    }

    private fun extractChangelogFromElement(markdownEl: org.jsoup.nodes.Element?): String {
        if (markdownEl == null) return ""
        return try {
            val listItems = markdownEl.select("li")
            if (listItems.isNotEmpty()) {
                listItems.joinToString("\n") { "• " + it.text().trim() }
            } else {
                markdownEl.wholeText().trim()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse changelog element", e)
            ""
        }
    }

    private fun extractChangelogFromHtml(html: String): String {
        return try {
            val doc = Jsoup.parse(html)
            extractChangelogFromElement(doc.selectFirst(".markdown-body"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse changelog HTML", e)
            ""
        }
    }

    suspend fun checkForUpdates(currentVersion: String) {
        withContext(Dispatchers.IO) {
            val current = _updateState.value
            if (current is UpdateState.ReadyToInstall && current.apkFile.exists()) {
                return@withContext
            }
            if (current is UpdateState.Downloading) {
                return@withContext
            }

            var attempt = 0
            val maxAttempts = 3
            while (attempt < maxAttempts) {
                attempt++
                // Стратегия 1: GitHub API (api.github.com/repos/.../releases) для получения всех релизов
                try {
                    val request = Request.Builder()
                        .url("https://api.github.com/repos/ran4erep/R4ezka/releases?per_page=30")
                        .header("User-Agent", "R4ezka-App-Updater")
                        .header("Accept", "application/vnd.github.v3+json")
                        .build()

                    val (bodyString, isSuccess) = client.newCall(request).execute().use { response ->
                        Pair(response.body?.string(), response.isSuccessful)
                    }

                    if (isSuccess && !bodyString.isNullOrBlank()) {
                        val jsonArray = org.json.JSONArray(bodyString)
                        if (jsonArray.length() > 0) {
                            var latestTagName: String? = null
                            var downloadUrl: String? = null
                            val newerReleases = mutableListOf<ReleaseEntry>()

                            for (i in 0 until jsonArray.length()) {
                                val relJson = jsonArray.optJSONObject(i) ?: continue
                                if (relJson.optBoolean("draft", false)) continue

                                val tagName = relJson.optString("tag_name", "").trim()
                                if (tagName.isEmpty()) continue

                                if (isNewerVersion(currentVersion, tagName)) {
                                    if (latestTagName == null) {
                                        latestTagName = tagName
                                        val assetsArray = relJson.optJSONArray("assets")
                                        if (assetsArray != null) {
                                            for (j in 0 until assetsArray.length()) {
                                                val asset = assetsArray.getJSONObject(j)
                                                val assetName = asset.optString("name", "")
                                                if (assetName.endsWith(".apk", ignoreCase = true)) {
                                                    downloadUrl = asset.optString("browser_download_url", "")
                                                    break
                                                }
                                            }
                                        }
                                        if (downloadUrl.isNullOrBlank()) {
                                            downloadUrl = "https://github.com/ran4erep/R4ezka/releases/download/$tagName/r4ezka.apk"
                                        }
                                    }

                                    val body = relJson.optString("body", "")
                                    newerReleases.add(ReleaseEntry(tag = tagName, changelog = body))
                                } else {
                                    if (latestTagName != null) {
                                        break
                                    }
                                }
                            }

                            if (latestTagName != null && downloadUrl != null) {
                                val combinedChangelog = buildAggregatedChangelog(newerReleases)
                                _updateState.value = UpdateState.UpdateAvailable(
                                    latestVersion = latestTagName,
                                    downloadUrl = downloadUrl,
                                    changelog = combinedChangelog
                                )
                                return@withContext
                            } else {
                                // Версия актуальна или новее
                                return@withContext
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "GitHub API update check attempt $attempt error: ${e.message}")
                }

                // Стратегия 2 (Резервная): Прямой запрос к HTML странице релизов https://github.com/ran4erep/R4ezka/releases
                try {
                    val webRequest = Request.Builder()
                        .url("https://github.com/ran4erep/R4ezka/releases")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                        .build()

                    client.newCall(webRequest).execute().use { response ->
                        if (response.isSuccessful) {
                            val html = response.body?.string().orEmpty()
                            val doc = Jsoup.parse(html)
                            val sections = doc.select("section[id^=release-], section[data-release-anchor]")

                            if (sections.isNotEmpty()) {
                                var latestTagName: String? = null
                                var downloadUrl: String? = null
                                val newerReleases = mutableListOf<ReleaseEntry>()

                                for (section in sections) {
                                    var tagName = section.id().removePrefix("release-").trim()
                                    if (tagName.isEmpty()) {
                                        tagName = section.attr("data-release-anchor").removePrefix("release-").trim()
                                    }
                                    if (tagName.isEmpty()) {
                                        tagName = section.selectFirst("a[href*=/releases/tag/]")
                                            ?.attr("href")?.substringAfterLast("/tag/")?.trim().orEmpty()
                                    }
                                    if (tagName.isEmpty()) continue

                                    if (isNewerVersion(currentVersion, tagName)) {
                                        if (latestTagName == null) {
                                            latestTagName = tagName
                                            val apkLink = section.selectFirst("a[href$=\".apk\"]")?.attr("href")
                                            downloadUrl = if (!apkLink.isNullOrBlank()) {
                                                if (apkLink.startsWith("http")) apkLink else "https://github.com$apkLink"
                                            } else {
                                                "https://github.com/ran4erep/R4ezka/releases/download/$tagName/r4ezka.apk"
                                            }
                                        }

                                        val markdownEl = section.selectFirst(".markdown-body")
                                        val changelog = extractChangelogFromElement(markdownEl)
                                        newerReleases.add(ReleaseEntry(tag = tagName, changelog = changelog))
                                    } else {
                                        if (latestTagName != null) {
                                            break
                                        }
                                    }
                                }

                                if (latestTagName != null && downloadUrl != null) {
                                    val combinedChangelog = buildAggregatedChangelog(newerReleases)
                                    _updateState.value = UpdateState.UpdateAvailable(
                                        latestVersion = latestTagName,
                                        downloadUrl = downloadUrl,
                                        changelog = combinedChangelog
                                    )
                                    return@withContext
                                } else {
                                    return@withContext
                                }
                            } else {
                                // Если секции не найдены в HTML — крайний резерв: запрос на /releases/latest
                                val singleFallback = Request.Builder()
                                    .url("https://github.com/ran4erep/R4ezka/releases/latest")
                                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                                    .build()

                                client.newCall(singleFallback).execute().use { fbResponse ->
                                    if (fbResponse.isSuccessful) {
                                        val finalUrl = fbResponse.request.url.toString()
                                        val tagName = finalUrl.substringAfterLast("/tag/").substringAfterLast("/").trim()
                                        if (tagName.isNotEmpty() && tagName != "latest") {
                                            if (isNewerVersion(currentVersion, tagName)) {
                                                val fbHtml = fbResponse.body?.string().orEmpty()
                                                val changelog = extractChangelogFromHtml(fbHtml)
                                                val fbDownloadUrl = "https://github.com/ran4erep/R4ezka/releases/download/$tagName/r4ezka.apk"
                                                _updateState.value = UpdateState.UpdateAvailable(
                                                    latestVersion = tagName,
                                                    downloadUrl = fbDownloadUrl,
                                                    changelog = changelog.ifBlank { null }
                                                )
                                                return@withContext
                                            } else {
                                                return@withContext
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "GitHub Web fallback attempt $attempt error: ${e.message}")
                }

                if (attempt < maxAttempts) {
                    delay(1200)
                }
            }
        }
    }

    suspend fun startDownload(context: Context, downloadUrl: String) {
        withContext(Dispatchers.IO) {
            try {
                _updateState.value = UpdateState.Downloading(0f, 0L, 0L)
                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "R4ezka-App-Updater")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        _updateState.value = UpdateState.Error("Ошибка скачивания: ${response.code}")
                        return@withContext
                    }
                    val body = response.body ?: throw Exception("Пустой ответ от сервера")
                    val totalBytes = body.contentLength()
                    val cacheDir = context.cacheDir
                    val apkFile = File(cacheDir, "app_update.apk")
                    if (apkFile.exists()) {
                        apkFile.delete()
                    }

                    body.byteStream().use { inputStream ->
                        FileOutputStream(apkFile).use { outputStream ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var downloadedBytes = 0L
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else -1f
                                _updateState.value = UpdateState.Downloading(progress, downloadedBytes, totalBytes)
                            }
                        }
                    }
                    _updateState.value = UpdateState.ReadyToInstall(apkFile)
                }
            } catch (e: Exception) {
                _updateState.value = UpdateState.Error("Сбой при загрузке: ${e.message}")
            }
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists()) {
                _updateState.value = UpdateState.Idle
                return
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                setDataAndType(uri, "application/vnd.android.package-archive")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _updateState.value = UpdateState.Error("Ошибка установки: ${e.message}")
        }
    }

    fun dismissUpdate() {
        _updateState.value = UpdateState.Idle
    }
}
