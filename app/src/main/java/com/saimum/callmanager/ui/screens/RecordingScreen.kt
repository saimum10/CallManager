package com.saimum.callmanager.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saimum.callmanager.recording.CallDirection
import com.saimum.callmanager.recording.RecordingCapability
import com.saimum.callmanager.recording.RecordingEvent
import com.saimum.callmanager.recording.RecordingLibraryItem
import com.saimum.callmanager.recording.RecordingLiveState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingScreen(viewModel: RecordingViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Judge by the permissions recording actually needs — not by every
        // entry in the result map. Notifications are requested alongside but
        // are optional: denying them must not block recording.
        if (hasAllRecordingPermissions(context)) {
            viewModel.attemptEnableMaster()
        } else {
            viewModel.onPermissionsDenied()
        }
    }

    LaunchedEffect(uiState.lastEvent) {
        val event = uiState.lastEvent ?: return@LaunchedEffect
        val message = when (event) {
            is RecordingEvent.Saved -> "Recording saved: ${event.fileName}"
            is RecordingEvent.SavedButSilent ->
                "Saved, but no audio was captured — recording isn't supported on this device"
            is RecordingEvent.DeviceUnsupported -> "Recording unavailable on this device"
            is RecordingEvent.PermissionRevoked -> "Permission required — recording was skipped for this call"
        }
        snackbarHostState.showSnackbar(message)
        viewModel.consumeLastEvent()
    }

    LaunchedEffect(uiState.playbackError) {
        val error = uiState.playbackError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.consumePlaybackError()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                MasterToggleCard(
                    masterEnabled = uiState.preferences.masterEnabled,
                    capability = uiState.capability,
                    permissionDenied = uiState.permissionDenied,
                    onToggle = { enable ->
                        if (enable) {
                            if (hasAllRecordingPermissions(context)) {
                                viewModel.attemptEnableMaster()
                            } else {
                                permissionLauncher.launch(
                                    requiredRecordingPermissions() + missingOptionalPermissions(context)
                                )
                            }
                        } else {
                            viewModel.disableMaster()
                        }
                    }
                )
            }

            item {
                DirectionToggleRow(
                    label = "Incoming",
                    checked = uiState.preferences.incomingEnabled,
                    enabled = uiState.preferences.masterEnabled,
                    onCheckedChange = viewModel::setIncomingEnabled
                )
            }
            item {
                DirectionToggleRow(
                    label = "Outgoing",
                    checked = uiState.preferences.outgoingEnabled,
                    enabled = uiState.preferences.masterEnabled,
                    onCheckedChange = viewModel::setOutgoingEnabled
                )
            }

            val liveState = uiState.liveState
            if (liveState is RecordingLiveState.Recording) {
                item {
                    LiveRecordingCard(direction = liveState.direction, onStop = viewModel::stopActiveRecording)
                }
            }

            item {
                Text(
                    text = "Recordings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (uiState.recordings.isEmpty()) {
                item {
                    Text(
                        text = "No recordings yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(uiState.recordings, key = { it.id }) { recording ->
                    RecordingItemCard(
                        item = recording,
                        isPlaying = uiState.playback.playingId == recording.id && !uiState.playback.isPaused,
                        isPaused = uiState.playback.playingId == recording.id && uiState.playback.isPaused,
                        onTogglePlayback = { viewModel.togglePlayback(recording) },
                        onDelete = { viewModel.deleteRecording(recording) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MasterToggleCard(
    masterEnabled: Boolean,
    capability: RecordingCapability,
    permissionDenied: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Call Recording", style = MaterialTheme.typography.titleMedium)
                Switch(checked = masterEnabled, onCheckedChange = onToggle)
            }
            val statusText = when {
                permissionDenied -> "Permission required"
                capability.isConfirmedUnavailable -> "Recording unavailable on this device"
                capability == RecordingCapability.SUPPORTED -> "Confirmed working on this device"
                masterEnabled -> "Not yet verified — we'll confirm on your next call"
                else -> null
            }
            if (statusText != null) {
                val isError = permissionDenied || capability.isConfirmedUnavailable
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DirectionToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LiveRecordingCard(direction: CallDirection, onStop: () -> Unit) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Recording ${direction.name.lowercase()} call…")
            TextButton(onClick = onStop) { Text("Stop") }
        }
    }
}

@Composable
private fun RecordingItemCard(
    item: RecordingLibraryItem,
    isPlaying: Boolean,
    isPaused: Boolean,
    onTogglePlayback: () -> Unit,
    onDelete: () -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${item.direction.name.lowercase().replaceFirstChar { it.uppercase() }} · ${formatTimestamp(item.createdAtMillis)}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = formatDuration(item.durationMillis) + if (item.isSilent) " · no audio detected" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onTogglePlayback) {
                    Text(if (isPlaying) "Pause" else if (isPaused) "Resume" else "Play")
                }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

private fun formatTimestamp(millis: Long): String {
    val formatter = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return formatter.format(Date(millis))
}

/** What call recording genuinely cannot work without. */
private fun requiredRecordingPermissions(): Array<String> = arrayOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.READ_PHONE_STATE
)

/**
 * Asked for in the same dialog but never required: without the notification
 * permission the foreground service still runs and still records — Android
 * just doesn't show its status notification in the shade (it stays listed in
 * the system's active-apps list).
 */
private fun missingOptionalPermissions(context: android.content.Context): Array<String> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyArray()
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    return if (granted) emptyArray() else arrayOf(Manifest.permission.POST_NOTIFICATIONS)
}

private fun hasAllRecordingPermissions(context: android.content.Context): Boolean =
    requiredRecordingPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
