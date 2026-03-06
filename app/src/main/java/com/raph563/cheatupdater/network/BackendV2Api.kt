package com.raph563.cheatupdater.network

import com.squareup.moshi.Json
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming
import retrofit2.http.Url

interface BackendV2Api {
    @POST("api/v1/auth/register")
    suspend fun register(@Body payload: RegisterRequestDto): SimpleMessageDto

    @POST("api/v1/auth/verify-email")
    suspend fun verifyEmail(@Body payload: VerifyEmailRequestDto): SimpleMessageDto

    @POST("api/v1/auth/login")
    suspend fun login(@Body payload: LoginRequestDto): TokensDto

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body payload: RefreshRequestDto): TokensDto

    @POST("api/v1/auth/password/request-reset")
    suspend fun requestReset(@Body payload: RequestResetDto): SimpleMessageDto

    @POST("api/v1/auth/password/reset")
    suspend fun resetPassword(@Body payload: ResetPasswordDto): SimpleMessageDto

    @GET("api/v1/auth/me")
    suspend fun me(): AuthMeDto

    @POST("api/v1/mobile/check")
    suspend fun mobileCheck(@Body payload: MobileCheckRequestDto): MobileCheckResponseDto

    @POST("api/v1/mobile/install-report")
    suspend fun mobileInstallReport(@Body payload: MobileInstallReportDto): SimpleMessageDto

    @GET("api/v1/mobile/news")
    suspend fun mobileNews(): NewsListDto

    @GET("api/v1/mobile/referral/me")
    suspend fun referralMe(): ReferralMeDto

    @GET("api/v1/mobile/referral/link")
    suspend fun referralLink(): ReferralLinkDto

    @Streaming
    @GET
    suspend fun downloadFile(@Url url: String): Response<ResponseBody>
}

data class RegisterRequestDto(
    @Json(name = "email") val email: String,
    @Json(name = "password") val password: String,
    @Json(name = "referral_code") val referralCode: String,
    @Json(name = "device_hash") val deviceHash: String,
)

data class VerifyEmailRequestDto(
    @Json(name = "token") val token: String,
)

data class LoginRequestDto(
    @Json(name = "email") val email: String,
    @Json(name = "password") val password: String,
    @Json(name = "device_meta") val deviceMeta: Map<String, Any>,
)

data class RefreshRequestDto(
    @Json(name = "refresh_token") val refreshToken: String,
)

data class RequestResetDto(
    @Json(name = "email") val email: String,
)

data class ResetPasswordDto(
    @Json(name = "token") val token: String,
    @Json(name = "new_password") val newPassword: String,
)

data class TokensDto(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "token_type") val tokenType: String,
    @Json(name = "expires_in") val expiresIn: Int,
)

data class AuthMeDto(
    @Json(name = "user_id") val userId: String,
    @Json(name = "email") val email: String,
    @Json(name = "role") val role: String,
    @Json(name = "is_verified") val isVerified: Boolean,
    @Json(name = "referral_code") val referralCode: String?,
)

data class SimpleMessageDto(
    @Json(name = "ok") val ok: Boolean = false,
    @Json(name = "message") val message: String? = null,
    @Json(name = "verify_token_debug") val verifyTokenDebug: String? = null,
)

data class MobileCheckRequestDto(
    @Json(name = "device") val device: MobileDeviceDto,
    @Json(name = "installedApps") val installedApps: List<InstalledAppDto>,
)

data class MobileDeviceDto(
    @Json(name = "androidIdHash") val androidIdHash: String,
    @Json(name = "abis") val abis: List<String>,
    @Json(name = "sdkInt") val sdkInt: Int,
    @Json(name = "appChannel") val appChannel: String,
)

data class InstalledAppDto(
    @Json(name = "packageName") val packageName: String,
    @Json(name = "versionCode") val versionCode: Long?,
    @Json(name = "lastInstalledReleaseId") val lastInstalledReleaseId: String?,
)

data class MobileCheckResponseDto(
    @Json(name = "releaseId") val releaseId: String,
    @Json(name = "generatedAt") val generatedAt: String,
    @Json(name = "apps") val apps: List<MobileDecisionDto>,
)

data class MobileDecisionDto(
    @Json(name = "packageName") val packageName: String,
    @Json(name = "appName") val appName: String?,
    @Json(name = "versionName") val versionName: String?,
    @Json(name = "versionCode") val versionCode: Long?,
    @Json(name = "artifactId") val artifactId: String,
    @Json(name = "fileName") val fileName: String,
    @Json(name = "downloadUrl") val downloadUrl: String,
    @Json(name = "sha256") val sha256: String?,
    @Json(name = "size") val size: Long,
    @Json(name = "supportedAbis") val supportedAbis: List<String>,
    @Json(name = "action") val action: String,
    @Json(name = "reason") val reason: String,
)

data class MobileInstallReportDto(
    @Json(name = "artifactId") val artifactId: String?,
    @Json(name = "packageName") val packageName: String,
    @Json(name = "releaseId") val releaseId: String?,
    @Json(name = "status") val status: String,
    @Json(name = "deviceHash") val deviceHash: String?,
)

data class NewsListDto(
    @Json(name = "items") val items: List<NewsItemDto> = emptyList(),
)

data class NewsItemDto(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String,
    @Json(name = "body_md") val bodyMd: String,
    @Json(name = "type") val type: String,
    @Json(name = "visibility") val visibility: String,
    @Json(name = "published_at") val publishedAt: String,
    @Json(name = "updated_at") val updatedAt: String,
)

data class ReferralMeDto(
    @Json(name = "referral_code") val referralCode: String,
    @Json(name = "referred_total") val referredTotal: Int,
    @Json(name = "referred_validated") val referredValidated: Int,
)

data class ReferralLinkDto(
    @Json(name = "referral_code") val referralCode: String,
    @Json(name = "referral_link") val referralLink: String,
)
