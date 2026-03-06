package com.raph563.cheatupdater.data

data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
)

data class UserProfile(
    val userId: String,
    val email: String,
    val role: String,
    val isVerified: Boolean,
    val referralCode: String?,
)

data class NewsItem(
    val id: Int,
    val title: String,
    val bodyMarkdown: String,
    val type: String,
    val publishedAt: String,
)

data class ReferralSummary(
    val referralCode: String,
    val referredTotal: Int,
    val referredValidated: Int,
    val referralLink: String? = null,
)

