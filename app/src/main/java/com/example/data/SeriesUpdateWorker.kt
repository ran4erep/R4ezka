package com.example.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.*
import com.example.RezkaApplication
import java.util.concurrent.TimeUnit

class SeriesUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i("SeriesUpdateWorker", "Запуск резервной фоновой проверки обновлений...")
        val app = applicationContext as? RezkaApplication ?: return Result.failure()

        return try {
            val updated = SeriesUpdateEngine.checkAllSubscriptions(app, app.repository)
            Log.i("SeriesUpdateWorker", "Резервная фоновая проверка завершена успешно. Новых серий найдено: ${updated.size}")
            Result.success()
        } catch (e: Exception) {
            Log.w("SeriesUpdateWorker", "Ошибка резервной фоновой проверки: ${e.message}")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}

object SeriesUpdateScheduler {
    private const val UNIQUE_PERIODIC_WORK_NAME = "rezka_series_periodic_check"
    private const val UNIQUE_ONE_TIME_WORK_NAME = "rezka_series_manual_check"
    private const val UNIQUE_TEST_WORK_NAME = "rezka_series_test_delayed_check"

    private const val PERIODIC_ALARM_REQ_CODE = 8802
    private const val TEST_ALARM_REQ_CODE = 8801
    private const val PERIODIC_BACKUP_ALARM_REQ_CODE = 8803

    private const val ACTION_CHECK_UPDATES = "com.example.action.CHECK_SERIES_UPDATES"
    private const val EXTRA_ALARM_KIND = "alarm_kind"
    private const val EXTRA_EXACT_ALARM = "exact_alarm"

    private const val ALARM_KIND_PERIODIC = "periodic"
    private const val ALARM_KIND_TEST = "test"

    private const val LEGACY_CLEANUP_PREFS = "background_schedule"
    private const val LEGACY_CLEANUP_DONE = "legacy_work_cancelled_v2"

    fun schedulePeriodicCheck(context: Context, intervalHours: Long = 1) {
        cancelLegacyWorkOnce(context)
        cancelAlarm(context, PERIODIC_ALARM_REQ_CODE, ALARM_KIND_PERIODIC)
        cancelAlarm(context, PERIODIC_BACKUP_ALARM_REQ_CODE, ALARM_KIND_PERIODIC)

        scheduleAlarm(
            context = context,
            delayMs = intervalHours.coerceAtLeast(1) * 3600_000L,
            requestCode = PERIODIC_ALARM_REQ_CODE,
            kind = ALARM_KIND_PERIODIC,
            exactPreferred = true
        )
    }

    fun scheduleDelayedCheck(context: Context, delaySeconds: Long = 10) {
        cancelAlarm(context, TEST_ALARM_REQ_CODE, ALARM_KIND_TEST)

        scheduleAlarm(
            context = context,
            delayMs = delaySeconds.coerceAtLeast(1) * 1000L,
            requestCode = TEST_ALARM_REQ_CODE,
            kind = ALARM_KIND_TEST,
            exactPreferred = true
        )
    }

    fun onAlarmFired(context: Context, kind: String, exactAlarm: Boolean): Boolean {
        if (kind == ALARM_KIND_TEST) {
            cancelAlarm(context, TEST_ALARM_REQ_CODE, ALARM_KIND_TEST)
        }

        schedulePeriodicCheck(context)

        if (!exactAlarm) {
            return false
        }

        return try {
            val intent = Intent(context, SeriesUpdateForegroundService::class.java).apply {
                action = ACTION_CHECK_UPDATES
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: Exception) {
            Log.w("SeriesUpdateScheduler", "Не удалось запустить фоновый сервис проверки: ${e.message}")
            false
        }
    }

    fun enqueueFallbackWorker(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SeriesUpdateWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        } catch (e: Exception) {
            Log.e("SeriesUpdateScheduler", "Не удалось поставить резервную проверку: ${e.message}", e)
        }
    }

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

    private fun scheduleAlarm(
        context: Context,
        delayMs: Long,
        requestCode: Int,
        kind: String,
        exactPreferred: Boolean
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return

        val triggerAtMs = System.currentTimeMillis() + delayMs
        var exactScheduled = false

        if (exactPreferred && canScheduleExactAlarms(context)) {
            try {
                val intent = createIntent(context, kind, exactAlarm = true)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMs,
                        pendingIntent
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMs,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMs,
                        pendingIntent
                    )
                }

                exactScheduled = true
                Log.i(
                    "SeriesUpdateScheduler",
                    "Точный AlarmManager установлен: kind=$kind, trigger=$triggerAtMs, delay=${delayMs / 1000}s"
                )

                if (kind == ALARM_KIND_PERIODIC) {
                    schedulePeriodicBackupAlarm(context, triggerAtMs)
                }
                return
            } catch (e: SecurityException) {
                Log.w("SeriesUpdateScheduler", "Exact alarm недоступен, используется резервный AlarmManager: ${e.message}")
            } catch (e: Exception) {
                Log.w("SeriesUpdateScheduler", "Ошибка установки exact alarm, используется резервный AlarmManager: ${e.message}")
            }
        }

        try {
            val intent = createIntent(context, kind, exactAlarm = false)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            }

            Log.w(
                "SeriesUpdateScheduler",
                "Установлен неexact AlarmManager: kind=$kind, trigger=$triggerAtMs, delay=${delayMs / 1000}s"
            )
        } catch (e: Exception) {
            Log.e("SeriesUpdateScheduler", "Ошибка установки резервного AlarmManager: ${e.message}", e)
        }
    }

    private fun schedulePeriodicBackupAlarm(context: Context, triggerAtMs: Long) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return
            val intent = createIntent(context, ALARM_KIND_PERIODIC, exactAlarm = false)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                PERIODIC_BACKUP_ALARM_REQ_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            Log.w("SeriesUpdateScheduler", "Не удалось установить резервный alarm: ${e.message}")
        }
    }

    private fun createIntent(context: Context, kind: String, exactAlarm: Boolean): Intent {
        return Intent(context, SeriesUpdateAlarmReceiver::class.java).apply {
            action = ACTION_CHECK_UPDATES
            putExtra(EXTRA_ALARM_KIND, kind)
            putExtra(EXTRA_EXACT_ALARM, exactAlarm)
            setPackage(context.packageName)
        }
    }

    private fun cancelAlarm(context: Context, requestCode: Int, kind: String) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                createIntent(context, kind, exactAlarm = true),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }

            val fallbackPendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                createIntent(context, kind, exactAlarm = false),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (fallbackPendingIntent != null) {
                alarmManager.cancel(fallbackPendingIntent)
                fallbackPendingIntent.cancel()
            }
        } catch (e: Exception) {
            Log.w("SeriesUpdateScheduler", "Ошибка отмены alarm $requestCode: ${e.message}")
        }
    }

    private fun cancelLegacyWorkOnce(context: Context) {
        val prefs = context.getSharedPreferences(LEGACY_CLEANUP_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LEGACY_CLEANUP_DONE, false)) {
            return
        }

        try {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(UNIQUE_ONE_TIME_WORK_NAME)
            workManager.cancelUniqueWork(UNIQUE_TEST_WORK_NAME)
        } catch (e: Exception) {
            Log.w("SeriesUpdateScheduler", "Не удалось отменить старые WorkManager-задачи: ${e.message}")
        } finally {
            prefs.edit().putBoolean(LEGACY_CLEANUP_DONE, true).apply()
        }
    }

    private fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.canScheduleExactAlarms() == true
        } catch (e: Exception) {
            false
        }
    }
}
