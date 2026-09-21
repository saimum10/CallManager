package com.saimum.callmanager.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saimum.callmanager.telephony.ForwardingMmiCodes
import com.saimum.callmanager.telephony.ForwardingReason

@Composable
fun ForwardingScreen(viewModel: ForwardingViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) viewModel.onPermissionsGranted() else viewModel.onPermissionsDenied()
    }

    LaunchedEffect(Unit) {
        if (hasForwardingPermissions(context)) {
            viewModel.onPermissionsGranted()
        } else {
            viewModel.onPermissionsDenied()
        }
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                uiState.permissionDenied -> PermissionPromptCard(
                    onGrant = { permissionLauncher.launch(requiredForwardingPermissions()) }
                )
                uiState.loading -> Text("Loading SIM information…")
                uiState.simCards.isEmpty() -> Text(
                    "SIM not available",
                    style = MaterialTheme.typography.bodyLarge
                )
                else -> {
                    Text(
                        "Forwarding is set up directly with your carrier and may incur charges — " +
                            "check your plan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    uiState.simCards.forEach { card ->
                        SimForwardingCard(
                            card = card,
                            onToggle = { viewModel.toggleForwarding(card) },
                            onReasonChange = { viewModel.setReason(card.slot.slotIndex, it) },
                            onNumberChange = { viewModel.setDestinationNumber(card.slot.slotIndex, it) },
                            onCheckStatus = { viewModel.checkStatus(card) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionPromptCard(onGrant: () -> Unit) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Managing call forwarding needs phone permissions",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "To read which SIMs are active and to dial the forwarding codes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onGrant) { Text("Grant permission") }
        }
    }
}

@Composable
private fun SimForwardingCard(
    card: SimForwardingUiState,
    onToggle: () -> Unit,
    onReasonChange: (ForwardingReason) -> Unit,
    onNumberChange: (String) -> Unit,
    onCheckStatus: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val simLabel = "SIM ${card.slot.slotIndex + 1}" +
                    if (card.slot.displayName.isNotBlank()) " (${card.slot.displayName})" else ""
                Text(simLabel, style = MaterialTheme.typography.titleMedium)
                Switch(checked = card.config.enabled, onCheckedChange = { onToggle() }, enabled = !card.isBusy)
            }

            val statusText = if (card.config.enabled) {
                "Last set: ${card.config.reason.displayLabel()} → ${card.config.destinationNumber}"
            } else {
                "Forwarding off"
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Real, network-verified signal — only covers Unconditional (see
            // CallForwardingIndicatorTracker's doc comment for why). Shown
            // regardless of which reason is currently selected below, since
            // it reflects the carrier's actual unconditional-forwarding
            // state, independent of what the user happens to be viewing.
            val verifiedText = when (card.verifiedUnconditionalActive) {
                true -> "Network status (unconditional): ON — verified"
                false -> "Network status (unconditional): OFF — verified"
                null -> "Network status (unconditional): checking…"
            }
            Text(
                text = verifiedText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            ForwardingReasonSelector(
                selected = card.config.reason,
                enabled = !card.isBusy,
                onSelect = onReasonChange
            )

            // Bound to local state, not card.config.destinationNumber directly:
            // that value only updates after a full DataStore write-then-reread
            // round trip, which is too slow to back a text field directly —
            // fast typing would visibly lag or drop characters waiting for
            // each keystroke's round trip before the next could register.
            // Re-seeded only when switching to a different SIM slot, so a
            // persisted change from elsewhere doesn't fight the user's typing.
            var localNumber by remember(card.slot.slotIndex) { mutableStateOf(card.config.destinationNumber) }
            OutlinedTextField(
                value = localNumber,
                onValueChange = { newValue ->
                    // Optional leading "+" then digits, up to the E.164 maximum
                    // of 15 — works for local numbers and any country code.
                    val filtered = ForwardingMmiCodes.sanitizeDestination(newValue)
                    localNumber = filtered
                    onNumberChange(filtered)
                },
                label = { Text("Forward to number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                // Locked while forwarding is on for this SIM, same reason as
                // the reason selector above: the active MMI registration was
                // made for the number that's already saved, so editing it
                // here without turning forwarding off first would desync
                // the on-screen value from what the carrier actually has.
                enabled = !card.isBusy && !card.config.enabled,
                modifier = Modifier.fillMaxWidth()
            )

            TextButton(onClick = onCheckStatus, enabled = !card.isBusy) {
                Text("Check status with carrier")
            }

            if (card.lastCheckedStatusText != null) {
                Text(
                    text = "Carrier says: ${card.lastCheckedStatusText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ForwardingReasonSelector(
    selected: ForwardingReason,
    enabled: Boolean,
    onSelect: (ForwardingReason) -> Unit
) {
    Column {
        ForwardingReason.entries.forEach { reason ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onSelect(reason) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = reason == selected, onClick = { onSelect(reason) }, enabled = enabled)
                Text(reason.displayLabel())
            }
        }
    }
}

private fun requiredForwardingPermissions(): Array<String> = arrayOf(
    Manifest.permission.CALL_PHONE,
    Manifest.permission.READ_PHONE_STATE
)

private fun hasForwardingPermissions(context: android.content.Context): Boolean =
    requiredForwardingPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
