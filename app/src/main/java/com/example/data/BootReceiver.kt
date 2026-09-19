package com.example.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Восстанавливает работу фоновой проверки новых серий при перезагрузке устройства
 * или обновлении приложения.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i("BootReceiver", "Системное событие получено: $action. Восстанавливаем фоновые проверки...")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            RezkaService.init(context)
            SeriesUpdateScheduler.schedulePeriodicCheck(context)
        }
    }
}
