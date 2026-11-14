package com.truong.findmycar.data

import com.truong.findmycar.data.db.ParkingSpotDao
import com.truong.findmycar.data.db.ParkingSpotEntity
import com.truong.findmycar.data.db.SpotSource
import com.truong.findmycar.data.db.SpotStatus
import kotlinx.coroutines.flow.Flow

class ParkingRepository(
    private val parkingSpotDao: ParkingSpotDao
) {
    val activeSpot: Flow<ParkingSpotEntity?> = parkingSpotDao.observeActiveSpot()

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
        savedAt: Long = System.currentTimeMillis()
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
            status = SpotStatus.ACTIVE
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
}
