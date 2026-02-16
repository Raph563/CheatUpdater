package com.raph563.cheatupdater.network

import com.squareup.moshi.Json
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Streaming
import retrofit2.http.Url

interface GitHubApi {
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Header("Authorization") authorization: String?
    ): GitHubReleaseDto

    @Streaming
    @GET
    suspend fun downloadFile(
        @Url downloadUrl: String,
        @Header("Authorization") authorization: String?
    ): Response<ResponseBody>

    @GET("repos/{owner}/{repo}")
    suspend fun getRepository(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Header("Authorization") authorization: String?
    ): GitHubRepositoryDto

    @GET("repos/{owner}/{repo}/tags")
    suspend fun getTags(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Header("Authorization") authorization: String?
    ): List<GitHubTagDto>
}

data class GitHubReleaseDto(
    @Json(name = "id") val id: Long,
    @Json(name = "tag_name") val tagName: String,
    @Json(name = "name") val name: String?,
    @Json(name = "published_at") val publishedAt: String?,
    @Json(name = "assets") val assets: List<GitHubAssetDto>
)

data class GitHubAssetDto(
    @Json(name = "id") val id: Long,
    @Json(name = "name") val name: String,
    @Json(name = "size") val size: Long,
    @Json(name = "browser_download_url") val browserDownloadUrl: String
)

data class GitHubRepositoryDto(
    @Json(name = "full_name") val fullName: String,
    @Json(name = "default_branch") val defaultBranch: String?
)

data class GitHubTagDto(
    @Json(name = "name") val name: String
)
