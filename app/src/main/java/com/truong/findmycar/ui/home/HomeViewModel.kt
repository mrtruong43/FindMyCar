package com.truong.findmycar.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.truong.findmycar.data.ParkingRepository
import com.truong.findmycar.data.SettingsStore
import com.truong.findmycar.data.VehicleHologramType
import com.truong.findmycar.data.db.ParkingSpotEntity
import com.truong.findmycar.data.db.TrackPointEntity
import com.truong.findmycar.detect.ParkingDetector
import com.truong.findmycar.detect.ParkingEnvironmentMode
import com.truong.findmycar.detect.SaveSpotResult
import com.truong.findmycar.tracking.PathModel
import com.truong.findmycar.tracking.PathPoint
import com.truong.findmycar.tracking.TrackingStateHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PreSaveDraft(
    val floorTag: String? = null,
    val note: String = "",
    val photoUri: String? = null
)

data class HomeUiState(
    val activeSpot: ParkingSpotEntity? = null,
    val latestHistorySpot: ParkingSpotEntity? = null,
    val isSaving: Boolean = false,
    val isTracking: Boolean = false,
    val trackingElapsedSeconds: Long = 0L,
    val stepCount: Int = 0,
    val liveDistanceMeters: Float = 0f,
    val liveFloorDelta: Int = 0,
    val recordedStepCount: Int = 0,
    val recordedDistanceMeters: Float = 0f,
    val autoDetectEnabled: Boolean = true,
    val hapticFeedbackEnabled: Boolean = true,
    val barometerNoticeShown: Boolean = false,
    val floorHeightM: Float = 3.2f,
    val selectedEnvironmentMode: ParkingEnvironmentMode = ParkingEnvironmentMode.AUTO,
    val vehicleType: VehicleHologramType = VehicleHologramType.SCOOTER,
    val draftFloorTag: String? = null,
    val draftNote: String = "",
    val draftPhotoUri: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: ParkingRepository,
    private val parkingDetector: ParkingDetector,
    private val settingsStore: SettingsStore
) : ViewModel() {

    private val isSavingFlow = MutableStateFlow(false)
    private val selectedEnvironmentModeFlow = MutableStateFlow(ParkingEnvironmentMode.AUTO)
    private val preSaveDraftFlow = MutableStateFlow(PreSaveDraft())

    private val activeTrackPointsFlow = repository.activeSpot.flatMapLatest { spot ->
        if (spot == null) flowOf(emptyList()) else repository.observeTrackPoints(spot.id)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        repository.activeSpot,
        activeTrackPointsFlow,
        repository.historySpots.map { it.firstOrNull() },
        isSavingFlow,
        TrackingStateHolder.isTracking,
        TrackingStateHolder.elapsedSeconds,
        TrackingStateHolder.telemetry,
        settingsStore.settings,
        selectedEnvironmentModeFlow,
        preSaveDraftFlow
    ) { values ->
        val spot = values[0] as ParkingSpotEntity?
        @Suppress("UNCHECKED_CAST")
        val trackPoints = values[1] as List<TrackPointEntity>
        val latestHistory = values[2] as ParkingSpotEntity?
        val isSaving = values[3] as Boolean
        val isTracking = values[4] as Boolean
        val elapsedSec = values[5] as Long
        val telemetry = values[6] as com.truong.findmycar.tracking.TrackingTelemetry
        val settings = values[7] as com.truong.findmycar.data.AppSettings
        val selectedMode = values[8] as ParkingEnvironmentMode
        val draft = values[9] as PreSaveDraft

        val liveDist = telemetry.stepCount * settings.effectiveStepLengthM()
        val storedDist = if (trackPoints.size >= 2) {
            PathModel.totalLengthMeters(
                trackPoints.map { PathPoint(x = it.x, y = it.y, relHeightM = it.relHeightM, tMs = it.t) }
            )
        } else {
            trackPoints.size * settings.effectiveStepLengthM()
        }

        HomeUiState(
            activeSpot = spot,
            latestHistorySpot = latestHistory,
            isSaving = isSaving,
            isTracking = isTracking,
            trackingElapsedSeconds = elapsedSec,
            stepCount = telemetry.stepCount,
            liveDistanceMeters = liveDist,
            liveFloorDelta = telemetry.floorDelta,
            recordedStepCount = maxOf(trackPoints.size, if (isTracking) telemetry.stepCount else 0),
            recordedDistanceMeters = maxOf(storedDist, if (isTracking) liveDist else 0f),
            autoDetectEnabled = settings.autoDetectEnabled,
            hapticFeedbackEnabled = settings.hapticFeedbackEnabled,
            barometerNoticeShown = settings.barometerNoticeShown,
            floorHeightM = settings.floorHeightM,
            selectedEnvironmentMode = selectedMode,
            vehicleType = settings.vehicleType,
            draftFloorTag = draft.floorTag,
            draftNote = draft.note,
            draftPhotoUri = draft.photoUri
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

    fun selectEnvironmentMode(mode: ParkingEnvironmentMode) {
        selectedEnvironmentModeFlow.value = mode
    }

    fun toggleDraftFloorTag(tag: String) {
        val current = preSaveDraftFlow.value
        preSaveDraftFlow.value = current.copy(
            floorTag = if (current.floorTag == tag) null else tag
        )
    }

    fun onDraftNoteChanged(note: String) {
        preSaveDraftFlow.value = preSaveDraftFlow.value.copy(note = note)
    }

    fun setDraftPhotoUri(uri: String?) {
        preSaveDraftFlow.value = preSaveDraftFlow.value.copy(photoUri = uri)
    }

    fun saveManualSpot() {
        if (isSavingFlow.value) return
        viewModelScope.launch {
            isSavingFlow.value = true
            try {
                val mode = selectedEnvironmentModeFlow.value
                val draft = preSaveDraftFlow.value
                val trimmedNote = draft.note.trim()
                val bundledNote = when {
                    draft.floorTag != null && trimmedNote.isNotEmpty() ->
                        "${draft.floorTag} • $trimmedNote"
                    draft.floorTag != null -> draft.floorTag
                    else -> trimmedNote
                }
                val result = parkingDetector.saveManualSpot(
                    environmentMode = mode,
                    note = bundledNote,
                    photoUri = draft.photoUri
                )
                if (!result.skippedDuplicate) {
                    selectedEnvironmentModeFlow.value = ParkingEnvironmentMode.AUTO
                    preSaveDraftFlow.value = PreSaveDraft()
                }
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

    fun markActiveSpotFound(spotId: Long) {
        viewModelScope.launch {
            repository.markAsFound(spotId)
        }
    }

    fun updateActiveSpotFloorTag(spot: ParkingSpotEntity, tag: String) {
        viewModelScope.launch {
            val (currentFloor, bodyNote) = splitFloorAndNote(spot.note)
            val newFloor = if (currentFloor == tag) null else tag
            val combined = combineFloorAndNote(newFloor, bodyNote)
            repository.updateNote(spot.id, combined)
        }
    }

    fun updateActiveSpotNoteBody(spot: ParkingSpotEntity, bodyNote: String) {
        viewModelScope.launch {
            val (currentFloor, _) = splitFloorAndNote(spot.note)
            val combined = combineFloorAndNote(currentFloor, bodyNote)
            repository.updateNote(spot.id, combined)
        }
    }

    fun updateActiveSpotPhoto(spotId: Long, photoUri: String?) {
        viewModelScope.launch {
            repository.updatePhotoUri(spotId, photoUri)
        }
    }

    companion object {
        val QUICK_FLOOR_TAGS = listOf("B3", "B2", "B1", "Trệt", "Tầng 2")

        fun splitFloorAndNote(rawNote: String): Pair<String?, String> {
            val trimmed = rawNote.trim()
            if (trimmed.isEmpty()) return null to ""
            val matchedTag = QUICK_FLOOR_TAGS.firstOrNull { tag ->
                trimmed == tag || trimmed.startsWith("$tag • ")
            }
            return if (matchedTag != null) {
                val rest = trimmed.removePrefix(matchedTag).removePrefix(" • ").trimStart()
                matchedTag to rest
            } else {
                null to rawNote
            }
        }

        fun combineFloorAndNote(floorTag: String?, bodyNote: String): String {
            val cleanBody = bodyNote.trimStart()
            return when {
                floorTag != null && cleanBody.isNotEmpty() -> "$floorTag • $cleanBody"
                floorTag != null -> floorTag
                else -> bodyNote
            }
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
