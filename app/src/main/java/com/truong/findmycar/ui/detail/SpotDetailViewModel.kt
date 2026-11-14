package com.truong.findmycar.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.truong.findmycar.data.ParkingRepository
import com.truong.findmycar.data.db.ParkingSpotEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SpotDetailUiState(
    val spot: ParkingSpotEntity? = null,
    val noteDraft: String = "",
    val isNoteDirty: Boolean = false
)

class SpotDetailViewModel(
    private val spotId: Long,
    private val repository: ParkingRepository
) : ViewModel() {

    private val noteDraftFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SpotDetailUiState> = combine(
        repository.observeSpotById(spotId),
        noteDraftFlow
    ) { spot, draft ->
        val currentDraft = draft ?: spot?.note.orEmpty()
        val isDirty = spot != null && currentDraft != spot.note
        SpotDetailUiState(
            spot = spot,
            noteDraft = currentDraft,
            isNoteDirty = isDirty
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SpotDetailUiState()
    )

    fun onNoteChanged(newText: String) {
        noteDraftFlow.value = newText
    }

    fun appendVoiceNote(recognizedText: String) {
        val current = uiState.value.noteDraft.trim()
        val updated = if (current.isEmpty()) recognizedText else "$current\n$recognizedText"
        noteDraftFlow.value = updated
        viewModelScope.launch {
            repository.updateNote(spotId, updated)
        }
    }

    fun saveNote() {
        val text = uiState.value.noteDraft
        viewModelScope.launch {
            repository.updateNote(spotId, text)
            noteDraftFlow.value = null
        }
    }

    fun savePhotoUri(uriString: String) {
        viewModelScope.launch {
            repository.updatePhotoUri(spotId, uriString)
        }
    }

    class Factory(
        private val spotId: Long,
        private val repository: ParkingRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SpotDetailViewModel(spotId, repository) as T
        }
    }
}
