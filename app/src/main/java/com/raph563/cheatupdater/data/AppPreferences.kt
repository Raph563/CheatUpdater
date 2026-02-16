package com.raph563.cheatupdater.data

import android.content.Context
import com.raph563.cheatupdater.BuildConfig

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadRepoConfig(): RepoConfig {
        val owner = prefs.getString(KEY_OWNER, BuildConfig.DEFAULT_OWNER).orEmpty()
        val repo = prefs.getString(KEY_REPO, BuildConfig.DEFAULT_REPO).orEmpty()
        val token = prefs.getString(KEY_TOKEN, "").orEmpty().ifBlank { null }
        return RepoConfig(owner = owner, repo = repo, token = token)
    }

    fun saveRepoConfig(config: RepoConfig) {
        prefs.edit()
            .putString(KEY_OWNER, config.owner.trim())
            .putString(KEY_REPO, config.repo.trim())
            .putString(KEY_TOKEN, config.token.orEmpty().trim())
            .apply()
    }

    fun getLastSeenTag(): String? = prefs.getString(KEY_LAST_TAG, null)

    fun setLastSeenTag(tag: String) {
        prefs.edit().putString(KEY_LAST_TAG, tag).apply()
    }

    companion object {
        private const val PREFS_NAME = "cheat_updater_prefs"
        private const val KEY_OWNER = "owner"
        private const val KEY_REPO = "repo"
        private const val KEY_TOKEN = "token"
        private const val KEY_LAST_TAG = "last_tag"
    }
}
