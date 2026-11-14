package com.truong.findmycar.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ParkingSpotDao {

    @Query("SELECT * FROM parking_spots WHERE status = 'ACTIVE' ORDER BY savedAt DESC LIMIT 1")
    fun observeActiveSpot(): Flow<ParkingSpotEntity?>

    @Query("SELECT * FROM parking_spots WHERE status = 'ACTIVE' ORDER BY savedAt DESC LIMIT 1")
    suspend fun getActiveSpotOnce(): ParkingSpotEntity?

    @Query("SELECT * FROM parking_spots WHERE id = :id LIMIT 1")
    fun observeSpotById(id: Long): Flow<ParkingSpotEntity?>

    @Query("SELECT * FROM parking_spots WHERE id = :id LIMIT 1")
    suspend fun getSpotById(id: Long): ParkingSpotEntity?

    @Query("SELECT * FROM parking_spots ORDER BY savedAt DESC")
    fun observeAllSpots(): Flow<List<ParkingSpotEntity>>

    @Query("SELECT * FROM parking_spots WHERE status != 'ACTIVE' ORDER BY savedAt DESC")
    fun observeHistorySpots(): Flow<List<ParkingSpotEntity>>

    @Query("UPDATE parking_spots SET status = 'ARCHIVED' WHERE status = 'ACTIVE'")
    suspend fun archiveAllActive()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(spot: ParkingSpotEntity): Long

    @Transaction
    suspend fun insertAsSingleActive(spot: ParkingSpotEntity): Long {
        archiveAllActive()
        return insert(spot.copy(status = SpotStatus.ACTIVE))
    }

    @Query("UPDATE parking_spots SET note = :note WHERE id = :spotId")
    suspend fun updateNote(spotId: Long, note: String)

    @Query("UPDATE parking_spots SET photoUri = :photoUri WHERE id = :spotId")
    suspend fun updatePhotoUri(spotId: Long, photoUri: String?)

    @Query("UPDATE parking_spots SET status = :status WHERE id = :spotId")
    suspend fun updateStatus(spotId: Long, status: String)

    @Query("UPDATE parking_spots SET anchorSet = 1 WHERE id = :spotId")
    suspend fun markAnchorSet(spotId: Long)

    @Query(
        """
        UPDATE parking_spots
        SET anchorSet = 1,
            anchorLat = :anchorLat,
            anchorLon = :anchorLon,
            anchorPressureHpa = :anchorPressureHpa,
            anchorRelHeightM = :anchorRelHeightM
        WHERE id = :spotId
        """
    )
    suspend fun finalizeAnchor(
        spotId: Long,
        anchorLat: Double?,
        anchorLon: Double?,
        anchorPressureHpa: Float?,
        anchorRelHeightM: Float?
    )

    @Query("DELETE FROM parking_spots WHERE id = :spotId")
    suspend fun deleteById(spotId: Long)

    @Query("DELETE FROM parking_spots")
    suspend fun deleteAll()
}
