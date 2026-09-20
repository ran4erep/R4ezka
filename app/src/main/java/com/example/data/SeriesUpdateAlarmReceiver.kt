package com.example.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SeriesUpdateAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CHECK_UPDATES) {
            return
        }

        val kind = intent.getStringExtra(EXTRA_ALARM_KIND) ?: ALARM_KIND_PERIODIC
        val exactAlarm = intent.getBooleanExtra(EXTRA_EXACT_ALARM, false)

        Log.i(
            TAG,
            "Сработал фоновой alarm: kind=$kind, exact=$exactAlarm"
        )

        val started = SeriesUpdateScheduler.onAlarmFired(
            context = context,
            kind = kind,
            exactAlarm = exactAlarm
        )

        if (!started) {
            Log.w(TAG, "Exact foreground service не запущен; используется системный резервный Worker.")
            SeriesUpdateScheduler.enqueueFallbackWorker(context)
        }
    }

    companion object {
        private const val TAG = "SeriesUpdateAlarm"

        const val ACTION_CHECK_UPDATES = "com.example.action.CHECK_SERIES_UPDATES"
        const val EXTRA_ALARM_KIND = "alarm_kind"
        const val EXTRA_EXACT_ALARM = "exact_alarm"

        const val ALARM_KIND_PERIODIC = "periodic"
        const val ALARM_KIND_TEST = "test"
    }
}
