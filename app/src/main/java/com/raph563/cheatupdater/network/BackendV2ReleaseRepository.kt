package com.raph563.cheatupdater.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.raph563.cheatupdater.data.ApkCandidate
import com.raph563.cheatupdater.data.AppPreferences
import com.raph563.cheatupdater.data.GitHubAsset
import com.raph563.cheatupdater.data.GitHubRelease
import com.raph563.cheatupdater.data.NewsItem
import com.raph563.cheatupdater.data.ReferralSummary
import com.raph563.cheatupdater.data.UpdateAction
import com.raph563.cheatupdater.data.UpdateCheckResult
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class BackendV2ReleaseRepository(
    private val context: Context,
    baseUrl: String,
) {
    private val prefs = AppPreferences(context)
    private val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    private val sourceBaseUrl = normalizedBaseUrl.toHttpUrlOrNull()
    private val api = BackendV2ClientFactory.create(normalizedBaseUrl) { prefs.getAccessToken() }

    suspend fun checkForUpdates(lastSeenTag: String?): UpdateCheckResult {
        val request = MobileCheckRequestDto(
            device = MobileDeviceDto(
                androidIdHash = deviceHash(),
                abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
                sdkInt = Build.VERSION.SDK_INT,
                appChannel = "release",
            ),
            installedApps = collectInstalledApps(lastSeenTag)
        )
        val result = try {
            withAuthRetry { api.mobileCheck(request) }
        } catch (http: HttpException) {
            if (http.code() == 404) {
                val noRelease = GitHubRelease(
                    id = 0L,
                    tagName = "none",
                    name = "Aucune release diffusee",
                    publishedAt = null,
                    assets = emptyList(),
                )
                return UpdateCheckResult(
                    release = noRelease,
                    candidates = emptyList(),
                    isNewRelease = false,
                )
            }
            throw http
        }
        val release = GitHubRelease(
            id = result.releaseId.hashCode().toLong(),
            tagName = result.releaseId,
            name = result.releaseId,
            publishedAt = result.generatedAt,
            assets = result.apps.mapIndexed { idx, item ->
                GitHubAsset(
                    id = idx.toLong(),
                    name = item.fileName,
                    size = item.size,
                    browserDownloadUrl = resolveDownloadUrl(item.downloadUrl),
                )
            }
        )
        val releaseFolder = File(context.filesDir, "apk_cache/${sanitize(result.releaseId)}")
        if (!releaseFolder.exists()) releaseFolder.mkdirs()

        val candidates = result.apps.map { item ->
            val local = File(releaseFolder, item.fileName)
            if (!local.exists() || local.length() != item.size) {
                download(item.downloadUrl, local)
            }
            val action = mapAction(item.action)
            ApkCandidate(
                asset = GitHubAsset(
                    id = item.artifactId.hashCode().toLong(),
                    name = item.fileName,
                    size = item.size,
                    browserDownloadUrl = resolveDownloadUrl(item.downloadUrl),
                ),
                localFile = local,
                packageName = item.packageName,
                archiveVersionCode = item.versionCode,
                installedVersionCode = installedVersionCode(item.packageName),
                action = action,
                artifactId = item.artifactId,
                releaseId = result.releaseId,
                reason = item.reason,
            )
        }
        return UpdateCheckResult(
            release = release,
            candidates = candidates,
            isNewRelease = lastSeenTag == null || lastSeenTag != result.releaseId,
        )
    }

    suspend fun testConnection(sourceId: String): String {
        val me = withAuthRetry { api.me() }
        return "Connexion backend V2 OK (${sourceId}): ${me.email}"
    }

    suspend fun reportInstall(candidate: ApkCandidate, status: String) {
        val payload = MobileInstallReportDto(
            artifactId = candidate.artifactId,
            packageName = candidate.packageName ?: candidate.asset.name,
            releaseId = candidate.releaseId,
            status = status,
            deviceHash = deviceHash(),
        )
        withAuthRetry { api.mobileInstallReport(payload) }
    }

    suspend fun fetchNews(): List<NewsItem> {
        val response = withAuthRetry { api.mobileNews() }
        return response.items.map {
            NewsItem(
                id = it.id,
                title = it.title,
                bodyMarkdown = it.bodyMd,
                type = it.type,
                publishedAt = it.publishedAt,
            )
        }
    }

    suspend fun fetchReferral(): ReferralSummary {
        val me = withAuthRetry { api.referralMe() }
        val link = withAuthRetry { api.referralLink() }
        return ReferralSummary(
            referralCode = me.referralCode,
            referredTotal = me.referredTotal,
            referredValidated = me.referredValidated,
            referralLink = link.referralLink,
        )
    }

    private fun collectInstalledApps(lastSeenTag: String?): List<InstalledAppDto> {
        val pm = context.packageManager
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
        return list.mapNotNull { info ->
            val pkg = info.packageName ?: return@mapNotNull null
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            InstalledAppDto(
                packageName = pkg,
                versionCode = versionCode,
                lastInstalledReleaseId = prefs.getInstalledReleaseId(pkg) ?: lastSeenTag,
            )
        }
    }

    private fun installedVersionCode(packageName: String): Long? {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun download(url: String, output: File) {
        val response = withAuthRetry { api.downloadFile(resolveDownloadUrl(url)) }
        if (!response.isSuccessful) {
            throw IllegalStateException("Download failed: HTTP ${response.code()}")
        }
        val body = response.body() ?: throw IllegalStateException("Download body empty")
        output.outputStream().use { out ->
            body.byteStream().use { input -> input.copyTo(out) }
        }
    }

    private fun mapAction(value: String): UpdateAction {
        return when (value.uppercase()) {
            "INSTALL" -> UpdateAction.INSTALL
            "UPDATE" -> UpdateAction.UPDATE
            "REINSTALL" -> UpdateAction.REINSTALL
            else -> UpdateAction.UP_TO_DATE
        }
    }

    private fun resolveDownloadUrl(url: String): String {
        val appUrl = url.toHttpUrlOrNull() ?: return url
        val base = sourceBaseUrl ?: return url
        val host = appUrl.host.lowercase(Locale.US)
        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if (!isLoopback) return url
        return appUrl.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()
            .toString()
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    private fun deviceHash(): String {
        val raw = "${Build.BRAND}|${Build.DEVICE}|${Build.MODEL}|${Build.VERSION.SDK_INT}"
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private suspend fun <T> withAuthRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (http: HttpException) {
            if (http.code() != 401 || !refreshAccessToken()) {
                throw http
            }
            block()
        }
    }

    private suspend fun refreshAccessToken(): Boolean {
        val refresh = prefs.getRefreshToken() ?: return false
        return try {
            val tokens = api.refresh(RefreshRequestDto(refreshToken = refresh))
            prefs.setAccessToken(tokens.accessToken)
            prefs.setRefreshToken(tokens.refreshToken)
            true
        } catch (_: Exception) {
            false
        }
    }
}
