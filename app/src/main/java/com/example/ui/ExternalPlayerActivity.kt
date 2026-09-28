package com.example.ui

import android.app.Application
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.data.StreamUrl
import com.example.ui.components.RezkaPlayer
import com.example.ui.theme.CinemaBlack
import com.example.ui.theme.MyApplicationTheme

/**
 * Изолированная легковесная Activity для воспроизведения внешних видеофайлов и потоков
 * с устройства (из проводников, файловых менеджеров, мессенджеров и галереи).
 * 
 * Преимущества архитектуры:
 * 1. Минимальная нагрузка на CPU и память: не инициализирует каталог, сервисы фоновой синхронизации и БД.
 * 2. Фиксированная альбомная (горизонтальная) ориентация: исключает циклические перевороты экрана.
 * 3. При выходе (кнопка Назад, стрелка закрытия) вызывает finish() и возвращает пользователя
 *    ровно в то приложение, из которого было открыто видео.
 */
class ExternalPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Гарантированная фиксация горизонтальной (ландшафтной) ориентации под любой наклон
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        val targetUri: Uri? = intent?.data
        if (targetUri == null) {
            finish()
            return
        }

        val videoTitle = resolveDisplayName(application, targetUri)

        setContent {
            MyApplicationTheme {
                BackHandler {
                    finish()
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(CinemaBlack)
                ) {
                    RezkaPlayer(
                        title = videoTitle,
                        subtitle = "",
                        itemId = "ext_${targetUri.hashCode()}",
                        streams = listOf(StreamUrl(quality = "Оригинал", url = targetUri.toString())),
                        isTvMode = false,
                        onBack = { finish() },
                        onProgressUpdate = { _, _ -> }
                    )
                }
            }
        }
    }

    private fun resolveDisplayName(app: Application, uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) {
                            val name = cursor.getString(nameIdx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        val lastSegment = uri.lastPathSegment
        if (!lastSegment.isNullOrBlank()) {
            return lastSegment.substringAfterLast('/')
        }
        return "Видео"
    }
}
