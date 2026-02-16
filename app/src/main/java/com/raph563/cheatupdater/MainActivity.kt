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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
        WorkScheduler.enqueueOneTimeCheck(this)
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
                    onOwnerChanged = viewModel::onOwnerChanged,
                    onRepoChanged = viewModel::onRepoChanged,
                    onTokenChanged = viewModel::onTokenChanged,
                    onSaveConfig = viewModel::saveConfig,
                    onCheckUpdates = {
                        WorkScheduler.enqueueOneTimeCheck(this)
                        viewModel.checkUpdates()
                    },
                    onStartPersistentService = { UpdaterForegroundService.start(this) },
                    onRequestNotificationPermission = ::requestNotificationsPermission,
                    onRequestBatteryOptimizationExemption = ::requestBatteryOptimizationExemption,
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                    onOpenInstallUnknownAppsSettings = ::openUnknownAppsSettings,
                    onInstallCandidate = viewModel::installCandidate
                )
            }
        }

        viewModel.checkUpdates()
        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
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

@Composable
private fun CheatUpdaterScreen(
    state: MainUiState,
    onOwnerChanged: (String) -> Unit,
    onRepoChanged: (String) -> Unit,
    onTokenChanged: (String) -> Unit,
    onSaveConfig: () -> Unit,
    onCheckUpdates: () -> Unit,
    onStartPersistentService: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestBatteryOptimizationExemption: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenInstallUnknownAppsSettings: () -> Unit,
    onInstallCandidate: (CandidateUi) -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("CheatUpdater V1", style = MaterialTheme.typography.headlineSmall)
                Text("Release: ${state.releaseTag ?: "Aucune"}")
                Text("Etat: ${state.status}")
            }

            item { HorizontalDivider() }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.owner,
                        onValueChange = onOwnerChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("GitHub Owner") }
                    )
                    OutlinedTextField(
                        value = state.repo,
                        onValueChange = onRepoChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("GitHub Repo") }
                    )
                    OutlinedTextField(
                        value = state.token,
                        onValueChange = onTokenChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Token (optionnel)") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSaveConfig) { Text("Sauver config") }
                        Button(onClick = onCheckUpdates, enabled = !state.isChecking) {
                            Text(if (state.isChecking) "Checking..." else "Check mises a jour")
                        }
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
            if (candidate.action == UpdateAction.INSTALL || candidate.action == UpdateAction.UPDATE) {
                val label = if (candidate.action == UpdateAction.INSTALL) "Installer" else "Mettre a jour"
                Button(onClick = { onInstallCandidate(candidate) }) {
                    Text(label)
                }
            }
        }
    }
}
