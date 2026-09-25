package com.example.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast

object DownloadHelper {
    private const val TAG = "DownloadHelper"

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

            // Очистка спецсимволов для имени файла
            val cleanTitle = title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
            val cleanSubtitle = subtitle.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
            val cleanQuality = quality.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()

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

            // Извлечение прямого MP4 URL если ссылка содержит манифест HLS
            val finalUrl = when {
                streamUrl.contains(":hls:manifest.m3u8") -> streamUrl.substringBefore(":hls:manifest.m3u8")
                streamUrl.contains(".mp4:") -> streamUrl.substringBefore(":")
                else -> streamUrl
            }

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
}
