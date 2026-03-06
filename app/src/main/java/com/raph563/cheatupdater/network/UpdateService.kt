package com.raph563.cheatupdater.network

import android.content.Context
import com.raph563.cheatupdater.data.ApkCandidate
import com.raph563.cheatupdater.data.NewsItem
import com.raph563.cheatupdater.data.RepoConfig
import com.raph563.cheatupdater.data.ReferralSummary
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
                BackendV2ReleaseRepository(context, baseUrl).checkForUpdates(lastSeenTag)
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
                BackendV2ReleaseRepository(context, baseUrl).testConnection(source.id)
            }
        }
    }

    suspend fun reportInstall(source: UpdateSource, candidate: ApkCandidate, status: String) {
        when (source.type) {
            com.raph563.cheatupdater.data.SourceType.GITHUB -> {
                // No reporting endpoint for GitHub sources.
            }

            com.raph563.cheatupdater.data.SourceType.BACKEND -> {
                val baseUrl = source.backendBaseUrl
                    ?: throw IllegalStateException("Base URL backend manquante")
                BackendV2ReleaseRepository(context, baseUrl).reportInstall(candidate, status)
            }
        }
    }

    suspend fun fetchNews(source: UpdateSource): List<NewsItem> {
        return when (source.type) {
            com.raph563.cheatupdater.data.SourceType.GITHUB -> emptyList()
            com.raph563.cheatupdater.data.SourceType.BACKEND -> {
                val baseUrl = source.backendBaseUrl
                    ?: throw IllegalStateException("Base URL backend manquante")
                BackendV2ReleaseRepository(context, baseUrl).fetchNews()
            }
        }
    }

    suspend fun fetchReferral(source: UpdateSource): ReferralSummary? {
        return when (source.type) {
            com.raph563.cheatupdater.data.SourceType.GITHUB -> null
            com.raph563.cheatupdater.data.SourceType.BACKEND -> {
                val baseUrl = source.backendBaseUrl
                    ?: throw IllegalStateException("Base URL backend manquante")
                BackendV2ReleaseRepository(context, baseUrl).fetchReferral()
            }
        }
    }

    fun defaultSource() = UpdateSources.default()
}
