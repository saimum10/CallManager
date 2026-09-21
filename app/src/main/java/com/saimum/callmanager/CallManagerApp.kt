package com.saimum.callmanager

import android.app.Application
import android.util.Log
import com.saimum.callmanager.data.DataStoreForwardingPreferencesStore
import com.saimum.callmanager.data.DataStoreRecordingPreferencesStore
import com.saimum.callmanager.data.DataStoreRecordingSettingsStore
import com.saimum.callmanager.data.RoomRecordingLibraryStore
import com.saimum.callmanager.recording.RecordingMaintenance
import com.saimum.callmanager.recording.RecordingServiceLocator
import com.saimum.callmanager.recording.RecordingStorage
import com.saimum.callmanager.telephony.TelephonyServiceLocator
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CallManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val libraryStore = RoomRecordingLibraryStore(applicationContext)
        val settingsStore = DataStoreRecordingSettingsStore(applicationContext)

        RecordingServiceLocator.preferencesStore = DataStoreRecordingPreferencesStore(applicationContext)
        RecordingServiceLocator.libraryStore = libraryStore
        RecordingServiceLocator.settingsStore = settingsStore
        TelephonyServiceLocator.forwardingPreferencesStore = DataStoreForwardingPreferencesStore(applicationContext)

        // One-shot startup sweep: catches age/storage-limit expiry even on
        // days with no new recording. The other trigger is right after each
        // recording finishes (see CallRecordingService) — deliberately not a
        // WorkManager periodic job, to keep dependencies minimal; see README.
        //
        // The exception handler is essential here, not optional: without it,
        // any failure in this sweep (a Room hiccup, a full disk, anything)
        // would crash the app on every single launch, since this runs
        // unconditionally in Application.onCreate().
        val startupExceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Log.e("CallManagerApp", "Startup retention sweep failed", throwable)
        }
        CoroutineScope(Dispatchers.IO + startupExceptionHandler).launch {
            val settings = settingsStore.settings.first()
            val maintenance = RecordingMaintenance(libraryStore)
            // Adopt recordings orphaned by a crash before applying retention,
            // so they count toward the storage limit and age rules too.
            maintenance.recoverOrphanedRecordings(RecordingStorage.recordingsDir(applicationContext))
            maintenance.applyRetentionPolicies(settings)
        }
    }
}
