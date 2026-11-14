package com.truong.findmycar

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.truong.findmycar.detect.ParkingDetector
import com.truong.findmycar.ui.navigation.FindMyCarNavHost
import com.truong.findmycar.ui.theme.FindMyCarTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val pendingDetailSpotId = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractDeepLink(intent)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        val container = (application as FindMyCarApp).container
        setContent {
            val detailSpotId by pendingDetailSpotId.collectAsStateWithLifecycle()
            FindMyCarTheme(darkTheme = true) {
                FindMyCarNavHost(
                    container = container,
                    pendingDetailSpotId = detailSpotId,
                    onPendingDetailConsumed = { pendingDetailSpotId.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractDeepLink(intent)
    }

    private fun extractDeepLink(intent: Intent?) {
        val spotId = intent?.getLongExtra(ParkingDetector.EXTRA_OPEN_SPOT_DETAIL_ID, -1L) ?: -1L
        if (spotId > 0L) {
            pendingDetailSpotId.value = spotId
            intent?.removeExtra(ParkingDetector.EXTRA_OPEN_SPOT_DETAIL_ID)
        }
    }
}
