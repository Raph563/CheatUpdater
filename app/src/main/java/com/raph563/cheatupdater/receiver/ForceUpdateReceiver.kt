package com.raph563.cheatupdater.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.raph563.cheatupdater.notifier.NotificationHelper
import com.raph563.cheatupdater.worker.WorkScheduler

class ForceUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val reason = intent?.getStringExtra(EXTRA_REASON)
            ?: "Mise a jour requise pour assurer le fonctionnement du telephone."
        NotificationHelper.showMandatoryAlert(context, reason)
        WorkScheduler.enqueueOneTimeCheck(context)
    }

    companion object {
        const val ACTION_FORCE_UPDATE = "com.raph563.cheatupdater.ACTION_FORCE_UPDATE"
        const val EXTRA_REASON = "reason"
    }
}
