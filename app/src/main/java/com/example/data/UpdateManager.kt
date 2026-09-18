package com.example.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
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

    suspend fun checkForUpdates(currentVersion: String) {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/ran4erep/R4ezka/releases/latest")
                    .header("User-Agent", "R4ezka-App-Updater")
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // Silent fail or idle, we don't block user if GitHub rate limit exceeded or offline
                        _updateState.value = UpdateState.Idle
                        return@withContext
                    }
                    val bodyString = response.body?.string() ?: return@withContext
                    val json = JSONObject(bodyString)
                    val tagName = json.getString("tag_name")
                    val changelog = json.optString("body", "")

                    if (isNewerVersion(currentVersion, tagName)) {
                        val assetsArray = json.optJSONArray("assets")
                        var downloadUrl: String? = null
                        if (assetsArray != null) {
                            for (i in 0 until assetsArray.length()) {
                                val asset = assetsArray.getJSONObject(i)
                                val assetName = asset.getString("name")
                                if (assetName.endsWith(".apk")) {
                                    downloadUrl = asset.getString("browser_download_url")
                                    break
                                }
                            }
                        }
                        if (downloadUrl != null) {
                            _updateState.value = UpdateState.UpdateAvailable(
                                latestVersion = tagName,
                                downloadUrl = downloadUrl,
                                changelog = changelog
                            )
                        } else {
                            _updateState.value = UpdateState.Idle
                        }
                    } else {
                        _updateState.value = UpdateState.Idle
                    }
                }
            } catch (e: Exception) {
                // Handle silently or logs to avoid crashing on poor connection
                _updateState.value = UpdateState.Idle
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
