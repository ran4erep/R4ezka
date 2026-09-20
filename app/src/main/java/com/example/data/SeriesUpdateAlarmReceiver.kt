package com.example.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.example.RezkaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Высокопроизводительный фоновый Receiver на основе AlarmManager + WakeLock.
 * Гарантирует запуск проверки обновлений даже при ПОЛНОСТЬЮ ЗАКРЫТОМ / СВЕРНУТОМ приложении
 * и обходит ограничения Doze Mode / JobScheduler на Xiaomi, Samsung, Huawei и других устройствах.
 */
class SeriesUpdateAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "⏰ Сработал будильник фоновой проверки серий AlarmReceiver! Действие: ${intent?.action}")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Rezka:SeriesUpdateCheckWakeLock"
        )?.apply {
            acquire(30_000L) // Максимум 30 секунд удержания процессора
        }

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                RezkaService.init(context)
                val database = RezkaDatabase.getDatabase(context)
                val repository = RezkaRepository(database)

                Log.i(TAG, "Начало фонового сканирования подписок из AlarmReceiver...")
                val updated = SeriesUpdateEngine.checkAllSubscriptions(context, repository)
                Log.i(TAG, "Сканирование из AlarmReceiver завершено! Обновлений найдено: ${updated.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка сканирования в AlarmReceiver: ${e.message}", e)
            } finally {
                // Перепланируем следующий ТОЧНЫЙ будильник ровно через 1 час, обеспечивая непрерывный цикл
                try {
                    SeriesUpdateScheduler.scheduleExactAlarm(context, 3600_000L)
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось перезапланировать точный будильник: ${e.message}")
                }

                wakeLock?.let {
                    if (it.isHeld) {
                        try { it.release() } catch (_: Exception) {}
                    }
                }
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SeriesUpdateAlarm"
        const val ACTION_CHECK_UPDATES = "com.example.action.CHECK_SERIES_UPDATES"
    }
}
