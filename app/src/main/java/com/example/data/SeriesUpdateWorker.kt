package com.example.data

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.RezkaApplication
import java.util.concurrent.TimeUnit

class SeriesUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i("SeriesUpdateWorker", "Запуск фоновой проверки обновлений сериалов...")
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

    /**
     * Планирует периодическую проверку новых серий через WorkManager.
     * Интервал проверки: 1 час (с flex-интервалом 15 минут).
     * Проверка срабатывает при наличии сети независимо от уровня заряда батареи.
     */
    fun schedulePeriodicCheck(context: Context, intervalHours: Long = 1) {
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
}
