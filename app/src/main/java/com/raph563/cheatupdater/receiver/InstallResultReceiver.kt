package com.raph563.cheatupdater.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.raph563.cheatupdater.notifier.NotificationHelper

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val source = intent ?: return
        val status = source.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val packageName = source.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: source.getStringExtra(EXTRA_PACKAGE_NAME_HINT)
        val statusMessage = source.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val filePath = source.getStringExtra(EXTRA_FILE_PATH)

        val internalBroadcast = Intent(ACTION_INSTALL_RESULT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_STATUS_MESSAGE, statusMessage)
            putExtra(EXTRA_FILE_PATH, filePath)
        }
        context.sendBroadcast(internalBroadcast)

        if (status != PackageInstaller.STATUS_SUCCESS) {
            NotificationHelper.showInstallFailure(context, packageName, filePath)
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.raph563.cheatupdater.ACTION_INSTALL_RESULT"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_PACKAGE_NAME_HINT = "extra_package_name_hint"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
        const val EXTRA_FILE_PATH = "extra_file_path"
    }
}
