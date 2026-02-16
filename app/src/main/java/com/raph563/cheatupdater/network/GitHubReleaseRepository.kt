package com.raph563.cheatupdater.network

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.raph563.cheatupdater.data.ApkCandidate
import com.raph563.cheatupdater.data.GitHubAsset
import com.raph563.cheatupdater.data.GitHubRelease
import com.raph563.cheatupdater.data.RepoConfig
import com.raph563.cheatupdater.data.UpdateAction
import com.raph563.cheatupdater.data.UpdateCheckResult
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class GitHubReleaseRepository(private val context: Context) {
    private val packageManager: PackageManager = context.packageManager

    private val apiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CheatUpdater/1.0")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    private val webClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", "CheatUpdater/1.0")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    private val api: GitHubApi by lazy {
        val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(apiClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GitHubApi::class.java)
    }

    suspend fun checkForUpdates(
        config: RepoConfig,
        lastSeenTag: String?
    ): UpdateCheckResult {
        require(config.owner.isNotBlank()) { "Owner GitHub requis." }
        require(config.repo.isNotBlank()) { "Repo GitHub requis." }

        val owner = config.owner.trim()
        val repo = config.repo.trim()
        val release = getLatestRelease(owner, repo, config.token.toAuthHeader())
        val releaseFolder = File(context.filesDir, "apk_cache/${sanitize(release.tagName)}")
        if (!releaseFolder.exists()) {
            releaseFolder.mkdirs()
        }

        val candidates = release.assets
            .filter { it.name.lowercase(Locale.US).endsWith(".apk") }
            .map { asset ->
                val localFile = File(releaseFolder, asset.name)
                val hasKnownSize = asset.size > 0
                if (!localFile.exists() || (hasKnownSize && localFile.length() != asset.size)) {
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

    suspend fun testConnection(config: RepoConfig): String {
        require(config.owner.isNotBlank()) { "Owner GitHub requis." }
        require(config.repo.isNotBlank()) { "Repo GitHub requis." }
        val owner = config.owner.trim()
        val repo = config.repo.trim()
        val auth = config.token.toAuthHeader()

        return try {
            val releaseDto = api.getLatestRelease(
                owner = owner,
                repo = repo,
                authorization = auth
            )
            "Connexion GitHub OK: release ${releaseDto.tagName}"
        } catch (http: HttpException) {
            if (http.code() == 403 || http.code() == 429) {
                val webRelease = getLatestReleaseFromWeb(owner, repo)
                return "Connexion GitHub OK (fallback web): release ${webRelease.tagName}"
            }
            if (http.code() != 404) throw http
            val repository = api.getRepository(
                owner = owner,
                repo = repo,
                authorization = auth
            )
            val tags = runCatching {
                api.getTags(owner = owner, repo = repo, authorization = auth)
            }.getOrDefault(emptyList())
            val latestTag = tags.firstOrNull()?.name
            if (latestTag.isNullOrBlank()) {
                "Connexion GitHub OK: ${repository.fullName} (aucune release publiee)"
            } else {
                "Connexion GitHub OK: ${repository.fullName} (dernier tag: $latestTag)"
            }
        }
    }

    private suspend fun getLatestRelease(owner: String, repo: String, auth: String?): GitHubRelease {
        val releaseDto = try {
            api.getLatestRelease(
                owner = owner,
                repo = repo,
                authorization = auth
            )
        } catch (http: HttpException) {
            if (http.code() == 404) {
                return GitHubRelease(
                    id = 0L,
                    tagName = "no-release",
                    name = "$owner/$repo",
                    publishedAt = null,
                    assets = emptyList()
                )
            }
            if (http.code() == 403 || http.code() == 429) {
                return getLatestReleaseFromWeb(owner, repo)
            }
            throw http
        }
        return releaseDto.toDomain()
    }

    private fun getLatestReleaseFromWeb(owner: String, repo: String): GitHubRelease {
        val latestUrl = "https://github.com/$owner/$repo/releases/latest"
        val request = Request.Builder()
            .url(latestUrl)
            .get()
            .build()
        webClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Fallback web GitHub KO: HTTP ${response.code}")
            }
            val html = response.body?.string().orEmpty()
            val finalUrl = response.request.url.toString()
            val tag = extractTag(finalUrl, html, owner, repo) ?: "web-latest"
            val directAssets = extractApkAssets(owner, repo, html)
            val assets = if (directAssets.isNotEmpty()) {
                directAssets
            } else {
                fetchApkAssetsFromExpanded(owner, repo, tag)
            }
            return GitHubRelease(
                id = finalUrl.hashCode().toLong(),
                tagName = tag,
                name = "$owner/$repo",
                publishedAt = null,
                assets = assets
            )
        }
    }

    private fun fetchApkAssetsFromExpanded(owner: String, repo: String, tag: String): List<GitHubAsset> {
        val encodedTag = URLEncoder.encode(tag, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val assetsUrl = "https://github.com/$owner/$repo/releases/expanded_assets/$encodedTag"
        val request = Request.Builder()
            .url(assetsUrl)
            .get()
            .build()
        webClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Fallback web assets KO: HTTP ${response.code}")
            }
            val html = response.body?.string().orEmpty()
            return extractApkAssets(owner, repo, html)
        }
    }

    private fun extractTag(finalUrl: String, html: String, owner: String, repo: String): String? {
        val fromUrl = Regex("/releases/tag/([^/?#]+)")
            .find(finalUrl)
            ?.groupValues
            ?.getOrNull(1)
        if (!fromUrl.isNullOrBlank()) {
            return decodeSegment(fromUrl)
        }
        val tagPattern = Regex(
            "/${Regex.escape(owner)}/${Regex.escape(repo)}/releases/tag/([^\"'<>?#]+)"
        )
        val fromHtml = tagPattern.find(html)?.groupValues?.getOrNull(1)
        return fromHtml?.let { decodeSegment(it) }
    }

    private fun extractApkAssets(owner: String, repo: String, html: String): List<GitHubAsset> {
        val escapedOwner = Regex.escape(owner)
        val escapedRepo = Regex.escape(repo)
        val pattern = Regex(
            "/$escapedOwner/$escapedRepo/releases/download/([^\"'<>?#]+)/([^\"'<>?#]+\\.apk)"
        )
        val dedup = linkedMapOf<String, GitHubAsset>()
        var id = 1L
        pattern.findAll(html).forEach { match ->
            val rawTag = match.groupValues[1]
            val rawFileName = match.groupValues[2]
            val fileName = decodeSegment(rawFileName)
            val downloadUrl = "https://github.com/$owner/$repo/releases/download/$rawTag/$rawFileName"
            if (!dedup.containsKey(fileName)) {
                dedup[fileName] = GitHubAsset(
                    id = id++,
                    name = fileName,
                    size = -1L,
                    browserDownloadUrl = downloadUrl
                )
            }
        }
        return dedup.values.toList()
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

    private fun decodeSegment(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

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
