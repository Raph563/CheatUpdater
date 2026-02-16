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
import com.raph563.cheatupdater.data.UpdateAction
import com.raph563.cheatupdater.data.UpdateCheckResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import retrofit2.HttpException
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.IOException
import java.util.Locale

class BackendReleaseRepository(
    private val context: Context,
    baseUrl: String
) {
    private val packageManager: PackageManager = context.packageManager
    private val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    private val sourceBaseUrl = normalizedBaseUrl.toHttpUrlOrNull()

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("Accept", "application/json")
                .header("User-Agent", "CheatUpdater/1.0")
                .build()
            chain.proceed(req)
        }
        .build()

    private val api: BackendApi by lazy {
        val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

        Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(BackendApi::class.java)
    }

    suspend fun checkForUpdates(lastSeenTag: String?): UpdateCheckResult {
        val backendRelease = try {
            api.getCurrentRelease()
        } catch (http: HttpException) {
            if (http.code() == 404) {
                return UpdateCheckResult(
                    release = GitHubRelease(
                        id = 0L,
                        tagName = "no-release",
                        name = "no-release",
                        publishedAt = null,
                        assets = emptyList()
                    ),
                    candidates = emptyList(),
                    isNewRelease = false
                )
            }
            throw http
        }
        val domainRelease = backendRelease.toDomain()
        val releaseFolder = File(context.filesDir, "apk_cache/${sanitize(domainRelease.tagName)}")
        if (!releaseFolder.exists()) {
            releaseFolder.mkdirs()
        }

        val candidates = backendRelease.apps.map { app ->
            val resolvedDownloadUrl = resolveDownloadUrl(app.downloadUrl)
            val localFile = File(releaseFolder, app.fileName)
            if (!localFile.exists() || (app.size != null && localFile.length() != app.size)) {
                download(resolvedDownloadUrl, localFile)
            }
            buildCandidate(
                asset = GitHubAsset(
                    id = localFile.hashCode().toLong(),
                    name = app.fileName,
                    size = app.size ?: localFile.length(),
                    browserDownloadUrl = resolvedDownloadUrl
                ),
                localFile = localFile
            )
        }

        return UpdateCheckResult(
            release = domainRelease,
            candidates = candidates,
            isNewRelease = lastSeenTag == null || lastSeenTag != domainRelease.tagName
        )
    }

    suspend fun testConnection(sourceId: String): String {
        val debug = api.debugSource(sourceId)
        return if (debug.ok) {
            val details = listOfNotNull(debug.message, debug.tag, debug.release).joinToString(" / ")
            "Connexion OK${if (details.isBlank()) "" else ": $details"}"
        } else {
            "Connexion KO: ${debug.message ?: "source indisponible"}"
        }
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

    private fun download(url: String, output: File) {
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Echec telechargement backend: HTTP ${response.code}")
        }
        val body = response.body ?: throw IOException("Body vide lors du telechargement")
        output.outputStream().use { stream ->
            body.byteStream().use { input -> input.copyTo(stream) }
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

    private fun sanitize(value: String): String =
        value.lowercase(Locale.US).replace(Regex("[^a-zA-Z0-9._-]"), "_")

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

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeSafe(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
}

private fun BackendReleaseDto.toDomain(): GitHubRelease {
    return GitHubRelease(
        id = releaseId.hashCode().toLong(),
        tagName = tag,
        name = releaseId,
        publishedAt = publishedAt,
        assets = apps.mapIndexed { index, item ->
            GitHubAsset(
                id = index.toLong(),
                name = item.fileName,
                size = item.size ?: 0L,
                browserDownloadUrl = item.downloadUrl
            )
        }
    )
}
