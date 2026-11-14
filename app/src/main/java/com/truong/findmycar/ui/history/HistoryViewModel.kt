package com.truong.findmycar.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.truong.findmycar.data.ParkingRepository
import com.truong.findmycar.data.SettingsStore
import com.truong.findmycar.data.db.ParkingSpotEntity
import com.truong.findmycar.util.PhotoUtils
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(
    val spots: List<ParkingSpotEntity> = emptyList(),
    val floorHeightM: Float = SettingsStore.DEFAULT_FLOOR_HEIGHT_M
)

class HistoryViewModel(
    private val appContext: Context,
    private val repository: ParkingRepository,
    settingsStore: SettingsStore
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = combine(
        repository.historySpots,
        settingsStore.settings
    ) { spots, settings ->
        HistoryUiState(
            spots = spots,
            floorHeightM = settings.floorHeightM
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HistoryUiState()
    )

    fun deleteSpot(spot: ParkingSpotEntity) {
        viewModelScope.launch {
            PhotoUtils.deletePhotoFile(appContext, spot.photoUri)
            repository.deleteSpot(spot.id)
        }
    }

    class Factory(
        private val context: Context,
        private val repository: ParkingRepository,
        private val settingsStore: SettingsStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HistoryViewModel(context.applicationContext, repository, settingsStore) as T
        }
    }
}
