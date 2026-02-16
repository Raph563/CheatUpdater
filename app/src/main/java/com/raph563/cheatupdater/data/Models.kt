package com.raph563.cheatupdater.data

import java.io.File

data class RepoConfig(
    val owner: String,
    val repo: String,
    val token: String?
)

data class GitHubAsset(
    val id: Long,
    val name: String,
    val size: Long,
    val browserDownloadUrl: String
)

data class GitHubRelease(
    val id: Long,
    val tagName: String,
    val name: String?,
    val publishedAt: String?,
    val assets: List<GitHubAsset>
)

enum class UpdateAction {
    INSTALL,
    UPDATE,
    UP_TO_DATE
}

data class ApkCandidate(
    val asset: GitHubAsset,
    val localFile: File,
    val packageName: String?,
    val archiveVersionCode: Long?,
    val installedVersionCode: Long?,
    val action: UpdateAction
)

data class UpdateCheckResult(
    val release: GitHubRelease,
    val candidates: List<ApkCandidate>,
    val isNewRelease: Boolean
)
