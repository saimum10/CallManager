package com.saimum.callmanager.data

import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * Runs a DataStore/Room write defensively: logs and swallows any exception
 * (disk full, storage failure, DB lock, corruption, whatever) instead of
 * letting it propagate and crash the app.
 *
 * This matters more than it might look: these writes are called from
 * ViewModels (crashes the visible UI) but also from CallRecordingService,
 * often in the middle of an active phone call — a background coroutine
 * crash there kills the whole app mid-call. A dropped write in a rare
 * storage-failure scenario is a far better outcome than that.
 */
internal suspend fun safeWrite(tag: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        Log.e(tag, "Write failed — change was not persisted", e)
    }
}

/**
 * The read-side counterpart. DataStore's `.data` Flow is documented to
 * throw [IOException] on read failures (corrupted file, disk error, etc.)
 * — every collector of that Flow needs to handle it or crash. This is the
 * standard AndroidX-recommended recovery: fall back to empty preferences
 * on an IOException, but let any other (genuinely unexpected) exception
 * through rather than silently masking real bugs.
 */
internal fun Flow<Preferences>.recoverFromReadErrors(tag: String): Flow<Preferences> = catch { exception ->
    if (exception is IOException) {
        Log.e(tag, "Failed to read preferences, falling back to defaults", exception)
        emit(emptyPreferences())
    } else {
        throw exception
    }
}
