package com.saimum.callmanager.ui.screens

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saimum.callmanager.data.ThemeMode
import com.saimum.callmanager.data.ThemePreferencesStore
import com.saimum.callmanager.recording.AutoDeleteInterval
import com.saimum.callmanager.recording.RecordingSettings
import com.saimum.callmanager.recording.RecordingServiceLocator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PermissionStatus(val label: String, val granted: Boolean)

data class SettingsUiState(
    val recordingSettings: RecordingSettings = RecordingSettings(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val storageUsedBytes: Long = 0L,
    val permissionStatuses: List<PermissionStatus> = emptyList(),
    val appVersion: String = "",
    val customFolderDisplayName: String? = null,
    val message: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val themeStore = ThemePreferencesStore(application)
    private val settingsStore get() = RecordingServiceLocator.settingsStore
    private val libraryStore get() = RecordingServiceLocator.libraryStore

    private val _uiState = MutableStateFlow(SettingsUiState(appVersion = readAppVersion(application)))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore?.settings?.collect { s ->
                // DocumentFile lookups are a content-provider call — off the main thread.
                val displayName = s.customFolderUri?.let {
                    withContext(Dispatchers.IO) { resolveFolderDisplayName(it) }
                }
                _uiState.update { it.copy(recordingSettings = s, customFolderDisplayName = displayName) }
            }
        }
        viewModelScope.launch {
            themeStore.themeMode.collect { mode ->
                _uiState.update { it.copy(themeMode = mode) }
            }
        }
        viewModelScope.launch {
            // One File.length() (a disk hit) per recording — computed on the IO
            // dispatcher so a long library can't stall the UI on every change.
            libraryStore?.recordings
                ?.map { list ->
                    list.sumOf { item -> runCatching { File(item.filePath).length() }.getOrDefault(0L) }
                }
                ?.flowOn(Dispatchers.IO)
                ?.collect { total -> _uiState.update { it.copy(storageUsedBytes = total) } }
        }
    }

    /** Call each time the screen appears — grants can change while the app is backgrounded. */
    fun refreshPermissionStatuses(context: Context) {
        val statuses = listOf(
            PermissionStatus("Microphone", isGranted(context, android.Manifest.permission.RECORD_AUDIO)),
            PermissionStatus("Phone state", isGranted(context, android.Manifest.permission.READ_PHONE_STATE)),
            PermissionStatus("Phone calls", isGranted(context, android.Manifest.permission.CALL_PHONE)),
            PermissionStatus("Notifications (optional)", hasNotificationPermission(context))
        )
        _uiState.update { it.copy(permissionStatuses = statuses) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themeStore.setThemeMode(mode) }
    }

    fun setIncludeDirectionInFileName(include: Boolean) {
        updateSettings { it.copy(includeDirectionInFileName = include) }
    }

    fun setAutoDeleteInterval(interval: AutoDeleteInterval) {
        updateSettings { it.copy(autoDeleteInterval = interval) }
    }

    fun setMaxStorageBytes(bytes: Long?) {
        updateSettings { it.copy(maxStorageBytes = bytes) }
    }

    fun setNotifyOnFinish(notify: Boolean) {
        updateSettings { it.copy(notifyOnFinish = notify) }
    }

    fun setForceSpeakerphoneWhileRecording(enabled: Boolean) {
        updateSettings { it.copy(forceSpeakerphoneWhileRecording = enabled) }
    }

    /** Pass null to clear it and go back to app-private storage only. */
    fun setCustomFolderUri(uriString: String?) {
        updateSettings { it.copy(customFolderUri = uriString) }
    }

    fun onFolderPermissionFailed() {
        _uiState.update { it.copy(message = "Couldn't get permission for that folder — try a different one") }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun resolveFolderDisplayName(uriString: String): String? = runCatching {
        DocumentFile.fromTreeUri(getApplication(), Uri.parse(uriString))?.name
    }.getOrNull()

    private fun updateSettings(transform: (RecordingSettings) -> RecordingSettings) {
        viewModelScope.launch { settingsStore?.update(transform) }
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(context, android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true // no runtime notification permission existed before API 33
        }

    private fun readAppVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")
}
