package com.raph563.cheatupdater.notifier

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.raph563.cheatupdater.MainActivity
import com.raph563.cheatupdater.R

object NotificationHelper {
    const val CHANNEL_PERSISTENT = "persistent_channel"
    const val CHANNEL_UPDATES = "updates_channel"
    const val CHANNEL_CRITICAL = "critical_channel"
    const val NOTIF_ID_PERSISTENT = 2001
    const val NOTIF_ID_UPDATES = 2002
    const val NOTIF_ID_CRITICAL = 2003
    const val NOTIF_ID_INSTALL_FAILURE = 2004

    const val EXTRA_PACKAGE_NAME = "extra_package_name"
    const val EXTRA_FILE_PATH = "extra_file_path"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val persistent = NotificationChannel(
            CHANNEL_PERSISTENT,
            context.getString(R.string.notif_channel_persistent_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_persistent_desc)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val updates = NotificationChannel(
            CHANNEL_UPDATES,
            context.getString(R.string.notif_channel_updates_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_updates_desc)
        }

        val critical = NotificationChannel(
            CHANNEL_CRITICAL,
            context.getString(R.string.notif_channel_critical_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_critical_desc)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannels(listOf(persistent, updates, critical))
    }

    fun buildForegroundNotification(context: Context): android.app.Notification {
        return NotificationCompat.Builder(context, CHANNEL_PERSISTENT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("CheatUpdater actif")
            .setContentText("Surveillance des releases GitHub en cours.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainPendingIntent(context))
            .build()
    }

    fun showUpdatesAvailable(context: Context, count: Int, tag: String) {
        val text = if (count == 1) {
            "1 APK a installer ou mettre a jour (release $tag)."
        } else {
            "$count APK a installer ou mettre a jour (release $tag)."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Mises a jour detectees")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(mainPendingIntent(context))
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID_UPDATES, notification)
    }

    fun showMandatoryAlert(context: Context, reason: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_CRITICAL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Mise a jour obligatoire")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent(context))
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID_CRITICAL, notification)
    }

    fun showInstallFailure(context: Context, packageName: String?, filePath: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_FILE_PATH, filePath)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            9002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Installation echouee")
            .setContentText("Touchez pour desinstaller puis reinstaller.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID_INSTALL_FAILURE, notification)
    }

    private fun mainPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            9001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
