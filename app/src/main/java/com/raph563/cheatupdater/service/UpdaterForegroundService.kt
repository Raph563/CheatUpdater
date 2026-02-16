package com.raph563.cheatupdater.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.raph563.cheatupdater.notifier.NotificationHelper
import com.raph563.cheatupdater.worker.WorkScheduler

class UpdaterForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NotificationHelper.NOTIF_ID_PERSISTENT,
            NotificationHelper.buildForegroundNotification(this)
        )
        if (intent?.action == ACTION_CHECK_NOW) {
            WorkScheduler.enqueueOneTimeCheck(this)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CHECK_NOW = "com.raph563.cheatupdater.action.CHECK_NOW"

        fun start(context: Context) {
            val intent = Intent(context, UpdaterForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startAndCheck(context: Context) {
            val intent = Intent(context, UpdaterForegroundService::class.java).apply {
                action = ACTION_CHECK_NOW
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
