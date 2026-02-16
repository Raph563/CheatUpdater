package com.raph563.cheatupdater.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.raph563.cheatupdater.data.AppPreferences
import com.raph563.cheatupdater.data.UpdateAction
import com.raph563.cheatupdater.data.UpdateSources
import com.raph563.cheatupdater.network.UpdateService
import com.raph563.cheatupdater.notifier.NotificationHelper

class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = AppPreferences(applicationContext)
        val sourceId = prefs.getSelectedSourceId()
        val source = UpdateSources.findById(sourceId) ?: UpdateSources.default()

        return runCatching {
            val updateService = UpdateService(applicationContext)
            val result = updateService.checkForUpdates(source, prefs.getLastSeenTag(source.id))
            if (result.isNewRelease) {
                prefs.setLastSeenTag(source.id, result.release.tagName)
            }
            val actionable = result.candidates.count {
                it.action == UpdateAction.INSTALL || it.action == UpdateAction.UPDATE
            }
            if (actionable > 0) {
                NotificationHelper.showUpdatesAvailable(
                    context = applicationContext,
                    count = actionable,
                    tag = result.release.tagName
                )
            }
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}
