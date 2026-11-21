package com.hanson.mobileuse.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hanson.mobileuse.notification.HansonNotificationListenerService

/**
 * Automatically restarts the Hanson Foreground Watchdog and re-binds the NotificationListener
 * after device reboot (`BOOT_COMPLETED`) or APK update (`MY_PACKAGE_REPLACED`).
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            HansonForegroundService.start(context)
            HansonNotificationListenerService.ensureRebound(packageName = context.packageName)
        }
    }
}
