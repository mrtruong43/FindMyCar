package com.truong.findmycar

import android.app.Application
import com.truong.findmycar.data.ParkingRepository
import com.truong.findmycar.data.SettingsStore
import com.truong.findmycar.data.db.AppDatabase
import com.truong.findmycar.detect.ActivityTransitionManager
import com.truong.findmycar.detect.ParkingDetector
import com.truong.findmycar.util.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(application: Application) {
    val database: AppDatabase by lazy { AppDatabase.getInstance(application) }
    val repository: ParkingRepository by lazy { ParkingRepository(database.parkingSpotDao()) }
    val settingsStore: SettingsStore by lazy { SettingsStore(application) }
    val parkingDetector: ParkingDetector by lazy { ParkingDetector(application, repository) }
}

class FindMyCarApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            val autoDetect = container.settingsStore.settings.first().autoDetectEnabled
            if (autoDetect && Permissions.hasActivityRecognition(this@FindMyCarApp)) {
                ActivityTransitionManager.register(this@FindMyCarApp)
            }
        }
    }
}
