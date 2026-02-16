package com.raph563.cheatupdater.network

import com.squareup.moshi.Json
import retrofit2.http.GET
import retrofit2.http.Path

interface BackendApi {
    @GET("mobile/current")
    suspend fun getCurrentRelease(): BackendReleaseDto

    @GET("mobile/debug/repository/{sourceId}")
    suspend fun debugSource(
        @Path("sourceId") sourceId: String
    ): BackendDebugDto
}

data class BackendReleaseDto(
    @Json(name = "releaseId") val releaseId: String,
    @Json(name = "tag") val tag: String,
    @Json(name = "publishedAt") val publishedAt: String?,
    @Json(name = "apps") val apps: List<BackendAppDto>
)

data class BackendAppDto(
    @Json(name = "packageName") val packageName: String,
    @Json(name = "appName") val appName: String?,
    @Json(name = "version") val version: String?,
    @Json(name = "fileName") val fileName: String,
    @Json(name = "downloadUrl") val downloadUrl: String,
    @Json(name = "sha256") val sha256: String?,
    @Json(name = "size") val size: Long?
)

data class BackendDebugDto(
    @Json(name = "ok") val ok: Boolean,
    @Json(name = "message") val message: String?,
    @Json(name = "tag") val tag: String?,
    @Json(name = "release") val release: String?
)
