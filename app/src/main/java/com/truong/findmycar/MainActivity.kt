package com.truong.findmycar

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.truong.findmycar.ui.navigation.FindMyCarNavHost
import com.truong.findmycar.ui.theme.FindMyCarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        val container = (application as FindMyCarApp).container
        setContent {
            FindMyCarTheme(darkTheme = true) {
                FindMyCarNavHost(container = container)
            }
        }
    }
}
