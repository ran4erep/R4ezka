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
        Log.i("SeriesUpdateWorker", "Запуск фоновой проверки обновлений сериалов через WorkManager...")
        val context = applicationContext
        RezkaService.init(context)

        val repository = (context as? RezkaApplication)?.repository
            ?: RezkaRepository(RezkaDatabase.getDatabase(context))

        return try {
            val updated = SeriesUpdateEngine.checkAllSubscriptions(context, repository)
            Log.i("SeriesUpdateWorker", "Фоновая проверка WorkManager завершена успешно. Новых серий: ${updated.size}")
            Result.success()
        } catch (e: Exception) {
            Log.w("SeriesUpdateWorker", "Ошибка во время фоновой проверки WorkManager: ${e.message}")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}

object SeriesUpdateScheduler {
    private const val TAG = "SeriesUpdateScheduler"
    private const val UNIQUE_PERIODIC_WORK_NAME = "rezka_series_periodic_check"
    private const val UNIQUE_ONE_TIME_WORK_NAME = "rezka_series_manual_check"
    private const val PREFS_NAME = "rezka_series_prefs"
    private const val KEY_CHECK_INTERVAL_HOURS = "check_interval_hours"

    const val ALARM_REQUEST_CODE_PERIODIC = 1001
    const val ALARM_REQUEST_CODE_TEST = 1002

    fun getCheckIntervalHours(context: Context): Long {
        return 1L
    }

    fun setCheckIntervalHours(context: Context, hours: Long) {
        val safeHours = hours.coerceIn(1L, 24L)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_CHECK_INTERVAL_HOURS, safeHours).apply()
        // Перепланируем задачи с новым интервалом
        schedulePeriodicCheck(context, safeHours, forceUpdate = true)
        scheduleAlarmCheck(context, safeHours)
    }

    /**
     * Планирует периодическую проверку новых серий через WorkManager.
     * Использует KEEP политику по умолчанию, чтобы не сбрасывать таймер при каждом открытии приложения!
     */
    fun schedulePeriodicCheck(
        context: Context,
        intervalHours: Long = getCheckIntervalHours(context),
        forceUpdate: Boolean = false
    ) {
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

        val policy = if (forceUpdate) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK_NAME,
            policy,
            periodicRequest
        )
        Log.i(TAG, "Периодическая проверка WorkManager запланирована (каждые $intervalHours ч., policy=$policy)")

        // Параллельно поддерживаем AlarmManager для гарантированного пробуждения в Doze mode
        scheduleAlarmCheck(context, intervalHours)
    }

    /**
     * Планирует высоконадежную проверку через AlarmManager, способную пробить Doze Mode
     * и разбудить BroadcastReceiver даже если приложение полностью выгружено из памяти или закрыто.
     */
    fun scheduleAlarmCheck(context: Context, intervalHours: Long = getCheckIntervalHours(context)) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, SeriesUpdateReceiver::class.java).apply {
                action = SeriesUpdateReceiver.ACTION_CHECK_SERIES_UPDATES
                setPackage(context.packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE_PERIODIC,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAtMillis = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(intervalHours)

            var scheduled = false
            // 1. Приоритет: setAlarmClock (пробуждает систему без ограничений энергосбережения)
            try {
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, pendingIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                scheduled = true
            } catch (e: Exception) {
                Log.d(TAG, "setAlarmClock periodic fallback: ${e.message}")
            }

            if (!scheduled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            }
            Log.i(TAG, "AlarmManager запланирован на +$intervalHours ч. (через ${TimeUnit.HOURS.toMinutes(intervalHours)} мин.)")
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось запланировать проверку в AlarmManager: ${e.message}")
            // Надежный фоллбэк без точного будильника, гарантированно срабатывающий даже при запретах
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                val intent = Intent(context, SeriesUpdateReceiver::class.java).apply {
                    action = SeriesUpdateReceiver.ACTION_CHECK_SERIES_UPDATES
                    setPackage(context.packageName)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    ALARM_REQUEST_CODE_PERIODIC,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAtMillis = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(intervalHours)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager?.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Сбой и в фоллбэке AlarmManager: ${fallbackEx.message}")
            }
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
     * Запланировать фоновую проверку со сдвигом по времени (например, ровно через 10 секунд).
     * Использует AlarmManager.setAlarmClock — единственный системный механизм в Android OS,
     * который НЕ подвержен 9-15 минутному троттлингу Doze-mode и гарантированно срабатывает
     * каждую секунду при повторных запусках теста, даже когда приложение закрыто и выгружено из памяти.
     */
    fun scheduleDelayedCheck(context: Context, delaySeconds: Long = 10) {
        val safeDelay = delaySeconds.coerceAtLeast(3)
        Log.i(TAG, "Планирование тестовой проверки через $safeDelay сек. (setAlarmClock + WorkManager)...")

        // 1. Аппаратный AlarmManager с наивысшим приоритетом setAlarmClock
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager != null) {
                val intent = Intent(context, SeriesUpdateReceiver::class.java).apply {
                    action = SeriesUpdateReceiver.ACTION_DELAYED_TEST_CHECK
                    setPackage(context.packageName)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    ALARM_REQUEST_CODE_TEST,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAtMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(safeDelay)

                var scheduled = false
                // setAlarmClock полностью освобожден от лимитов Doze-mode и срабатывает точно в срок
                try {
                    val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, pendingIntent)
                    alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                    scheduled = true
                    Log.i(TAG, "Тестовый AlarmClock успешно установлен ровно на +$safeDelay сек.")
                } catch (e: Exception) {
                    Log.w(TAG, "setAlarmClock не удался: ${e.message}, пробуем setExactAndAllowWhileIdle")
                }

                if (!scheduled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (alarmManager.canScheduleExactAlarms()) {
                            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                        } else {
                            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    } else {
                        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    }
                    Log.i(TAG, "Тестовый AlarmManager взведен на +$safeDelay сек. через setExact/AllowWhileIdle")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка планирования тестового AlarmManager: ${e.message}")
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                val intent = Intent(context, SeriesUpdateReceiver::class.java).apply {
                    action = SeriesUpdateReceiver.ACTION_DELAYED_TEST_CHECK
                    setPackage(context.packageName)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    ALARM_REQUEST_CODE_TEST,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAtMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(safeDelay)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } catch (_: Exception) {}
        }

        // 2. Дополнительный WorkManager
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val testRequest = OneTimeWorkRequestBuilder<SeriesUpdateWorker>()
                .setConstraints(constraints)
                .setInitialDelay(safeDelay, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "rezka_series_test_delayed_check",
                ExistingWorkPolicy.REPLACE,
                testRequest
            )
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка планирования тестового WorkManager: ${e.message}")
        }
    }
}
