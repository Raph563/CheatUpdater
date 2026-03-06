package com.raph563.cheatupdater.ui

import android.app.Application
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.raph563.cheatupdater.data.ApkCandidate
import com.raph563.cheatupdater.data.AppPreferences
import com.raph563.cheatupdater.data.NewsItem
import com.raph563.cheatupdater.data.ReferralSummary
import com.raph563.cheatupdater.data.UpdateAction
import com.raph563.cheatupdater.data.UpdateSource
import com.raph563.cheatupdater.data.UpdateSources
import com.raph563.cheatupdater.network.AuthRepository
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
    val archiveVersionCode: Long?,
    val artifactId: String? = null,
    val releaseId: String? = null,
    val reason: String? = null,
)

data class MainUiState(
    val availableSources: List<UpdateSource> = UpdateSources.all,
    val selectedSourceId: String = UpdateSources.default().id,
    val isChecking: Boolean = false,
    val isTestingSource: Boolean = false,
    val isFunctionalChecking: Boolean = false,
    val isAuthLoading: Boolean = false,
    val isDataLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val userEmail: String? = null,
    val userRole: String? = null,
    val prefilledReferralCode: String = "",
    val status: String = "Pret.",
    val authStatus: String = "Connexion requise.",
    val debugStatus: String = "Aucun test execute.",
    val functionalCheckStatus: String = "Aucun check fonctionnel execute.",
    val releaseTag: String? = null,
    val candidates: List<CandidateUi> = emptyList(),
    val newsItems: List<NewsItem> = emptyList(),
    val referral: ReferralSummary? = null,
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
    private val pendingInstallCandidateByPackage = mutableMapOf<String, CandidateUi>()

    init {
        val sourceId = prefs.getSelectedSourceId()
        val resolved = UpdateSources.findById(sourceId) ?: UpdateSources.default()
        _uiState.update {
            it.copy(
                selectedSourceId = resolved.id,
                isAuthenticated = !prefs.getAccessToken().isNullOrBlank(),
                userEmail = prefs.getUserEmail(),
                userRole = prefs.getUserRole(),
                authStatus = if (prefs.getAccessToken().isNullOrBlank()) {
                    "Connexion requise."
                } else {
                    "Session locale detectee."
                },
            )
        }
        if (!prefs.getAccessToken().isNullOrBlank()) {
            refreshSession()
        }
    }

    fun setPrefilledReferralCode(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        _uiState.update { it.copy(prefilledReferralCode = clean) }
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

    fun register(email: String, password: String, referralCode: String) {
        if (_uiState.value.isAuthLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authStatus = "Creation du compte...") }
            runCatching {
                authRepository().register(email = email, password = password, referralCode = referralCode)
            }.onSuccess { message ->
                _uiState.update {
                    it.copy(
                        isAuthLoading = false,
                        authStatus = "$message (verification email requise)",
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isAuthLoading = false,
                        authStatus = "Inscription KO: ${error.message}",
                    )
                }
            }
        }
    }

    fun verifyEmail(token: String) {
        if (_uiState.value.isAuthLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authStatus = "Verification email...") }
            runCatching {
                authRepository().verifyEmail(token)
            }.onSuccess { message ->
                _uiState.update {
                    it.copy(
                        isAuthLoading = false,
                        authStatus = message,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isAuthLoading = false,
                        authStatus = "Verification KO: ${error.message}",
                    )
                }
            }
        }
    }

    fun login(email: String, password: String) {
        if (_uiState.value.isAuthLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authStatus = "Connexion...") }
            runCatching {
                authRepository().login(email, password)
                authRepository().me()
            }.onSuccess { profile ->
                _uiState.update {
                    it.copy(
                        isAuthLoading = false,
                        isAuthenticated = true,
                        userEmail = profile.email,
                        userRole = profile.role,
                        authStatus = "Connecte: ${profile.email}",
                    )
                }
                refreshProtectedData()
                checkUpdates()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isAuthLoading = false,
                        isAuthenticated = false,
                        authStatus = "Connexion KO: ${error.message}",
                    )
                }
            }
        }
    }

    fun logout() {
        authRepository().logout()
        _uiState.update {
            it.copy(
                isAuthenticated = false,
                userEmail = null,
                userRole = null,
                authStatus = "Session fermee.",
                newsItems = emptyList(),
                referral = null,
                candidates = emptyList(),
            )
        }
    }

    fun requestPasswordReset(email: String) {
        if (_uiState.value.isAuthLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authStatus = "Demande reset mot de passe...") }
            runCatching {
                authRepository().requestPasswordReset(email)
            }.onSuccess { message ->
                _uiState.update { it.copy(isAuthLoading = false, authStatus = message) }
            }.onFailure { error ->
                _uiState.update { it.copy(isAuthLoading = false, authStatus = "Reset KO: ${error.message}") }
            }
        }
    }

    fun resetPassword(token: String, newPassword: String) {
        if (_uiState.value.isAuthLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authStatus = "Mise a jour mot de passe...") }
            runCatching {
                authRepository().resetPassword(token, newPassword)
            }.onSuccess { message ->
                _uiState.update { it.copy(isAuthLoading = false, authStatus = message) }
            }.onFailure { error ->
                _uiState.update { it.copy(isAuthLoading = false, authStatus = "Reset KO: ${error.message}") }
            }
        }
    }

    fun refreshSession() {
        if (_uiState.value.isAuthLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authStatus = "Validation session...") }
            runCatching {
                val repo = authRepository()
                if (!repo.hasSession()) throw IllegalStateException("Aucun token local")
                repo.me()
            }.onSuccess { profile ->
                _uiState.update {
                    it.copy(
                        isAuthLoading = false,
                        isAuthenticated = true,
                        userEmail = profile.email,
                        userRole = profile.role,
                        authStatus = "Session active: ${profile.email}",
                    )
                }
                refreshProtectedData()
            }.onFailure {
                runCatching { authRepository().refresh() }
                    .onSuccess {
                        runCatching { authRepository().me() }
                            .onSuccess { profile ->
                                _uiState.update {
                                    it.copy(
                                        isAuthLoading = false,
                                        isAuthenticated = true,
                                        userEmail = profile.email,
                                        userRole = profile.role,
                                        authStatus = "Session renouvelee: ${profile.email}",
                                    )
                                }
                                refreshProtectedData()
                            }
                            .onFailure { error ->
                                authRepository().logout()
                                _uiState.update {
                                    it.copy(
                                        isAuthLoading = false,
                                        isAuthenticated = false,
                                        userEmail = null,
                                        userRole = null,
                                        authStatus = "Session invalide: ${error.message}",
                                    )
                                }
                            }
                    }
                    .onFailure { error ->
                        authRepository().logout()
                        _uiState.update {
                            it.copy(
                                isAuthLoading = false,
                                isAuthenticated = false,
                                userEmail = null,
                                userRole = null,
                                authStatus = "Session expiree: ${error.message}",
                            )
                        }
                    }
            }
        }
    }

    fun checkUpdates() {
        if (_uiState.value.isChecking) return
        if (!_uiState.value.isAuthenticated) {
            _uiState.update { it.copy(status = "Connecte-toi avant de checker les mises a jour.") }
            return
        }
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
                        archiveVersionCode = candidate.archiveVersionCode,
                        artifactId = candidate.artifactId,
                        releaseId = candidate.releaseId,
                        reason = candidate.reason,
                    )
                }
                val actionable = candidates.count { it.action.isActionable() }
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
        if (!_uiState.value.isAuthenticated) {
            _uiState.update { it.copy(functionalCheckStatus = "Connexion requise pour le check fonctionnel.") }
            return
        }
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
                val news = updateService.fetchNews(source)
                Triple(connectivity, result, news)
            }.onSuccess { (connectivity, result, news) ->
                val candidates = result.candidates.map { candidate ->
                    CandidateUi(
                        assetName = candidate.asset.name,
                        packageName = candidate.packageName,
                        localPath = candidate.localFile.absolutePath,
                        action = candidate.action,
                        installedVersionCode = candidate.installedVersionCode,
                        archiveVersionCode = candidate.archiveVersionCode,
                        artifactId = candidate.artifactId,
                        releaseId = candidate.releaseId,
                        reason = candidate.reason,
                    )
                }
                val actionable = candidates.count { it.action.isActionable() }
                val parsedPackages = candidates.count { !it.packageName.isNullOrBlank() }
                _uiState.update {
                    it.copy(
                        isFunctionalChecking = false,
                        releaseTag = result.release.tagName,
                        candidates = candidates,
                        newsItems = news,
                        functionalCheckStatus = buildString {
                            append("OK | ")
                            append(connectivity)
                            append(" | release=")
                            append(result.release.tagName)
                            append(" | apk=")
                            append(candidates.size)
                            append(" | actionnables=")
                            append(actionable)
                            append(" | packages_detectes=")
                            append(parsedPackages)
                            append(" | news=")
                            append(news.size)
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

    fun refreshProtectedData() {
        if (_uiState.value.isDataLoading || !_uiState.value.isAuthenticated) return
        viewModelScope.launch {
            val source = selectedSource()
            _uiState.update { it.copy(isDataLoading = true, status = "Chargement news et parrainage...") }
            runCatching {
                val news = updateService.fetchNews(source)
                val referral = updateService.fetchReferral(source)
                Pair(news, referral)
            }.onSuccess { (news, referral) ->
                _uiState.update {
                    it.copy(
                        isDataLoading = false,
                        newsItems = news,
                        referral = referral,
                        status = "Donnees chargees."
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isDataLoading = false,
                        status = "Echec chargement donnees: ${error.message}"
                    )
                }
            }
        }
    }

    fun installCandidate(candidate: CandidateUi) {
        val targetPackage = candidate.packageName
        if (targetPackage != null) {
            pendingReinstallPathByPackage[targetPackage] = candidate.localPath
            pendingInstallCandidateByPackage[targetPackage] = candidate
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
                val pkg = packageName
                val candidate = pkg?.let { pendingInstallCandidateByPackage[it] }
                if (candidate != null) {
                    reportInstall(candidate, candidate.action.toReportStatus())
                    if (!candidate.releaseId.isNullOrBlank() && !pkg.isNullOrBlank()) {
                        prefs.setInstalledReleaseId(pkg, candidate.releaseId)
                    }
                    pendingInstallCandidateByPackage.remove(pkg)
                }
                _uiState.update {
                    it.copy(status = "Installation reussie pour ${packageName ?: "package inconnu"}.")
                }
                checkUpdates()
                return@launch
            }

            val pkg = packageName
            val candidate = pkg?.let { pendingInstallCandidateByPackage[it] }
            if (candidate != null) {
                reportInstall(candidate, "failed")
            }

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

    private fun authRepository(): AuthRepository {
        val baseUrl = selectedSource().backendBaseUrl
            ?: throw IllegalStateException("Base URL backend manquante")
        return AuthRepository(getApplication(), baseUrl)
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

    private suspend fun reportInstall(candidate: CandidateUi, status: String) {
        val source = selectedSource()
        runCatching {
            val apkCandidate = ApkCandidate(
                asset = com.raph563.cheatupdater.data.GitHubAsset(
                    id = candidate.assetName.hashCode().toLong(),
                    name = candidate.assetName,
                    size = File(candidate.localPath).length(),
                    browserDownloadUrl = "",
                ),
                localFile = File(candidate.localPath),
                packageName = candidate.packageName,
                archiveVersionCode = candidate.archiveVersionCode,
                installedVersionCode = candidate.installedVersionCode,
                action = candidate.action,
                artifactId = candidate.artifactId,
                releaseId = candidate.releaseId,
                reason = candidate.reason,
            )
            updateService.reportInstall(source, apkCandidate, status)
        }
    }
}

private fun UpdateAction.isActionable(): Boolean {
    return this == UpdateAction.INSTALL || this == UpdateAction.UPDATE || this == UpdateAction.REINSTALL
}

private fun UpdateAction.toReportStatus(): String {
    return when (this) {
        UpdateAction.INSTALL -> "installed"
        UpdateAction.UPDATE -> "updated"
        UpdateAction.REINSTALL -> "reinstalled"
        UpdateAction.UP_TO_DATE -> "installed"
    }
}
