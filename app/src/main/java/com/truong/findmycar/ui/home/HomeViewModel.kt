package com.truong.findmycar.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.truong.findmycar.data.ParkingRepository
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
    val trackingElapsedSeconds: Long = 0L
)

class HomeViewModel(
    private val repository: ParkingRepository,
    private val parkingDetector: ParkingDetector
) : ViewModel() {

    private val isSavingFlow = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        repository.activeSpot,
        isSavingFlow,
        TrackingStateHolder.isTracking,
        TrackingStateHolder.elapsedSeconds
    ) { spot, isSaving, isTracking, elapsedSec ->
        HomeUiState(
            activeSpot = spot,
            isSaving = isSaving,
            isTracking = isTracking,
            trackingElapsedSeconds = elapsedSec
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HomeUiState()
    )

    private val _saveEvents = MutableSharedFlow<SaveSpotResult>(extraBufferCapacity = 1)
    val saveEvents: SharedFlow<SaveSpotResult> = _saveEvents.asSharedFlow()

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

    class Factory(
        private val repository: ParkingRepository,
        private val parkingDetector: ParkingDetector
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository, parkingDetector) as T
        }
    }
}
