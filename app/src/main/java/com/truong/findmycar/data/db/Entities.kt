package com.truong.findmycar.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object SpotSource {
    const val AUTO_ACTIVITY = "AUTO_ACTIVITY"
    const val MANUAL = "MANUAL"
}

object SpotStatus {
    const val ACTIVE = "ACTIVE"
    const val FOUND = "FOUND"
    const val ARCHIVED = "ARCHIVED"
}

@Entity(tableName = "parking_spots")
data class ParkingSpotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val savedAt: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Float? = null,
    val pressureHpa: Float? = null,
    val note: String = "",
    val photoUri: String? = null,
    val source: String = SpotSource.MANUAL,
    val status: String = SpotStatus.ACTIVE,
    val anchorSet: Boolean = false,
    val anchorLat: Double? = null,
    val anchorLon: Double? = null,
    val anchorPressureHpa: Float? = null,
    val anchorRelHeightM: Float? = null,
    val environmentMode: String = "AUTO"
)

@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = ParkingSpotEntity::class,
            parentColumns = ["id"],
            childColumns = ["spotId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["spotId"])]
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val spotId: Long,
    val seq: Int,
    val x: Float,
    val y: Float,
    val relHeightM: Float,
    val t: Long
)
