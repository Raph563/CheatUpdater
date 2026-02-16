package com.raph563.cheatupdater.ui

import android.app.Application
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.raph563.cheatupdater.data.AppPreferences
import com.raph563.cheatupdater.data.UpdateAction
import com.raph563.cheatupdater.data.UpdateSource
import com.raph563.cheatupdater.data.UpdateSources
import com.raph563.cheatupdater.network.UpdateService
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
    val availableSources: List<UpdateSource> = UpdateSources.all,
    val selectedSourceId: String = UpdateSources.default().id,
    val isChecking: Boolean = false,
    val isTestingSource: Boolean = false,
    val isFunctionalChecking: Boolean = false,
    val status: String = "Pret.",
    val debugStatus: String = "Aucun test execute.",
    val functionalCheckStatus: String = "Aucun check fonctionnel execute.",
    val releaseTag: String? = null,
    val candidates: List<CandidateUi> = emptyList()
)

sealed interface UiEvent {
    data class PromptUninstall(val packageName: String, val apkPath: String) : UiEvent
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = AppPreferences(application)
    private val updateService = UpdateService(application)
    private val installer = ApkInstaller(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val pendingReinstallPathByPackage = mutableMapOf<String, String>()

    init {
        val sourceId = prefs.getSelectedSourceId()
        val resolved = UpdateSources.findById(sourceId) ?: UpdateSources.default()
        _uiState.update {
            it.copy(selectedSourceId = resolved.id)
        }
    }

    fun onSourceSelected(sourceId: String) {
        val resolved = UpdateSources.findById(sourceId) ?: return
        _uiState.update { it.copy(selectedSourceId = resolved.id) }
    }

    fun saveConfig() {
        val sourceId = _uiState.value.selectedSourceId
        prefs.setSelectedSourceId(sourceId)
        _uiState.update {
            it.copy(status = "Source enregistree: ${selectedSource().displayName}")
        }
    }

    fun checkUpdates() {
        if (_uiState.value.isChecking) return
        viewModelScope.launch {
            val source = selectedSource()
            _uiState.update { it.copy(isChecking = true, status = "Verification des mises a jour...") }
            runCatching {
                updateService.checkForUpdates(source, prefs.getLastSeenTag(source.id))
            }.onSuccess { result ->
                if (result.isNewRelease) {
                    prefs.setLastSeenTag(source.id, result.release.tagName)
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

    fun testSourceConnection() {
        if (_uiState.value.isTestingSource) return
        viewModelScope.launch {
            val source = selectedSource()
            _uiState.update { it.copy(isTestingSource = true, debugStatus = "Test en cours...") }
            runCatching {
                updateService.testConnection(source)
            }.onSuccess { info ->
                _uiState.update {
                    it.copy(
                        isTestingSource = false,
                        debugStatus = info
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isTestingSource = false,
                        debugStatus = "Test KO: ${error.message}"
                    )
                }
            }
        }
    }

    fun runFunctionalAppCheck() {
        if (_uiState.value.isFunctionalChecking) return
        viewModelScope.launch {
            val source = selectedSource()
            _uiState.update {
                it.copy(
                    isFunctionalChecking = true,
                    functionalCheckStatus = "Check fonctionnel en cours..."
                )
            }
            runCatching {
                val connectivity = updateService.testConnection(source)
                val result = updateService.checkForUpdates(source, prefs.getLastSeenTag(source.id))
                Triple(connectivity, result.release.tagName, result.candidates)
            }.onSuccess { (connectivity, tag, candidatesRaw) ->
                val candidates = candidatesRaw.map { candidate ->
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
                val parsedPackages = candidates.count { !it.packageName.isNullOrBlank() }
                _uiState.update {
                    it.copy(
                        isFunctionalChecking = false,
                        releaseTag = tag,
                        candidates = candidates,
                        functionalCheckStatus = buildString {
                            append("OK | ")
                            append(connectivity)
                            append(" | release=")
                            append(tag)
                            append(" | apk=")
                            append(candidates.size)
                            append(" | actionnables=")
                            append(actionable)
                            append(" | packages_detectes=")
                            append(parsedPackages)
                        }
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isFunctionalChecking = false,
                        functionalCheckStatus = "Check fonctionnel KO: ${error.message}"
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

    private fun selectedSource(): UpdateSource {
        val id = _uiState.value.selectedSourceId
        return UpdateSources.findById(id) ?: UpdateSources.default()
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
