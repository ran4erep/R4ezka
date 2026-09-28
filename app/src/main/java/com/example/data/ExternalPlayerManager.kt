package com.example.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/**
 * Модель установленного в системе внешнего видеоплеера.
 */
data class ExternalPlayerApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable? = null
)

/**
 * Высокопроизводительный менеджер обнаружения и запуска внешних видеоплееров.
 * Обеспечивает динамический поиск без хардкода, кэширование в памяти
 * и корректную передачу HTTP-заголовков (User-Agent, Referer) и субтитров сторонним приложениям.
 */
object ExternalPlayerManager {
    private const val TAG = "ExternalPlayerManager"

    @Volatile
    private var cachedPlayers: List<ExternalPlayerApp>? = null

    /**
     * Динамическое получение списка всех установленных в Android приложений,
     * способных воспроизводить видеопотоки или видеофайлы.
     * Исключает собственное приложение и сортирует по алфавиту с учетом локали.
     */
    suspend fun getInstalledVideoPlayers(context: Context, forceRefresh: Boolean = false): List<ExternalPlayerApp> {
        if (!forceRefresh && cachedPlayers != null) {
            return cachedPlayers!!
        }

        return withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val myPackage = context.packageName
            val sampleVideoUri = Uri.parse("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")

            val videoHttpIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(sampleVideoUri, "video/*")
            }

            val videoFileIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("file:///sample.mp4"), "video/*")
            }

            val flags = PackageManager.MATCH_DEFAULT_ONLY

            val resolvedHttp = try {
                pm.queryIntentActivities(videoHttpIntent, flags)
            } catch (e: Exception) {
                emptyList()
            }

            val resolvedFile = try {
                pm.queryIntentActivities(videoFileIntent, flags)
            } catch (e: Exception) {
                emptyList()
            }

            val combined = (resolvedHttp + resolvedFile)
                .filter { it.activityInfo != null && it.activityInfo.packageName != myPackage }
                .distinctBy { it.activityInfo.packageName }

            val ruLocale = Locale.forLanguageTag("ru")
            val collator = Collator.getInstance(ruLocale).apply { strength = Collator.PRIMARY }

            val result = combined.mapNotNull { resolveInfo ->
                try {
                    val appName = resolveInfo.loadLabel(pm).toString()
                    val icon = resolveInfo.loadIcon(pm)
                    ExternalPlayerApp(
                        packageName = resolveInfo.activityInfo.packageName,
                        activityName = resolveInfo.activityInfo.name,
                        label = appName,
                        icon = icon
                    )
                } catch (e: Exception) {
                    null
                }
            }.sortedWith { a, b -> collator.compare(a.label, b.label) }

            cachedPlayers = result
            result
        }
    }

    /**
     * Запуск воспроизведения во внешнем плеере.
     * Если передан конкретный плеер (package:...), открывается напрямую в нем.
     * Если выбрана опция "Спросить внешний" (ask_external), открывается системный chooser Андроида.
     */
    fun launchPlayback(
        context: Context,
        streamUrl: String,
        title: String,
        subtitle: String = "",
        startPositionMs: Long = 0L,
        subtitles: List<SubtitleTrack> = emptyList(),
        playerKey: String = RezkaService.PLAYER_ASK_EXTERNAL
    ): Boolean {
        return try {
            val uri = Uri.parse(streamUrl)
            val fullTitle = if (subtitle.isNotBlank()) "$title — $subtitle" else title

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                putExtra("title", fullTitle)
                putExtra("android.intent.extra.TITLE", fullTitle)

                val userAgent = RezkaService.USER_AGENT
                val referer = "${RezkaService.currentBaseUrl}/"
                val origin = RezkaService.currentBaseUrl

                // Заголовки для популярных плееров (MX Player, Just Player, MPV)
                val headersArray = arrayOf(
                    "User-Agent", userAgent,
                    "Referer", referer,
                    "Origin", origin
                )
                putExtra("headers", headersArray)

                // Стандартный Android Media bundle
                val headerBundle = Bundle().apply {
                    putString("User-Agent", userAgent)
                    putString("Referer", referer)
                    putString("Origin", origin)
                }
                putExtra("android.media.intent.extra.HTTP_HEADERS", headerBundle)

                // Субтитры (VLC, MX Player)
                if (subtitles.isNotEmpty()) {
                    val subUris = subtitles.map { Uri.parse(it.url) }.toTypedArray()
                    val subNames = subtitles.map { it.title }.toTypedArray()
                    putExtra("subs", subUris)
                    putExtra("subs.enable", subUris)
                    putExtra("subs.name", subNames)
                }

                // Позиция воспроизведения (мс)
                if (startPositionMs > 0L) {
                    putExtra("position", startPositionMs.toInt())
                    putExtra("return_result", true)
                }
            }

            if (playerKey.startsWith("package:")) {
                val targetPackage = playerKey.removePrefix("package:").trim()
                intent.setPackage(targetPackage)
                try {
                    context.startActivity(intent)
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось открыть в плеере $targetPackage, откат к диалогу выбора", e)
                    intent.setPackage(null)
                    val chooser = Intent.createChooser(intent, "Воспроизвести в плеере")
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                    return true
                }
            } else {
                val chooser = Intent.createChooser(intent, "Воспроизвести в плеере")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при запуске внешнего плеера", e)
            Toast.makeText(context, "Не найден подходящий видеоплеер", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
