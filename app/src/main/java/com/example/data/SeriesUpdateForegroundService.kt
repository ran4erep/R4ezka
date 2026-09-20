package com.example.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SeriesUpdateForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checkJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_CHECK_UPDATES) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (checkJob?.isActive == true) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            createForegroundNotificationChannel()
            val notification = createForegroundNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось перевести сервис в foreground: ${e.message}", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        checkJob = serviceScope.launch {
            try {
                RezkaService.init(applicationContext)
                val database = RezkaDatabase.getDatabase(applicationContext)
                val repository = RezkaRepository(database)

                Log.i(TAG, "Начало фонового сканирования подписок из ForegroundService...")
                val updated = SeriesUpdateEngine.checkAllSubscriptions(
                    applicationContext,
                    repository
                )
                Log.i(
                    TAG,
                    "Фоновое сканирование завершено. Обновлений найдено: ${updated.size}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка фонового сканирования: ${e.message}", e)
            } finally {
                checkJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        checkJob?.cancel()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "ForegroundService получил системный timeout: startId=$startId, type=$fgsType")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createForegroundNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Фоновая проверка",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        manager.createNotificationChannel(channel)
    }

    private fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.example.R.drawable.ic_notification)
            .setContentTitle(getString(com.example.R.string.app_name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "SeriesUpdateService"
        private const val CHANNEL_ID = "rezka_background_check_channel"
        private const val NOTIFICATION_ID = 47021
        const val ACTION_CHECK_UPDATES = "com.example.action.CHECK_SERIES_UPDATES"
    }
}
