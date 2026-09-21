package com.saimum.callmanager.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saimum.callmanager.data.ThemeMode
import com.saimum.callmanager.recording.AutoDeleteInterval
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.refreshPermissionStatuses(context)
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.setCustomFolderUri(uri.toString())
            } catch (e: SecurityException) {
                viewModel.onFolderPermissionFailed()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SectionCard(title = "Call recording") {
                SettingsSwitchRow(
                    label = "Include call direction in file name",
                    checked = uiState.recordingSettings.includeDirectionInFileName,
                    onCheckedChange = viewModel::setIncludeDirectionInFileName
                )
                SettingsSwitchRow(
                    label = "Use speakerphone while recording",
                    checked = uiState.recordingSettings.forceSpeakerphoneWhileRecording,
                    onCheckedChange = viewModel::setForceSpeakerphoneWhileRecording
                )
                Text(
                    "On some devices the normal recording path captures nothing from the " +
                        "other person — turning this on switches every call to speakerphone " +
                        "while it's being recorded, which fixes that on many phones. It's " +
                        "audible to anyone nearby, so it's off by default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Storage used: ${formatBytes(uiState.storageUsedBytes)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                Text("Auto-delete old recordings", style = MaterialTheme.typography.titleSmall)
                RadioOptionGroup(
                    options = autoDeleteOptions,
                    selected = uiState.recordingSettings.autoDeleteInterval,
                    onSelect = viewModel::setAutoDeleteInterval
                )
                Spacer(Modifier.height(4.dp))
                Text("Maximum storage limit", style = MaterialTheme.typography.titleSmall)
                RadioOptionGroup(
                    options = maxStorageOptions,
                    selected = uiState.recordingSettings.maxStorageBytes,
                    onSelect = viewModel::setMaxStorageBytes
                )
            }

            SectionCard(title = "Recordings folder") {
                val folderLabel = uiState.customFolderDisplayName?.let { "Copying to: $it" }
                    ?: "Using app storage only"
                Text(folderLabel, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "New recordings are always kept in the app's own storage (for the " +
                        "in-app library) and additionally copied here as a convenience, best-effort.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { folderPickerLauncher.launch(null) }) {
                        Text("Choose folder")
                    }
                    if (uiState.recordingSettings.customFolderUri != null) {
                        TextButton(onClick = { viewModel.setCustomFolderUri(null) }) {
                            Text("Use app storage only")
                        }
                    }
                }
            }

            SectionCard(title = "Notification") {
                SettingsSwitchRow(
                    label = "Notify when a recording finishes",
                    checked = uiState.recordingSettings.notifyOnFinish,
                    onCheckedChange = viewModel::setNotifyOnFinish
                )
                Text(
                    "While call recording is turned on, a quiet \"Call recording is on\" " +
                        "notification stays visible (and changes to \"Recording active\" during " +
                        "a call). It can't be turned off — Android requires it so the app can " +
                        "keep recording while it's in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard(title = "Appearance") {
                RadioOptionGroup(
                    options = themeOptions,
                    selected = uiState.themeMode,
                    onSelect = viewModel::setThemeMode
                )
            }

            SectionCard(title = "Permissions") {
                uiState.permissionStatuses.forEach { status ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(status.label)
                        Text(
                            text = if (status.granted) "Granted" else "Not granted",
                            color = if (status.granted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
                TextButton(onClick = { openAppSettings(context) }) {
                    Text("Open app settings")
                }
            }

            SectionCard(title = "About") {
                Text("Version ${uiState.appVersion}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text("Developed by Saimum x AI", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "github.com/saimum10/CallManager",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { openUrl(context, "https://github.com/saimum10/CallManager") }
                )
                Spacer(Modifier.height(4.dp))
                Text("License", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Apache License 2.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> RadioOptionGroup(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        options.forEach { (label, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(value) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = value == selected, onClick = { onSelect(value) })
                Text(label)
            }
        }
    }
}

private val autoDeleteOptions: List<Pair<String, AutoDeleteInterval>> = listOf(
    "Off" to AutoDeleteInterval.OFF,
    "3 days" to AutoDeleteInterval.AFTER_3_DAYS,
    "7 days" to AutoDeleteInterval.AFTER_7_DAYS,
    "15 days" to AutoDeleteInterval.AFTER_15_DAYS,
    "30 days" to AutoDeleteInterval.AFTER_30_DAYS
)

private val maxStorageOptions: List<Pair<String, Long?>> = listOf(
    "No limit" to null,
    "100 MB" to 100L * 1024 * 1024,
    "500 MB" to 500L * 1024 * 1024,
    "1 GB" to 1024L * 1024 * 1024,
    "2 GB" to 2048L * 1024 * 1024
)

private val themeOptions: List<Pair<String, ThemeMode>> = listOf(
    "Light" to ThemeMode.LIGHT,
    "Dark" to ThemeMode.DARK,
    "System default" to ThemeMode.SYSTEM
)

private fun formatBytes(bytes: Long): String {
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 1024.0) {
        String.format(Locale.US, "%.2f GB", megabytes / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", megabytes)
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    context.startActivity(intent)
}

private fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
