package com.raph563.cheatupdater.data

import android.content.Context

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    private fun lastTagKey(sourceId: String): String = "last_tag_$sourceId"

    companion object {
        private const val PREFS_NAME = "cheat_updater_prefs"
        private const val KEY_SELECTED_SOURCE_ID = "selected_source_id"
    }
}
