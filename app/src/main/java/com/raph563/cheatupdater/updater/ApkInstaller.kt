package com.raph563.cheatupdater.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.raph563.cheatupdater.receiver.InstallResultReceiver
import java.io.File

class ApkInstaller(private val context: Context) {
    private val packageInstaller: PackageInstaller
        get() = context.packageManager.packageInstaller

    fun install(apkFile: File, packageNameHint: String?) {
        require(apkFile.exists()) { "APK introuvable: ${apkFile.absolutePath}" }

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(apkFile.length())
            packageNameHint?.let { setAppPackageName(it) }
        }

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        try {
            session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                apkFile.inputStream().use { input ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            val callbackIntent = Intent(context, InstallResultReceiver::class.java).apply {
                putExtra(InstallResultReceiver.EXTRA_PACKAGE_NAME_HINT, packageNameHint)
                putExtra(InstallResultReceiver.EXTRA_FILE_PATH, apkFile.absolutePath)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            session.commit(pendingIntent.intentSender)
        } catch (t: Throwable) {
            session.abandon()
            throw t
        } finally {
            session.close()
        }
    }
}
