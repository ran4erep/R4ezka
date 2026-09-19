package com.example.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.example.RezkaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Высоконадежный системный ресивер для обработки пробуждений от AlarmManager,
 * системной перезагрузки (BOOT_COMPLETED) и обновления приложения (MY_PACKAGE_REPLACED).
 * Гарантирует запуск проверки и доставку уведомлений ДАЖЕ ЕСЛИ ПРИЛОЖЕНИЕ ЗАКРЫТО или выгружено из памяти.
 */
class SeriesUpdateReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "SeriesUpdateReceiver"
        const val ACTION_CHECK_SERIES_UPDATES = "com.example.ACTION_CHECK_SERIES_UPDATES"
        const val ACTION_DELAYED_TEST_CHECK = "com.example.ACTION_DELAYED_TEST_CHECK"

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Получен broadcast: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Система перезагружена/обновлена. Восстанавливаем расписание проверок...")
                SeriesUpdateScheduler.schedulePeriodicCheck(context)
                SeriesUpdateScheduler.scheduleAlarmCheck(context)
            }

            ACTION_CHECK_SERIES_UPDATES,
            ACTION_DELAYED_TEST_CHECK -> {
                val isTest = (action == ACTION_DELAYED_TEST_CHECK)
                Log.i(TAG, "Запуск фоновой проверки через AlarmManager (isTest=$isTest)...")

                val pendingResult = goAsync()
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Rezka:SeriesUpdateWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(60_000L) // Максимум 60 секунд на проверку серий
                }

                receiverScope.launch {
                    try {
                        withTimeoutOrNull(50_000L) {
                            // Гарантируем инициализацию сервиса и базы данных
                            RezkaService.init(context)

                            val repository = (context.applicationContext as? RezkaApplication)?.repository
                                ?: RezkaRepository(RezkaDatabase.getDatabase(context))

                            val updated = SeriesUpdateEngine.checkAllSubscriptions(context, repository)
                            Log.i(TAG, "Проверка в фоновом режиме завершена. Найдено обновлений: ${updated.size}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка фоновой проверки в ресивере: ${e.message}", e)
                    } finally {
                        // Если это была регулярная периодическая проверка через Alarm, планируем следующий интервал
                        if (!isTest) {
                            SeriesUpdateScheduler.scheduleAlarmCheck(context)
                        }
                        try {
                            if (wakeLock?.isHeld == true) {
                                wakeLock.release()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Ошибка освобождения WakeLock: ${e.message}")
                        }
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
