package com.raph563.cheatupdater.network

import android.content.Context
import android.os.Build
import com.raph563.cheatupdater.data.AppPreferences
import com.raph563.cheatupdater.data.SessionTokens
import com.raph563.cheatupdater.data.UserProfile
import java.security.MessageDigest

class AuthRepository(
    context: Context,
    baseUrl: String,
) {
    private val prefs = AppPreferences(context)
    private val api = BackendV2ClientFactory.create(baseUrl) { prefs.getAccessToken() }

    suspend fun register(email: String, password: String, referralCode: String): String {
        val response = api.register(
            RegisterRequestDto(
                email = email.trim(),
                password = password,
                referralCode = referralCode.trim(),
                deviceHash = deviceHash(),
            )
        )
        return response.message ?: "Compte créé"
    }

    suspend fun verifyEmail(token: String): String {
        val response = api.verifyEmail(VerifyEmailRequestDto(token = token.trim()))
        return response.message ?: "Email vérifié"
    }

    suspend fun login(email: String, password: String): SessionTokens {
        val payload = LoginRequestDto(
            email = email.trim(),
            password = password,
            deviceMeta = mapOf(
                "model" to Build.MODEL,
                "manufacturer" to Build.MANUFACTURER,
                "sdkInt" to Build.VERSION.SDK_INT,
            )
        )
        val response = api.login(payload)
        prefs.setAccessToken(response.accessToken)
        prefs.setRefreshToken(response.refreshToken)
        return SessionTokens(response.accessToken, response.refreshToken, response.expiresIn)
    }

    suspend fun refresh(): SessionTokens {
        val refresh = prefs.getRefreshToken() ?: throw IllegalStateException("Refresh token missing")
        val response = api.refresh(RefreshRequestDto(refreshToken = refresh))
        prefs.setAccessToken(response.accessToken)
        prefs.setRefreshToken(response.refreshToken)
        return SessionTokens(response.accessToken, response.refreshToken, response.expiresIn)
    }

    suspend fun me(): UserProfile {
        val response = api.me()
        prefs.setUserProfile(
            userId = response.userId,
            email = response.email,
            role = response.role,
            referralCode = response.referralCode,
        )
        return UserProfile(
            userId = response.userId,
            email = response.email,
            role = response.role,
            isVerified = response.isVerified,
            referralCode = response.referralCode,
        )
    }

    suspend fun requestPasswordReset(email: String): String {
        val response = api.requestReset(RequestResetDto(email = email.trim()))
        return response.message ?: "Demande envoyée"
    }

    suspend fun resetPassword(token: String, newPassword: String): String {
        val response = api.resetPassword(ResetPasswordDto(token = token.trim(), newPassword = newPassword))
        return response.message ?: "Mot de passe mis à jour"
    }

    fun hasSession(): Boolean = !prefs.getAccessToken().isNullOrBlank()

    fun logout() {
        prefs.clearSession()
    }

    private fun deviceHash(): String {
        val raw = "${Build.BRAND}|${Build.DEVICE}|${Build.MODEL}|${Build.VERSION.SDK_INT}"
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

