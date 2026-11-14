package com.truong.findmycar.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class TrackingTelemetry(
    val stepCount: Int = 0,
    val headingDeg: Float = 0f,
    val headingMode: HeadingMode = HeadingMode.HAND_HELD,
    val pcaRatio: Float = 1f,
    val pressureHpa: Float? = null,
    val relHeightM: Float = 0f,
    val floorDelta: Int = 0,
    val x: Float = 0f,
    val y: Float = 0f
)

object TrackingStateHolder {
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _activeSpotId = MutableStateFlow<Long?>(null)
    val activeSpotId: StateFlow<Long?> = _activeSpotId.asStateFlow()

    private val _telemetry = MutableStateFlow(TrackingTelemetry())
    val telemetry: StateFlow<TrackingTelemetry> = _telemetry.asStateFlow()

    private val _isRecordingCsv = MutableStateFlow(false)
    val isRecordingCsv: StateFlow<Boolean> = _isRecordingCsv.asStateFlow()

    private val _csvRowCount = MutableStateFlow(0)
    val csvRowCount: StateFlow<Int> = _csvRowCount.asStateFlow()

    private val csvLock = Any()
    private val csvRows = ArrayList<String>()

    fun setStarted(spotId: Long) {
        _activeSpotId.value = spotId
        _elapsedSeconds.value = 0L
        _telemetry.value = TrackingTelemetry()
        _isTracking.value = true
    }

    fun updateElapsedSeconds(seconds: Long) {
        _elapsedSeconds.value = seconds
    }

    fun updateTelemetry(update: (TrackingTelemetry) -> TrackingTelemetry) {
        val next = update(_telemetry.value)
        _telemetry.value = next
        if (_isRecordingCsv.value) {
            appendCsvRow(event = "UPDATE", t = next)
        }
    }

    fun recordStepCsv(t: TrackingTelemetry) {
        if (_isRecordingCsv.value) {
            appendCsvRow(event = "STEP", t = t)
        }
    }

    fun setCsvRecording(enabled: Boolean) {
        if (enabled) {
            synchronized(csvLock) {
                csvRows.clear()
                _csvRowCount.value = 0
            }
        }
        _isRecordingCsv.value = enabled
    }

    fun buildCsvContent(): String? {
        synchronized(csvLock) {
            if (csvRows.isEmpty()) return null
            val sb = StringBuilder()
            sb.appendLine("timestamp_ms,event,steps,x_m,y_m,heading_deg,mode,pca_ratio,pressure_hpa,rel_height_m,floor_delta")
            for (row in csvRows) {
                sb.appendLine(row)
            }
            return sb.toString()
        }
    }

    private fun appendCsvRow(event: String, t: TrackingTelemetry) {
        val line = String.format(
            Locale.US,
            "%d,%s,%d,%.3f,%.3f,%.2f,%s,%.2f,%.2f,%.3f,%d",
            System.currentTimeMillis(),
            event,
            t.stepCount,
            t.x,
            t.y,
            t.headingDeg,
            t.headingMode.name,
            t.pcaRatio,
            t.pressureHpa ?: -1f,
            t.relHeightM,
            t.floorDelta
        )
        synchronized(csvLock) {
            if (csvRows.size >= MAX_CSV_ROWS) {
                csvRows.removeAt(0)
            }
            csvRows.add(line)
            _csvRowCount.value = csvRows.size
        }
    }

    fun setStopped() {
        _isTracking.value = false
        _elapsedSeconds.value = 0L
        _activeSpotId.value = null
    }

    private const val MAX_CSV_ROWS = 20_000
}
