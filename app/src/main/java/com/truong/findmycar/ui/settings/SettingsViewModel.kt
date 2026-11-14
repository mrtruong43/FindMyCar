package com.truong.findmycar.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.truong.findmycar.data.AppSettings
import com.truong.findmycar.data.ParkingRepository
import com.truong.findmycar.data.SettingsStore
import com.truong.findmycar.data.VehicleHologramType
import com.truong.findmycar.detect.ActivityTransitionManager
import com.truong.findmycar.tracking.TrackingService
import com.truong.findmycar.util.PhotoUtils
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val appContext: Context,
    private val settingsStore: SettingsStore,
    private val repository: ParkingRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AppSettings()
    )

    fun setAutoDetectEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAutoDetectEnabled(enabled)
            if (enabled) {
                ActivityTransitionManager.register(appContext)
            } else {
                ActivityTransitionManager.unregister(appContext)
            }
        }
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

    fun setCalibratedStepLengthM(stepLengthM: Float?) {
        viewModelScope.launch { settingsStore.setCalibratedStepLengthM(stepLengthM) }
    }

    fun setVehicleType(type: VehicleHologramType) {
        viewModelScope.launch { settingsStore.setVehicleType(type) }
    }

    fun clearAllData(onDone: () -> Unit) {
        viewModelScope.launch {
            TrackingService.stop(appContext)
            PhotoUtils.deleteAllPhotos(appContext)
            repository.deleteAllData()
            onDone()
        }
    }

    class Factory(
        private val context: Context,
        private val settingsStore: SettingsStore,
        private val repository: ParkingRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(context.applicationContext, settingsStore, repository) as T
        }
    }
}
