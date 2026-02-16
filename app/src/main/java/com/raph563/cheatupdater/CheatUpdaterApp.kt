package com.raph563.cheatupdater

import android.app.Application
import com.raph563.cheatupdater.notifier.NotificationHelper
import com.raph563.cheatupdater.worker.WorkScheduler

class CheatUpdaterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        WorkScheduler.scheduleDailyNoonCheck(this)
    }
}
