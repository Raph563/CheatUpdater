package com.raph563.cheatupdater.ui

import android.app.Application
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.raph563.cheatupdater.data.AppPreferences
import com.raph563.cheatupdater.data.RepoConfig
import com.raph563.cheatupdater.data.UpdateAction
import com.raph563.cheatupdater.network.GitHubReleaseRepository
import com.raph563.cheatupdater.notifier.NotificationHelper
import com.raph563.cheatupdater.updater.ApkInstaller
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class CandidateUi(
    val assetName: String,
    val packageName: String?,
    val localPath: String,
    val action: UpdateAction,
    val installedVersionCode: Long?,
    val archiveVersionCode: Long?
)

data class MainUiState(
    val owner: String = "",
    val repo: String = "",
    val token: String = "",
    val isChecking: Boolean = false,
    val status: String = "Pret.",
    val releaseTag: String? = null,
    val candidates: List<CandidateUi> = emptyList()
)

sealed interface UiEvent {
    data class PromptUninstall(val packageName: String, val apkPath: String) : UiEvent
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = AppPreferences(application)
    private val repository = GitHubReleaseRepository(application)
    private val installer = ApkInstaller(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val pendingReinstallPathByPackage = mutableMapOf<String, String>()

    init {
        val config = prefs.loadRepoConfig()
        _uiState.update {
            it.copy(
                owner = config.owner,
                repo = config.repo,
                token = config.token.orEmpty()
            )
        }
    }

    fun onOwnerChanged(value: String) = _uiState.update { it.copy(owner = value) }
    fun onRepoChanged(value: String) = _uiState.update { it.copy(repo = value) }
    fun onTokenChanged(value: String) = _uiState.update { it.copy(token = value) }

    fun saveConfig() {
        val state = _uiState.value
        prefs.saveRepoConfig(
            RepoConfig(
                owner = state.owner,
                repo = state.repo,
                token = state.token.ifBlank { null }
            )
        )
        _uiState.update { it.copy(status = "Configuration GitHub enregistree.") }
    }

    fun checkUpdates() {
        if (_uiState.value.isChecking) return
        viewModelScope.launch {
            val state = _uiState.value
            val config = RepoConfig(
                owner = state.owner.trim(),
                repo = state.repo.trim(),
                token = state.token.trim().ifBlank { null }
            )
            if (config.owner.isBlank() || config.repo.isBlank()) {
                _uiState.update {
                    it.copy(status = "Renseigne owner/repo GitHub avant de verifier.")
                }
                return@launch
            }
            _uiState.update { it.copy(isChecking = true, status = "Verification des mises a jour...") }
            runCatching {
                repository.checkForUpdates(config, prefs.getLastSeenTag())
            }.onSuccess { result ->
                if (result.isNewRelease) {
                    prefs.setLastSeenTag(result.release.tagName)
                }
                val candidates = result.candidates.map { candidate ->
                    CandidateUi(
                        assetName = candidate.asset.name,
                        packageName = candidate.packageName,
                        localPath = candidate.localFile.absolutePath,
                        action = candidate.action,
                        installedVersionCode = candidate.installedVersionCode,
                        archiveVersionCode = candidate.archiveVersionCode
                    )
                }
                val actionable = candidates.count {
                    it.action == UpdateAction.INSTALL || it.action == UpdateAction.UPDATE
                }
                if (actionable > 0) {
                    NotificationHelper.showUpdatesAvailable(
                        getApplication(),
                        actionable,
                        result.release.tagName
                    )
                }
                _uiState.update {
                    it.copy(
                        isChecking = false,
                        releaseTag = result.release.tagName,
                        candidates = candidates,
                        status = if (actionable == 0) {
                            "Aucune mise a jour necessaire."
                        } else {
                            "$actionable APK actionnables detectes."
                        }
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isChecking = false,
                        status = "Echec check update: ${error.message}"
                    )
                }
            }
        }
    }

    fun installCandidate(candidate: CandidateUi) {
        val targetPackage = candidate.packageName
        if (targetPackage != null) {
            pendingReinstallPathByPackage[targetPackage] = candidate.localPath
        }
        viewModelScope.launch {
            runCatching {
                installer.install(File(candidate.localPath), targetPackage)
            }.onSuccess {
                _uiState.update {
                    it.copy(status = "Demande d'installation envoyee: ${candidate.assetName}")
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(status = "Impossible de lancer l'installation: ${error.message}")
                }
            }
        }
    }

    fun onInstallResult(status: Int, packageName: String?, filePath: String?, statusMessage: String?) {
        viewModelScope.launch {
            if (status == PackageInstaller.STATUS_SUCCESS) {
                _uiState.update {
                    it.copy(status = "Installation reussie pour ${packageName ?: "package inconnu"}.")
                }
                checkUpdates()
                return@launch
            }

            val pkg = packageName
            val resolvedPath = when {
                !filePath.isNullOrBlank() -> filePath
                !pkg.isNullOrBlank() -> pendingReinstallPathByPackage[pkg]
                else -> null
            }
            val shouldPromptUninstall = !pkg.isNullOrBlank() && isPackageInstalled(pkg)
            if (!pkg.isNullOrBlank() && !resolvedPath.isNullOrBlank()) {
                pendingReinstallPathByPackage[pkg] = resolvedPath
            }
            if (shouldPromptUninstall && !pkg.isNullOrBlank() && !resolvedPath.isNullOrBlank()) {
                _events.emit(UiEvent.PromptUninstall(pkg, resolvedPath))
                _uiState.update {
                    it.copy(status = "Mise a jour echouee. Demande de desinstallation en cours pour $pkg.")
                }
            } else {
                _uiState.update {
                    it.copy(
                        status = "Installation echouee: ${statusMessage ?: "motif inconnu"}"
                    )
                }
            }
        }
    }

    fun onUninstallCompleted(packageName: String) {
        viewModelScope.launch {
            if (isPackageInstalled(packageName)) {
                _uiState.update {
                    it.copy(status = "Desinstallation non terminee pour $packageName.")
                }
                return@launch
            }
            val path = pendingReinstallPathByPackage[packageName]
            if (path.isNullOrBlank()) {
                _uiState.update {
                    it.copy(status = "Desinstallation terminee. Relance l'installation depuis la liste.")
                }
                return@launch
            }
            runCatching {
                installer.install(File(path), packageName)
            }.onSuccess {
                _uiState.update {
                    it.copy(status = "Desinstallation ok. Reinstallation demandee pour $packageName.")
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(status = "Reinstallation impossible: ${error.message}")
                }
            }
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            val pm = getApplication<Application>().packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
