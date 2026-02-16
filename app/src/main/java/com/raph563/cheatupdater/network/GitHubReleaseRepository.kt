package com.raph563.cheatupdater.network

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.raph563.cheatupdater.data.ApkCandidate
import com.raph563.cheatupdater.data.GitHubAsset
import com.raph563.cheatupdater.data.GitHubRelease
import com.raph563.cheatupdater.data.RepoConfig
import com.raph563.cheatupdater.data.UpdateAction
import com.raph563.cheatupdater.data.UpdateCheckResult
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.IOException
import java.util.Locale

class GitHubReleaseRepository(private val context: Context) {
    private val packageManager: PackageManager = context.packageManager

    private val api: GitHubApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CheatUpdater/1.0")
                    .build()
                chain.proceed(req)
            }
            .build()

        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(GitHubApi::class.java)
    }

    suspend fun checkForUpdates(
        config: RepoConfig,
        lastSeenTag: String?
    ): UpdateCheckResult {
        require(config.owner.isNotBlank()) { "Owner GitHub requis." }
        require(config.repo.isNotBlank()) { "Repo GitHub requis." }

        val releaseDto = api.getLatestRelease(
            owner = config.owner.trim(),
            repo = config.repo.trim(),
            authorization = config.token.toAuthHeader()
        )
        val release = releaseDto.toDomain()
        val releaseFolder = File(context.filesDir, "apk_cache/${sanitize(release.tagName)}")
        if (!releaseFolder.exists()) {
            releaseFolder.mkdirs()
        }

        val candidates = release.assets
            .filter { it.name.lowercase(Locale.US).endsWith(".apk") }
            .map { asset ->
                val localFile = File(releaseFolder, asset.name)
                if (!localFile.exists() || localFile.length() != asset.size) {
                    downloadAsset(asset, localFile, config.token.toAuthHeader())
                }
                buildCandidate(asset, localFile)
            }

        return UpdateCheckResult(
            release = release,
            candidates = candidates,
            isNewRelease = lastSeenTag == null || lastSeenTag != release.tagName
        )
    }

    private fun buildCandidate(asset: GitHubAsset, localFile: File): ApkCandidate {
        val archiveInfo = getArchivePackageInfo(localFile)
        val packageName = archiveInfo?.packageName
        val archiveVersionCode = archiveInfo?.longVersionCodeSafe()
        val installedVersionCode = packageName?.let { getInstalledVersionCode(it) }
        val action = when {
            installedVersionCode == null -> UpdateAction.INSTALL
            archiveVersionCode == null -> UpdateAction.INSTALL
            archiveVersionCode > installedVersionCode -> UpdateAction.UPDATE
            else -> UpdateAction.UP_TO_DATE
        }
        return ApkCandidate(
            asset = asset,
            localFile = localFile,
            packageName = packageName,
            archiveVersionCode = archiveVersionCode,
            installedVersionCode = installedVersionCode,
            action = action
        )
    }

    private suspend fun downloadAsset(asset: GitHubAsset, outputFile: File, authHeader: String?) {
        val response = api.downloadFile(asset.browserDownloadUrl, authHeader)
        if (!response.isSuccessful) {
            throw IOException("Echec telechargement ${asset.name}: HTTP ${response.code()}")
        }
        val body = response.body() ?: throw IOException("Body vide pour ${asset.name}")
        outputFile.outputStream().use { output ->
            body.byteStream().use { input ->
                input.copyTo(output)
            }
        }
    }

    private fun getArchivePackageInfo(file: File): PackageInfo? {
        val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }
        archiveInfo?.applicationInfo?.sourceDir = file.absolutePath
        archiveInfo?.applicationInfo?.publicSourceDir = file.absolutePath
        return archiveInfo
    }

    private fun getInstalledVersionCode(packageName: String): Long? {
        val installedInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        return installedInfo?.longVersionCodeSafe()
    }

    private fun String?.toAuthHeader(): String? {
        if (this.isNullOrBlank()) return null
        return "Bearer ${this.trim()}"
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeSafe(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
}

private fun GitHubReleaseDto.toDomain(): GitHubRelease {
    return GitHubRelease(
        id = id,
        tagName = tagName,
        name = name,
        publishedAt = publishedAt,
        assets = assets.map {
            GitHubAsset(
                id = it.id,
                name = it.name,
                size = it.size,
                browserDownloadUrl = it.browserDownloadUrl
            )
        }
    )
}
