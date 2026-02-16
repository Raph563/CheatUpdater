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
            id = "github_cheatupdater",
            displayName = "GitHub Releases (Raph563/CheatUpdater, anti-403)",
            type = SourceType.GITHUB,
            owner = "Raph563",
            repo = "CheatUpdater"
        ),
        UpdateSource(
            id = "backend_main",
            displayName = "Backend Docker (local)",
            type = SourceType.BACKEND,
            backendBaseUrl = "http://10.0.2.2:8088/"
        ),
        UpdateSource(
            id = "backend_lan",
            displayName = "Backend Docker (LAN Wi-Fi)",
            type = SourceType.BACKEND,
            backendBaseUrl = "http://192.168.0.56:8088/"
        ),
        UpdateSource(
            id = "github_debug",
            displayName = "GitHub Debug (inotia00/rvx-builder)",
            type = SourceType.GITHUB,
            owner = "inotia00",
            repo = "rvx-builder"
        )
    )

    fun default(): UpdateSource = findById("github_cheatupdater") ?: all.first()

    fun findById(id: String?): UpdateSource? =
        all.firstOrNull { it.id == id }
}
