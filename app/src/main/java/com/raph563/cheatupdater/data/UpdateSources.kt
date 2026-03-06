package com.raph563.cheatupdater.data

enum class SourceType {
    BACKEND,
    GITHUB
}

data class UpdateSource(
    val id: String,
    val displayName: String,
    val type: SourceType,
    val backendBaseUrl: String? = null,
    val owner: String? = null,
    val repo: String? = null,
    val token: String? = null
)

object UpdateSources {
    val all: List<UpdateSource> = listOf(
        UpdateSource(
            id = "backend_prod",
            displayName = "Backend CheatUpdater V2 (VPS)",
            type = SourceType.BACKEND,
            backendBaseUrl = "http://207.180.235.68:8088/"
        ),
        UpdateSource(
            id = "backend_main",
            displayName = "Backend CheatUpdater V2 (local)",
            type = SourceType.BACKEND,
            backendBaseUrl = "http://10.0.2.2:8088/"
        ),
        UpdateSource(
            id = "backend_lan",
            displayName = "Backend CheatUpdater V2 (LAN Wi-Fi)",
            type = SourceType.BACKEND,
            backendBaseUrl = "http://192.168.0.56:8088/"
        )
    )

    fun default(): UpdateSource = findById("backend_prod") ?: all.first()

    fun findById(id: String?): UpdateSource? =
        all.firstOrNull { it.id == id }
}
