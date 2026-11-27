package com.saimum.callmanager.recording

import android.content.Context
import java.io.File

/**
 * The one place that decides where recordings live, shared by the service
 * that writes them and the startup sweep that recovers stray files.
 */
object RecordingStorage {
    /**
     * App-specific external storage (needs no permission), falling back to
     * internal storage when external storage is unavailable —
     * getExternalFilesDir() can return null.
     */
    fun recordingsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "CallRecordings")
}
