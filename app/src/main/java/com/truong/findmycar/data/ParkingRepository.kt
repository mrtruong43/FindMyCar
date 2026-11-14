package com.truong.findmycar.data

import com.truong.findmycar.data.db.ParkingSpotDao
import com.truong.findmycar.data.db.ParkingSpotEntity
import com.truong.findmycar.data.db.SpotSource
import com.truong.findmycar.data.db.SpotStatus
import com.truong.findmycar.data.db.TrackPointDao
import com.truong.findmycar.data.db.TrackPointEntity
import kotlinx.coroutines.flow.Flow

class ParkingRepository(
    private val parkingSpotDao: ParkingSpotDao,
    private val trackPointDao: TrackPointDao
) {
    val activeSpot: Flow<ParkingSpotEntity?> = parkingSpotDao.observeActiveSpot()

    val historySpots: Flow<List<ParkingSpotEntity>> = parkingSpotDao.observeHistorySpots()

    suspend fun getActiveSpotOnce(): ParkingSpotEntity? =
        parkingSpotDao.getActiveSpotOnce()

    fun observeSpotById(spotId: Long): Flow<ParkingSpotEntity?> =
        parkingSpotDao.observeSpotById(spotId)

    suspend fun getSpotById(spotId: Long): ParkingSpotEntity? =
        parkingSpotDao.getSpotById(spotId)

    /**
     * Atomically transitions any existing ACTIVE spot to ARCHIVED and inserts the new spot as ACTIVE.
     */
    suspend fun saveNewSpot(
        lat: Double?,
        lon: Double?,
        accuracyM: Float?,
        pressureHpa: Float?,
        source: String = SpotSource.MANUAL,
        note: String = "",
        photoUri: String? = null,
        savedAt: Long = System.currentTimeMillis(),
        environmentMode: String = "AUTO"
    ): Long {
        val entity = ParkingSpotEntity(
            savedAt = savedAt,
            lat = lat,
            lon = lon,
            accuracyM = accuracyM,
            pressureHpa = pressureHpa,
            note = note,
            photoUri = photoUri,
            source = source,
            status = SpotStatus.ACTIVE,
            environmentMode = environmentMode
        )
        return parkingSpotDao.insertAsSingleActive(entity)
    }

    suspend fun updateNote(spotId: Long, note: String) {
        parkingSpotDao.updateNote(spotId, note)
    }

    suspend fun updatePhotoUri(spotId: Long, photoUri: String?) {
        parkingSpotDao.updatePhotoUri(spotId, photoUri)
    }

    suspend fun markAsFound(spotId: Long) {
        parkingSpotDao.updateStatus(spotId, SpotStatus.FOUND)
    }

    suspend fun markAnchorSet(spotId: Long) {
        parkingSpotDao.markAnchorSet(spotId)
    }

    suspend fun finalizeAnchor(
        spotId: Long,
        anchorLat: Double?,
        anchorLon: Double?,
        anchorPressureHpa: Float?,
        anchorRelHeightM: Float?
    ) {
        parkingSpotDao.finalizeAnchor(
            spotId = spotId,
            anchorLat = anchorLat,
            anchorLon = anchorLon,
            anchorPressureHpa = anchorPressureHpa,
            anchorRelHeightM = anchorRelHeightM
        )
    }

    suspend fun clearAnchor(spotId: Long) {
        parkingSpotDao.clearAnchor(spotId)
    }

    suspend fun insertTrackPoints(points: List<TrackPointEntity>) {
        if (points.isNotEmpty()) {
            trackPointDao.insertAll(points)
        }
    }

    fun observeTrackPoints(spotId: Long): Flow<List<TrackPointEntity>> =
        trackPointDao.observeBySpotId(spotId)

    suspend fun getTrackPoints(spotId: Long): List<TrackPointEntity> =
        trackPointDao.getBySpotId(spotId)

    suspend fun deleteSpot(spotId: Long) {
        parkingSpotDao.deleteById(spotId)
    }

    suspend fun deleteAllData() {
        parkingSpotDao.deleteAll()
    }
}
