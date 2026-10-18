package com.slop4hexagon.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.slop4hexagon.app.ui.SlopApp
import com.slop4hexagon.app.ui.SlopTheme
import com.slop4hexagon.app.settings.SettingsRepository

/**
 * Single-activity Compose host. The theme inputs come from the persisted
 * settings flow so light/dark and dynamic colour follow the user's choice.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SlopApplication).container
        setContent {
            val settings by container.settings.settings.collectAsState()
            SlopTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                SlopApp()
            }
        }
    }
}
