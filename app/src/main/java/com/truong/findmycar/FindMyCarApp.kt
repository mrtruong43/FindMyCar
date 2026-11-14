package com.truong.findmycar

import android.app.Application
import com.truong.findmycar.data.ParkingRepository
import com.truong.findmycar.data.SettingsStore
import com.truong.findmycar.data.db.AppDatabase
import com.truong.findmycar.detect.ParkingDetector

class AppContainer(application: Application) {
    val database: AppDatabase by lazy { AppDatabase.getInstance(application) }
    val repository: ParkingRepository by lazy { ParkingRepository(database.parkingSpotDao()) }
    val settingsStore: SettingsStore by lazy { SettingsStore(application) }
    val parkingDetector: ParkingDetector by lazy { ParkingDetector(application, repository) }
}

class FindMyCarApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
