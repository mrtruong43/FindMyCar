package com.truong.findmycar.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.truong.findmycar.data.ParkingRepository
import com.truong.findmycar.data.SettingsStore
import com.truong.findmycar.data.db.ParkingSpotEntity
import com.truong.findmycar.detect.ParkingDetector
import com.truong.findmycar.detect.SaveSpotResult
import com.truong.findmycar.tracking.TrackingStateHolder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val activeSpot: ParkingSpotEntity? = null,
    val isSaving: Boolean = false,
    val isTracking: Boolean = false,
    val trackingElapsedSeconds: Long = 0L,
    val stepCount: Int = 0,
    val autoDetectEnabled: Boolean = true,
    val barometerNoticeShown: Boolean = false
)

class HomeViewModel(
    private val repository: ParkingRepository,
    private val parkingDetector: ParkingDetector,
    private val settingsStore: SettingsStore
) : ViewModel() {

    private val isSavingFlow = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        repository.activeSpot,
        isSavingFlow,
        TrackingStateHolder.isTracking,
        TrackingStateHolder.elapsedSeconds,
        TrackingStateHolder.telemetry,
        settingsStore.settings
    ) { values ->
        val spot = values[0] as ParkingSpotEntity?
        val isSaving = values[1] as Boolean
        val isTracking = values[2] as Boolean
        val elapsedSec = values[3] as Long
        val telemetry = values[4] as com.truong.findmycar.tracking.TrackingTelemetry
        val settings = values[5] as com.truong.findmycar.data.AppSettings
        HomeUiState(
            activeSpot = spot,
            isSaving = isSaving,
            isTracking = isTracking,
            trackingElapsedSeconds = elapsedSec,
            stepCount = telemetry.stepCount,
            autoDetectEnabled = settings.autoDetectEnabled,
            barometerNoticeShown = settings.barometerNoticeShown
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HomeUiState()
    )

    private val _saveEvents = MutableSharedFlow<SaveSpotResult>(extraBufferCapacity = 1)
    val saveEvents: SharedFlow<SaveSpotResult> = _saveEvents.asSharedFlow()

    private val _anchorActionMessageRes = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val anchorActionMessageRes: SharedFlow<Int> = _anchorActionMessageRes.asSharedFlow()

    fun saveManualSpot() {
        if (isSavingFlow.value) return
        viewModelScope.launch {
            isSavingFlow.value = true
            try {
                val result = parkingDetector.saveManualSpot()
                _saveEvents.tryEmit(result)
            } finally {
                isSavingFlow.value = false
            }
        }
    }

    fun markAnchorReached(spotId: Long) {
        viewModelScope.launch {
            repository.markAnchorSet(spotId)
        }
    }

    fun updateAnchorHere(spot: ParkingSpotEntity) {
        viewModelScope.launch {
            if (parkingDetector.updateAnchorToCurrentPosition(spot)) {
                _anchorActionMessageRes.tryEmit(com.truong.findmycar.R.string.anchor_updated_toast)
            }
        }
    }

    fun clearAnchor(spotId: Long) {
        viewModelScope.launch {
            repository.clearAnchor(spotId)
            _anchorActionMessageRes.tryEmit(com.truong.findmycar.R.string.anchor_cleared_toast)
        }
    }

    fun markBarometerNoticeShown() {
        viewModelScope.launch {
            settingsStore.setBarometerNoticeShown(true)
        }
    }

    class Factory(
        private val repository: ParkingRepository,
        private val parkingDetector: ParkingDetector,
        private val settingsStore: SettingsStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository, parkingDetector, settingsStore) as T
        }
    }
}
