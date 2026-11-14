package com.truong.findmycar.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TrackingStateHolder {
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _activeSpotId = MutableStateFlow<Long?>(null)
    val activeSpotId: StateFlow<Long?> = _activeSpotId.asStateFlow()

    fun setStarted(spotId: Long) {
        _activeSpotId.value = spotId
        _elapsedSeconds.value = 0L
        _isTracking.value = true
    }

    fun updateElapsedSeconds(seconds: Long) {
        _elapsedSeconds.value = seconds
    }

    fun setStopped() {
        _isTracking.value = false
        _elapsedSeconds.value = 0L
        _activeSpotId.value = null
    }
}
