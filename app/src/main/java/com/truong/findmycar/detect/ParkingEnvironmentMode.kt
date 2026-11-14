package com.truong.findmycar.detect

enum class ParkingEnvironmentMode {
    AUTO,
    UNDERGROUND,
    OUTDOOR
}

/**
 * Pure Kotlin resolver for parking environment mode selection.
 */
object ParkingEnvironmentResolver {
    const val OUTDOOR_ACCURACY_THRESHOLD_M = 20f

    fun resolve(mode: ParkingEnvironmentMode, accuracyM: Float?): ParkingEnvironmentMode {
        return when (mode) {
            ParkingEnvironmentMode.UNDERGROUND -> ParkingEnvironmentMode.UNDERGROUND
            ParkingEnvironmentMode.OUTDOOR -> ParkingEnvironmentMode.OUTDOOR
            ParkingEnvironmentMode.AUTO -> {
                if (accuracyM != null && accuracyM <= OUTDOOR_ACCURACY_THRESHOLD_M) {
                    ParkingEnvironmentMode.OUTDOOR
                } else {
                    ParkingEnvironmentMode.UNDERGROUND
                }
            }
        }
    }

    fun shouldStartTrackingService(resolvedMode: ParkingEnvironmentMode): Boolean {
        return resolvedMode == ParkingEnvironmentMode.UNDERGROUND
    }

    fun fromStorageName(raw: String?): ParkingEnvironmentMode {
        if (raw.isNullOrBlank()) return ParkingEnvironmentMode.AUTO
        return runCatching { ParkingEnvironmentMode.valueOf(raw) }
            .getOrDefault(ParkingEnvironmentMode.AUTO)
    }
}
