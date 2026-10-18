package com.slop4hexagon.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.slop4hexagon.ui.navigation.AppNavGraph
import com.slop4hexagon.ui.navigation.Destination
import com.slop4hexagon.ui.navigation.bottomNavItems
import com.slop4hexagon.ui.theme.SlopTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SlopTheme {
                val navController = rememberNavController()
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            val backStack by navController.currentBackStackEntryAsState()
                            val current = backStack?.destination?.route
                            bottomNavItems.forEach { dest ->
                                NavigationBarItem(
                                    selected = current == dest.route,
                                    onClick = {
                                        navController.navigate(dest.route) {
                                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        val icon = when (dest) {
                                            Destination.Generate -> Icons.Filled.AutoAwesome
                                            Destination.Library -> Icons.Filled.Folder
                                            Destination.Benchmark -> Icons.Filled.Speed
                                            Destination.Settings -> Icons.Filled.Settings
                                        }
                                        Icon(icon, contentDescription = dest.label)
                                    },
                                    label = { Text(dest.label) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
                        AppNavGraph(navController)
                    }
                }
            }
        }
    }
}
