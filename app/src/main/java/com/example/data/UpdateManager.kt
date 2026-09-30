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

    fun isNewerVersion(current: String, latest: String): Boolean {
        val curClean = current.trim().removePrefix("v").removePrefix("V")
        val latClean = latest.trim().removePrefix("v").removePrefix("V")
        if (curClean == latClean) return false

        val curParts = curClean.split(".")
        val latParts = latClean.split(".")

        val maxLength = maxOf(curParts.size, latParts.size)
        for (i in 0 until maxLength) {
            val curVal = curParts.getOrNull(i)?.toIntOrNull() ?: 0
            val latVal = latParts.getOrNull(i)?.toIntOrNull() ?: 0
            if (latVal > curVal) return true
            if (curVal > latVal) return false
        }
        return false
    }

    private fun extractChangelogFromHtml(html: String): String {
        return try {
            val doc = Jsoup.parse(html)
            val markdownEl = doc.selectFirst(".markdown-body")
            if (markdownEl != null) {
                val listItems = markdownEl.select("li")
                if (listItems.isNotEmpty()) {
                    listItems.joinToString("\n") { "• " + it.text().trim() }
                } else {
                    markdownEl.wholeText().trim()
                }
            } else {
                ""
            }
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
                // Стратегия 1: GitHub API (api.github.com)
                try {
                    val request = Request.Builder()
                        .url("https://api.github.com/repos/ran4erep/R4ezka/releases/latest")
                        .header("User-Agent", "R4ezka-App-Updater")
                        .header("Accept", "application/vnd.github.v3+json")
                        .build()

                    val (bodyString, isSuccess) = client.newCall(request).execute().use { response ->
                        Pair(response.body?.string(), response.isSuccessful)
                    }

                    if (isSuccess && !bodyString.isNullOrBlank()) {
                        val json = JSONObject(bodyString)
                        val tagName = json.optString("tag_name", "").trim()
                        val changelog = json.optString("body", "")

                        if (tagName.isNotEmpty()) {
                            if (isNewerVersion(currentVersion, tagName)) {
                                val assetsArray = json.optJSONArray("assets")
                                var downloadUrl: String? = null
                                if (assetsArray != null) {
                                    for (i in 0 until assetsArray.length()) {
                                        val asset = assetsArray.getJSONObject(i)
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
                                _updateState.value = UpdateState.UpdateAvailable(
                                    latestVersion = tagName,
                                    downloadUrl = downloadUrl,
                                    changelog = changelog
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

                // Стратегия 2 (Резервная): Прямой переход по ссылке /releases/latest без ограничений API
                try {
                    val webRequest = Request.Builder()
                        .url("https://github.com/ran4erep/R4ezka/releases/latest")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                        .build()

                    client.newCall(webRequest).execute().use { response ->
                        if (response.isSuccessful) {
                            val finalUrl = response.request.url.toString()
                            val tagName = finalUrl.substringAfterLast("/tag/").substringAfterLast("/").trim()
                            if (tagName.isNotEmpty() && tagName != "latest") {
                                if (isNewerVersion(currentVersion, tagName)) {
                                    val html = response.body?.string().orEmpty()
                                    val changelog = extractChangelogFromHtml(html)
                                    val downloadUrl = "https://github.com/ran4erep/R4ezka/releases/download/$tagName/r4ezka.apk"
                                    _updateState.value = UpdateState.UpdateAvailable(
                                        latestVersion = tagName,
                                        downloadUrl = downloadUrl,
                                        changelog = changelog.ifBlank { null }
                                    )
                                    return@withContext
                                } else {
                                    return@withContext
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
