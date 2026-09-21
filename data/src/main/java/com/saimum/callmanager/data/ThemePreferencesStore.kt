package com.saimum.callmanager.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { LIGHT, DARK, SYSTEM }

private val Context.themeDataStore by preferencesDataStore(name = "theme_preferences")
private const val TAG = "ThemePreferencesStore"

/**
 * No interface here on purpose — unlike RecordingPreferencesStore etc.,
 * nothing outside the UI layer (no Service, no OS-instantiated component)
 * ever needs theme mode, so there's no dependency-direction problem to
 * solve with an interface in a feature module. The app module already
 * depends on data directly, so it just uses this concrete class.
 */
class ThemePreferencesStore(private val context: Context) {

    private val key = stringPreferencesKey("theme_mode")

    val themeMode: Flow<ThemeMode> = context.themeDataStore.data.recoverFromReadErrors(TAG).map { prefs ->
        prefs[key]?.let { name ->
            runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
        } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) = safeWrite(TAG) {
        context.themeDataStore.edit { it[key] = mode.name }
    }
}
