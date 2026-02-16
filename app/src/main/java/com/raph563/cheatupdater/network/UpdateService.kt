package com.raph563.cheatupdater.network

import android.content.Context
import com.raph563.cheatupdater.data.RepoConfig
import com.raph563.cheatupdater.data.UpdateCheckResult
import com.raph563.cheatupdater.data.UpdateSource
import com.raph563.cheatupdater.data.UpdateSources

class UpdateService(private val context: Context) {
    private val githubRepo = GitHubReleaseRepository(context)

    suspend fun checkForUpdates(
        source: UpdateSource,
        lastSeenTag: String?
    ): UpdateCheckResult {
        return when (source.type) {
            com.raph563.cheatupdater.data.SourceType.GITHUB -> {
                val config = RepoConfig(
                    owner = source.owner.orEmpty(),
                    repo = source.repo.orEmpty(),
                    token = source.token
                )
                githubRepo.checkForUpdates(config, lastSeenTag)
            }

            com.raph563.cheatupdater.data.SourceType.BACKEND -> {
                val baseUrl = source.backendBaseUrl
                    ?: throw IllegalStateException("Base URL backend manquante")
                BackendReleaseRepository(context, baseUrl).checkForUpdates(lastSeenTag)
            }
        }
    }

    suspend fun testConnection(source: UpdateSource): String {
        return when (source.type) {
            com.raph563.cheatupdater.data.SourceType.GITHUB -> {
                val config = RepoConfig(
                    owner = source.owner.orEmpty(),
                    repo = source.repo.orEmpty(),
                    token = source.token
                )
                githubRepo.testConnection(config)
            }

            com.raph563.cheatupdater.data.SourceType.BACKEND -> {
                val baseUrl = source.backendBaseUrl
                    ?: throw IllegalStateException("Base URL backend manquante")
                BackendReleaseRepository(context, baseUrl).testConnection(source.id)
            }
        }
    }

    fun defaultSource() = UpdateSources.default()
}
