package com.raph563.cheatupdater.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME_SECURE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSelectedSourceId(): String =
        prefs.getString(KEY_SELECTED_SOURCE_ID, UpdateSources.default().id).orEmpty()

    fun setSelectedSourceId(sourceId: String) {
        prefs.edit().putString(KEY_SELECTED_SOURCE_ID, sourceId).apply()
    }

    fun getLastSeenTag(sourceId: String): String? =
        prefs.getString(lastTagKey(sourceId), null)

    fun setLastSeenTag(sourceId: String, tag: String) {
        prefs.edit().putString(lastTagKey(sourceId), tag).apply()
    }

    fun getInstalledReleaseId(packageName: String): String? =
        prefs.getString(installedReleaseKey(packageName), null)

    fun setInstalledReleaseId(packageName: String, releaseId: String?) {
        prefs.edit().putString(installedReleaseKey(packageName), releaseId).apply()
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun setAccessToken(value: String?) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()
    }

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun setRefreshToken(value: String?) {
        prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_ROLE)
            .remove(KEY_REFERRAL_CODE)
            .apply()
    }

    fun setUserProfile(userId: String?, email: String?, role: String?, referralCode: String?) {
        prefs.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USER_EMAIL, email)
            .putString(KEY_USER_ROLE, role)
            .putString(KEY_REFERRAL_CODE, referralCode)
            .apply()
    }

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)
    fun getUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)
    fun getUserRole(): String? = prefs.getString(KEY_USER_ROLE, null)
    fun getReferralCode(): String? = prefs.getString(KEY_REFERRAL_CODE, null)

    private fun lastTagKey(sourceId: String): String = "last_tag_$sourceId"
    private fun installedReleaseKey(packageName: String): String = "pkg_release_$packageName"

    companion object {
        private const val PREFS_NAME = "cheat_updater_prefs"
        private const val PREFS_NAME_SECURE = "cheat_updater_secure_prefs"
        private const val KEY_SELECTED_SOURCE_ID = "selected_source_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_ROLE = "user_role"
        private const val KEY_REFERRAL_CODE = "referral_code"
    }
}
