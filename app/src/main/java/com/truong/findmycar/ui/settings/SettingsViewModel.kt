package com.truong.findmycar.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.truong.findmycar.data.AppSettings
import com.truong.findmycar.data.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsStore: SettingsStore
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AppSettings()
    )

    fun setAutoDetectEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoDetectEnabled(enabled) }
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setHapticFeedbackEnabled(enabled) }
    }

    fun setUserHeightM(heightM: Float) {
        viewModelScope.launch { settingsStore.setUserHeightM(heightM) }
    }

    fun setFloorHeightM(heightM: Float) {
        viewModelScope.launch { settingsStore.setFloorHeightM(heightM) }
    }

    class Factory(
        private val settingsStore: SettingsStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(settingsStore) as T
        }
    }
}
