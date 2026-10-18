package com.slop4hexagon.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Chipline
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.slop4hexagon.app.ui.benchmark.BenchmarkScreen
import com.slop4hexagon.app.ui.generate.GenerateScreen
import com.slop4hexagon.app.ui.library.ModelDetailsScreen
import com.slop4hexagon.app.ui.library.ModelLibraryScreen
import com.slop4hexagon.app.ui.settings.SettingsScreen

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    GENERATE("generate", "Generate", Icons.Default.Chipline),
    LIBRARY("library", "Models", Icons.Default.LibraryBooks),
    BENCHMARK("benchmark", "Benchmark", Icons.Default.Bolt),
    SETTINGS("settings", "Settings", Icons.Default.Tune),
}

private const val DETAILS_ROUTE = "model/{modelId}"

@Composable
fun SlopApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.GENERATE.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.GENERATE.route) {
                GenerateScreen(onOpenLibrary = { navController.navigate(Tab.LIBRARY.route) })
            }
            composable(Tab.LIBRARY.route) {
                ModelLibraryScreen(onOpenDetails = { id -> navController.navigate("model/$id") })
            }
            composable(DETAILS_ROUTE) { entry ->
                ModelDetailsScreen(modelId = entry.arguments?.getString("modelId"), onBack = { navController.popBackStack() })
            }
            composable(Tab.BENCHMARK.route) { BenchmarkScreen() }
            composable(Tab.SETTINGS.route) { SettingsScreen() }
        }
    }
}
