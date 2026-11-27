package com.saimum.callmanager

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saimum.callmanager.data.ThemeMode
import com.saimum.callmanager.recording.CallRecordingService
import com.saimum.callmanager.recording.RecordingServiceLocator
import com.saimum.callmanager.ui.navigation.CallManagerNavHost
import com.saimum.callmanager.ui.theme.CallManagerTheme
import com.saimum.callmanager.ui.theme.ThemeViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeViewModel: ThemeViewModel = viewModel()
            val themeMode by themeViewModel.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            CallManagerTheme(darkTheme = darkTheme) {
                CallManagerNavHost()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        resumeCallRecordingIfEnabled()
    }

    /**
     * Keeps call detection in step with the master toggle whenever the app
     * comes on screen — e.g. after a restore from backup. There is no
     * long-running service to bring back any more: "listening" is only the
     * call-state receiver being enabled, and Android wakes the app by itself
     * when a call starts, even with the UI closed or after a reboot.
     */
    private fun resumeCallRecordingIfEnabled() {
        val store = RecordingServiceLocator.preferencesStore ?: return
        lifecycleScope.launch {
            val masterEnabled = store.preferences.first().masterEnabled
            val permissionsGranted = listOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE
            ).all { ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED }

            when {
                masterEnabled && permissionsGranted ->
                    CallRecordingService.startListening(this@MainActivity)
                // Toggle says "on" but the permissions are gone (revoked in
                // system settings, or the preference came back from a backup
                // restore on a new device) — show the honest state instead of
                // an "on" switch that can't record anything.
                masterEnabled -> {
                    store.setMasterEnabled(false)
                    CallRecordingService.stopListening(this@MainActivity)
                }
                // Toggle is off: make sure the receiver isn't waking the app for calls.
                else -> CallRecordingService.stopListening(this@MainActivity)
            }
        }
    }
}
