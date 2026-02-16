package com.raph563.cheatupdater.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.raph563.cheatupdater.service.UpdaterForegroundService
import com.raph563.cheatupdater.worker.WorkScheduler

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            WorkScheduler.scheduleDailyNoonCheck(context)
            WorkScheduler.enqueueOneTimeCheck(context)
            UpdaterForegroundService.start(context)
        }
    }
}
