package com.saimum.callmanager.ui.screens

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saimum.callmanager.recording.RecordingPreferences
import com.saimum.callmanager.recording.RecordingServiceLocator
import com.saimum.callmanager.telephony.ForwardingOverviewProvider
import com.saimum.callmanager.telephony.SimForwardingSnapshot
import com.saimum.callmanager.telephony.SimSlotProvider
import com.saimum.callmanager.telephony.TelephonyServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val recordingPreferences: RecordingPreferences = RecordingPreferences(),
    val forwardingSnapshots: List<SimForwardingSnapshot> = emptyList(),
    val forwardingPermissionMissing: Boolean = true
)

/**
 * Purely a read-only mirror of what the Recording and Forwarding screens
 * already own — the Home dashboard never requests permissions or changes
 * any setting itself. If READ_PHONE_STATE hasn't been granted yet (because
 * the user hasn't opened the Forwarding tab), Home just says so rather
 * than requesting it up front.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var forwardingJob: Job? = null

    init {
        viewModelScope.launch {
            RecordingServiceLocator.preferencesStore?.preferences?.collect { prefs ->
                _uiState.value = _uiState.value.copy(recordingPreferences = prefs)
            }
        }
    }

    fun refreshForwardingOverview(context: Context) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        _uiState.value = _uiState.value.copy(forwardingPermissionMissing = !granted)
        if (!granted) return

        forwardingJob?.cancel()
        forwardingJob = viewModelScope.launch {
            val provider = ForwardingOverviewProvider(
                SimSlotProvider(getApplication()),
                TelephonyServiceLocator.forwardingPreferencesStore
            )
            provider.snapshots().collect { snapshots ->
                _uiState.value = _uiState.value.copy(forwardingSnapshots = snapshots)
            }
        }
    }
}
