package com.raph563.cheatupdater

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.raph563.cheatupdater.data.UpdateAction
import com.raph563.cheatupdater.notifier.NotificationHelper
import com.raph563.cheatupdater.receiver.InstallResultReceiver
import com.raph563.cheatupdater.service.UpdaterForegroundService
import com.raph563.cheatupdater.ui.CandidateUi
import com.raph563.cheatupdater.ui.MainUiState
import com.raph563.cheatupdater.ui.MainViewModel
import com.raph563.cheatupdater.ui.UiEvent
import com.raph563.cheatupdater.worker.WorkScheduler
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var pendingUninstallPackage: String? = null

    private val requestNotificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val uninstallLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            pendingUninstallPackage?.let { pkg ->
                viewModel.onUninstallCompleted(pkg)
            }
            pendingUninstallPackage = null
        }

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val source = intent ?: return
            val status = source.getIntExtra(
                InstallResultReceiver.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
            )
            val packageName = source.getStringExtra(InstallResultReceiver.EXTRA_PACKAGE_NAME)
            val message = source.getStringExtra(InstallResultReceiver.EXTRA_STATUS_MESSAGE)
            val filePath = source.getStringExtra(InstallResultReceiver.EXTRA_FILE_PATH)
            viewModel.onInstallResult(status, packageName, filePath, message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.createChannels(this)
        WorkScheduler.scheduleDailyNoonCheck(this)
        UpdaterForegroundService.start(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    if (event is UiEvent.PromptUninstall) {
                        showUninstallPrompt(event.packageName)
                    }
                }
            }
        }

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            MaterialTheme {
                CheatUpdaterScreen(
                    state = state,
                    onSourceSelected = viewModel::onSourceSelected,
                    onSaveConfig = viewModel::saveConfig,
                    onCheckUpdates = viewModel::checkUpdates,
                    onTestSource = viewModel::testSourceConnection,
                    onRunFunctionalCheck = viewModel::runFunctionalAppCheck,
                    onStartPersistentService = { UpdaterForegroundService.start(this) },
                    onRequestNotificationPermission = ::requestNotificationsPermission,
                    onRequestBatteryOptimizationExemption = ::requestBatteryOptimizationExemption,
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                    onOpenInstallUnknownAppsSettings = ::openUnknownAppsSettings,
                    onInstallCandidate = viewModel::installCandidate,
                    onRegister = viewModel::register,
                    onLogin = viewModel::login,
                    onVerifyEmail = viewModel::verifyEmail,
                    onRequestPasswordReset = viewModel::requestPasswordReset,
                    onResetPassword = viewModel::resetPassword,
                    onLogout = viewModel::logout,
                    onRefreshSession = viewModel::refreshSession,
                    onRefreshProtectedData = viewModel::refreshProtectedData,
                    onShareReferral = ::shareReferralLink,
                )
            }
        }

        handleNotificationIntent(intent)
        handleReferralIntent(intent)
        viewModel.checkUpdates()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
        handleReferralIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(InstallResultReceiver.ACTION_INSTALL_RESULT)
        ContextCompat.registerReceiver(
            this,
            installStatusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(installStatusReceiver) }
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val packageName = intent?.getStringExtra(NotificationHelper.EXTRA_PACKAGE_NAME)
        val filePath = intent?.getStringExtra(NotificationHelper.EXTRA_FILE_PATH)
        if (!packageName.isNullOrBlank()) {
            viewModel.onInstallResult(
                status = PackageInstaller.STATUS_FAILURE,
                packageName = packageName,
                filePath = filePath,
                statusMessage = "Relance depuis notification"
            )
        }
    }

    private fun handleReferralIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val code = data.getQueryParameter("ref")?.trim().orEmpty()
        if (code.isNotBlank()) {
            viewModel.setPrefilledReferralCode(code)
        }
    }

    private fun shareReferralLink(link: String?) {
        val value = link?.trim().orEmpty()
        if (value.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
        }
        startActivity(Intent.createChooser(intent, "Partager le lien de parrainage"))
    }

    private fun showUninstallPrompt(packageName: String) {
        pendingUninstallPackage = packageName
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        uninstallLauncher.launch(intent)
    }

    private fun requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        runCatching { startActivity(intent) }
    }

    private fun openUnknownAppsSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }.onFailure {
            if (it is ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CheatUpdaterScreen(
    state: MainUiState,
    onSourceSelected: (String) -> Unit,
    onSaveConfig: () -> Unit,
    onCheckUpdates: () -> Unit,
    onTestSource: () -> Unit,
    onRunFunctionalCheck: () -> Unit,
    onStartPersistentService: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestBatteryOptimizationExemption: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenInstallUnknownAppsSettings: () -> Unit,
    onInstallCandidate: (CandidateUi) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onLogin: (String, String) -> Unit,
    onVerifyEmail: (String) -> Unit,
    onRequestPasswordReset: (String) -> Unit,
    onResetPassword: (String, String) -> Unit,
    onLogout: () -> Unit,
    onRefreshSession: () -> Unit,
    onRefreshProtectedData: () -> Unit,
    onShareReferral: (String?) -> Unit,
) {
    val selectedSource = state.availableSources.firstOrNull { it.id == state.selectedSourceId }
    var sourceExpanded by remember { mutableStateOf(false) }

    var email by rememberSaveable { mutableStateOf(state.userEmail.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var referralCode by remember(state.prefilledReferralCode) { mutableStateOf(state.prefilledReferralCode) }
    var verifyToken by rememberSaveable { mutableStateOf("") }
    var resetEmail by rememberSaveable { mutableStateOf("") }
    var resetToken by rememberSaveable { mutableStateOf("") }
    var resetPassword by rememberSaveable { mutableStateOf("") }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("CheatUpdater v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
                Text("Release: ${state.releaseTag ?: "Aucune"}")
                Text("Etat: ${state.status}")
            }

            item { HorizontalDivider() }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Compte", fontWeight = FontWeight.Bold)
                    Text(state.authStatus)
                    if (state.isAuthenticated) {
                        Text("Connecte: ${state.userEmail ?: "inconnu"} (${state.userRole ?: "client"})")
                        Button(onClick = onRefreshSession, enabled = !state.isAuthLoading, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.isAuthLoading) "Validation..." else "Rafraichir session")
                        }
                        Button(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                            Text("Se deconnecter")
                        }
                    } else {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Mot de passe") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = referralCode,
                            onValueChange = { referralCode = it },
                            label = { Text("Code parrainage (obligatoire)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Button(
                            onClick = { onLogin(email, password) },
                            enabled = !state.isAuthLoading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.isAuthLoading) "Connexion..." else "Se connecter")
                        }
                        Button(
                            onClick = { onRegister(email, password, referralCode) },
                            enabled = !state.isAuthLoading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.isAuthLoading) "Creation..." else "S'inscrire")
                        }
                        OutlinedTextField(
                            value = verifyToken,
                            onValueChange = { verifyToken = it },
                            label = { Text("Token verification email") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Button(
                            onClick = { onVerifyEmail(verifyToken) },
                            enabled = !state.isAuthLoading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Valider email")
                        }
                        OutlinedTextField(
                            value = resetEmail,
                            onValueChange = { resetEmail = it },
                            label = { Text("Email reset mot de passe") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Button(
                            onClick = { onRequestPasswordReset(resetEmail) },
                            enabled = !state.isAuthLoading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Demander reset")
                        }
                        OutlinedTextField(
                            value = resetToken,
                            onValueChange = { resetToken = it },
                            label = { Text("Token reset") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = resetPassword,
                            onValueChange = { resetPassword = it },
                            label = { Text("Nouveau mot de passe") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Button(
                            onClick = { onResetPassword(resetToken, resetPassword) },
                            enabled = !state.isAuthLoading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Appliquer reset")
                        }
                    }
                }
            }

            item { HorizontalDivider() }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Source des mises a jour")
                    ExposedDropdownMenuBox(
                        expanded = sourceExpanded,
                        onExpandedChange = { sourceExpanded = !sourceExpanded }
                    ) {
                        OutlinedTextField(
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            readOnly = true,
                            value = selectedSource?.displayName ?: "Source inconnue",
                            onValueChange = {},
                            label = { Text("Source preconfiguree") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceExpanded)
                            }
                        )
                        ExposedDropdownMenu(
                            expanded = sourceExpanded,
                            onDismissRequest = { sourceExpanded = false }
                        ) {
                            state.availableSources.forEach { source ->
                                DropdownMenuItem(
                                    text = { Text(source.displayName) },
                                    onClick = {
                                        sourceExpanded = false
                                        onSourceSelected(source.id)
                                    }
                                )
                            }
                        }
                    }
                    Button(onClick = onSaveConfig, modifier = Modifier.fillMaxWidth()) {
                        Text("Sauver source")
                    }
                    Button(
                        onClick = onCheckUpdates,
                        enabled = !state.isChecking && state.isAuthenticated,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.isChecking) "Checking..." else "Check mises a jour")
                    }
                    Button(
                        onClick = onRefreshProtectedData,
                        enabled = !state.isDataLoading && state.isAuthenticated,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.isDataLoading) "Chargement..." else "Rafraichir news/parrainage")
                    }
                }
            }

            item { HorizontalDivider() }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Debug")
                    Text(state.debugStatus)
                    Button(
                        onClick = onTestSource,
                        enabled = !state.isTestingSource,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state.isTestingSource) "Test en cours..." else "Tester connexion backend")
                    }
                    Text(state.functionalCheckStatus)
                    Button(
                        onClick = onRunFunctionalCheck,
                        enabled = !state.isFunctionalChecking,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (state.isFunctionalChecking) {
                                "Check fonctionnel en cours..."
                            } else {
                                "Check application fonctionnel"
                            }
                        )
                    }
                }
            }

            item { HorizontalDivider() }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStartPersistentService, modifier = Modifier.fillMaxWidth()) {
                        Text("Activer notification persistante")
                    }
                    Button(
                        onClick = onRequestNotificationPermission,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Autoriser notifications")
                    }
                    Button(
                        onClick = onRequestBatteryOptimizationExemption,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Ignorer optimisation batterie")
                    }
                    Button(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Ouvrir accesibilite")
                    }
                    Button(
                        onClick = onOpenInstallUnknownAppsSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Autoriser installation APK")
                    }
                }
            }

            item { HorizontalDivider() }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Parrainage", fontWeight = FontWeight.Bold)
                    if (state.referral == null) {
                        Text("Aucune donnee de parrainage.")
                    } else {
                        Text("Code: ${state.referral.referralCode}")
                        Text("Total inscrits: ${state.referral.referredTotal}")
                        Text("Valides: ${state.referral.referredValidated}")
                        Text("Lien: ${state.referral.referralLink ?: "indisponible"}")
                        Button(
                            onClick = { onShareReferral(state.referral.referralLink) },
                            enabled = !state.referral.referralLink.isNullOrBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Partager le lien")
                        }
                    }
                }
            }

            item { HorizontalDivider() }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("News / Changelog", fontWeight = FontWeight.Bold)
                    if (state.newsItems.isEmpty()) {
                        Text("Aucune news disponible.")
                    }
                }
            }

            items(state.newsItems, key = { it.id }) { news ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(news.title, style = MaterialTheme.typography.titleMedium)
                        Text("Type: ${news.type} | Date: ${news.publishedAt}")
                        Text(news.bodyMarkdown)
                    }
                }
            }

            item { HorizontalDivider() }

            if (state.candidates.isEmpty()) {
                item { Text("Aucun APK detecte pour l'instant.") }
            } else {
                items(state.candidates, key = { it.localPath }) { candidate ->
                    CandidateCard(candidate = candidate, onInstallCandidate = onInstallCandidate)
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(candidate: CandidateUi, onInstallCandidate: (CandidateUi) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(candidate.assetName, style = MaterialTheme.typography.titleMedium)
            Text("Package: ${candidate.packageName ?: "Inconnu"}")
            Text("Version APK: ${candidate.archiveVersionCode ?: -1}")
            Text("Version installee: ${candidate.installedVersionCode ?: -1}")
            Text("Action: ${candidate.action.name}")
            if (!candidate.reason.isNullOrBlank()) {
                Text("Raison: ${candidate.reason}")
            }
            if (
                candidate.action == UpdateAction.INSTALL ||
                candidate.action == UpdateAction.UPDATE ||
                candidate.action == UpdateAction.REINSTALL
            ) {
                val label = when (candidate.action) {
                    UpdateAction.INSTALL -> "Installer"
                    UpdateAction.UPDATE -> "Mettre a jour"
                    UpdateAction.REINSTALL -> "Reinstaller"
                    UpdateAction.UP_TO_DATE -> "Installer"
                }
                Button(onClick = { onInstallCandidate(candidate) }) {
                    Text(label)
                }
            }
        }
    }
}
