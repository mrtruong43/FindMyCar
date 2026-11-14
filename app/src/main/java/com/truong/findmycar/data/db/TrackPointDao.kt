package com.truong.findmycar.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackPointDao {

    @Query("SELECT * FROM track_points WHERE spotId = :spotId ORDER BY seq ASC")
    fun observeBySpotId(spotId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_points WHERE spotId = :spotId ORDER BY seq ASC")
    suspend fun getBySpotId(spotId: Long): List<TrackPointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<TrackPointEntity>)

    @Query("DELETE FROM track_points WHERE spotId = :spotId")
    suspend fun deleteBySpotId(spotId: Long)
}
