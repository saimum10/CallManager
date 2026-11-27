package com.saimum.callmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saimum.callmanager.recording.RecordingPreferences
import com.saimum.callmanager.telephony.SimForwardingSnapshot

private val StatusOnColor = Color(0xFF2E7D32)
private val StatusOffColor = Color(0xFF9E9E9E)

/**
 * Read-only dashboard mirroring Recording + Forwarding state. Never edits
 * anything itself — tapping the Call Recording card or the Forwarding
 * section only navigates to the matching tab to change settings there; no
 * value shown here is ever changed from this screen. The Settings entry
 * point lives in the shared top bar (see CallManagerNavHost), not here.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    onNavigateToRecording: () -> Unit = {},
    onNavigateToForwarding: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refreshForwardingOverview(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        RecordingStatusCard(uiState.recordingPreferences, onClick = onNavigateToRecording)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigateToForwarding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Call Forwarding Overview", style = MaterialTheme.typography.titleMedium)

            when {
                uiState.forwardingPermissionMissing -> Text(
                    text = "Open the Forwarding tab once to see SIM status here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                uiState.forwardingSnapshots.isEmpty() -> Text(
                    text = "SIM not available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> uiState.forwardingSnapshots.forEach { snapshot ->
                    ForwardingOverviewCard(snapshot)
                }
            }
        }
    }
}

@Composable
private fun RecordingStatusCard(prefs: RecordingPreferences, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Call Recording", style = MaterialTheme.typography.titleMedium)
            StatusLine(label = null, isOn = prefs.masterEnabled)
            StatusLine(label = "Incoming", isOn = prefs.incomingEnabled)
            StatusLine(label = "Outgoing", isOn = prefs.outgoingEnabled)
        }
    }
}

@Composable
private fun ForwardingOverviewCard(snapshot: SimForwardingSnapshot) {
    // No own onClick — this card sits inside the forwarding section's
    // outer clickable Column, so a tap anywhere in that section (title,
    // placeholder text, or any SIM card) navigates to Forwarding the same
    // way. Giving the card its own onClick too would nest two clickables
    // and make Talkback announce the region twice.
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val simLabel = "SIM ${snapshot.slot.slotIndex + 1}" +
                if (snapshot.slot.displayName.isNotBlank()) " (${snapshot.slot.displayName})" else ""
            Text(simLabel, style = MaterialTheme.typography.titleMedium)
            StatusLine(label = "Forwarding", isOn = snapshot.config.enabled)
            if (snapshot.config.enabled) {
                LabelValueRow("Type", snapshot.config.reason.displayLabel())
                LabelValueRow("Forward to", snapshot.config.destinationNumber)
            }
            Text(
                text = "Not verified with carrier",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusLine(label: String?, isOn: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
        } else {
            Box(Modifier)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (isOn) StatusOnColor else StatusOffColor, CircleShape)
            )
            Text(if (isOn) "ON" else "OFF", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun LabelValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
