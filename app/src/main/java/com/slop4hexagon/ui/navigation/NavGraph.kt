package com.slop4hexagon.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.slop4hexagon.ui.screens.benchmark.BenchmarkScreen
import com.slop4hexagon.ui.screens.generate.GenerateScreen
import com.slop4hexagon.ui.screens.library.LibraryScreen
import com.slop4hexagon.ui.screens.settings.SettingsScreen

sealed class Destination(val route: String, val label: String, val icon: String) {
    data object Generate : Destination("generate", "Generate", "auto_awesome")
    data object Library : Destination("library", "Models", "folder")
    data object Benchmark : Destination("benchmark", "Benchmark", "speed")
    data object Settings : Destination("settings", "Settings", "settings")
}

val bottomNavItems = listOf(Destination.Generate, Destination.Library, Destination.Benchmark, Destination.Settings)

@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Destination.Generate.route) {
        composable(Destination.Generate.route) { GenerateScreen() }
        composable(Destination.Library.route) { LibraryScreen() }
        composable(Destination.Benchmark.route) { BenchmarkScreen() }
        composable(Destination.Settings.route) { SettingsScreen() }
    }
}
