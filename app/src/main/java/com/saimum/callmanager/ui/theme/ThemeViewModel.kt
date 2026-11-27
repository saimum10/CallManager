package com.saimum.callmanager.ui.theme

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saimum.callmanager.data.ThemeMode
import com.saimum.callmanager.data.ThemePreferencesStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ThemeViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ThemePreferencesStore(application)

    val themeMode: StateFlow<ThemeMode> = store.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ThemeMode.SYSTEM
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { store.setThemeMode(mode) }
    }
}
