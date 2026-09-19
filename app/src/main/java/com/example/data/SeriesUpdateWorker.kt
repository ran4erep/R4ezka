package com.example.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.*
import com.example.RezkaApplication
import java.util.concurrent.TimeUnit

class SeriesUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i("SeriesUpdateWorker", "Запуск фоновой проверки обновлений сериалов из WorkManager...")
        val app = applicationContext as? RezkaApplication ?: return Result.failure()

        return try {
            val updated = SeriesUpdateEngine.checkAllSubscriptions(app, app.repository)
            Log.i("SeriesUpdateWorker", "Фоновая проверка завершена успешно. Новых серий найдено: ${updated.size}")
            Result.success()
        } catch (e: Exception) {
            Log.w("SeriesUpdateWorker", "Ошибка во время фоновой проверки: ${e.message}")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}

object SeriesUpdateScheduler {
    private const val UNIQUE_PERIODIC_WORK_NAME = "rezka_series_periodic_check"
    private const val UNIQUE_ONE_TIME_WORK_NAME = "rezka_series_manual_check"
    private const val TEST_ALARM_REQ_CODE = 8801
    private const val PERIODIC_ALARM_REQ_CODE = 8802

    /**
     * Планирует периодическую проверку новых серий через WorkManager и резервный AlarmManager.
     * Интервал проверки: 1 час.
     * Совмещенная схема гарантирует срабатывание на любых прошивка Android (MIUI, OneUI и т.д.).
     */
    fun schedulePeriodicCheck(context: Context, intervalHours: Long = 1) {
        // 1. WorkManager
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<SeriesUpdateWorker>(
            intervalHours.coerceAtLeast(1),
            TimeUnit.HOURS,
            15,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )

        // 2. Резервный AlarmManager для обхода жестких фоновых ограничений вендоров
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val intent = Intent(context, SeriesUpdateAlarmReceiver::class.java).apply {
                action = SeriesUpdateAlarmReceiver.ACTION_CHECK_UPDATES
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                PERIODIC_ALARM_REQ_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val intervalMs = intervalHours.coerceAtLeast(1) * 3600_000L
            val triggerAtMs = System.currentTimeMillis() + intervalMs

            alarmManager?.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                triggerAtMs,
                intervalMs,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.w("SeriesUpdateScheduler", "Не удалось запланировать резервный AlarmManager: ${e.message}")
        }
    }

    /**
     * Запускает немедленную ручную проверку всех подписок прямо сейчас.
     */
    fun triggerImmediateCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeRequest = OneTimeWorkRequestBuilder<SeriesUpdateWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            oneTimeRequest
        )
    }

    /**
     * Запланировать фоновую проверку со сдвигом по времени (например, через 10 секунд).
     * Использует AlarmManager setExactAndAllowWhileIdle, что гарантирует мгновенный запуск
     * и доставку пуш-уведомления даже если приложение ПОЛНОСТЬЮ ЗАКРЫТО И ВЫГРУЖЕНО из памяти!
     */
    fun scheduleDelayedCheck(context: Context, delaySeconds: Long = 10) {
        Log.i("SeriesUpdateScheduler", "Планирование точной тестовой проверки через $delaySeconds секунд...")

        // 1. Прямой точный будильник AlarmManager (срабатывает в Doze Mode / при закрытом приложении)
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val intent = Intent(context, SeriesUpdateAlarmReceiver::class.java).apply {
                action = SeriesUpdateAlarmReceiver.ACTION_CHECK_UPDATES
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                TEST_ALARM_REQ_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAtMs = System.currentTimeMillis() + (delaySeconds * 1000L)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            } else {
                alarmManager?.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            }
            Log.i("SeriesUpdateScheduler", "Будильник AlarmManager успешно взведён на $triggerAtMs (через $delaySeconds сек)")
        } catch (e: Exception) {
            Log.e("SeriesUpdateScheduler", "Ошибка установки точного будильника AlarmManager: ${e.message}", e)
        }

        // 2. Вторичный фоллбэк через WorkManager
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val testRequest = OneTimeWorkRequestBuilder<SeriesUpdateWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "rezka_series_test_delayed_check",
                ExistingWorkPolicy.REPLACE,
                testRequest
            )
        } catch (e: Exception) {
            Log.w("SeriesUpdateScheduler", "Ошибка планирования WorkManager: ${e.message}")
        }
    }
}
