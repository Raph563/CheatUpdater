package com.raph563.cheatupdater.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object WorkScheduler {
    private const val DAILY_WORK_NAME = "daily_noon_update_check"
    private const val ON_DEMAND_WORK_NAME = "on_demand_update_check"

    fun scheduleDailyNoonCheck(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val delay = computeDelayUntilNextNoon(now)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .addTag(DAILY_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DAILY_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun enqueueOneTimeCheck(context: Context) {
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ON_DEMAND_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun computeDelayUntilNextNoon(now: LocalDateTime): Duration {
        val noon = now.toLocalDate().atTime(LocalTime.NOON)
        val nextNoon = if (now.isBefore(noon)) noon else noon.plusDays(1)
        return Duration.between(now, nextNoon)
    }
}
