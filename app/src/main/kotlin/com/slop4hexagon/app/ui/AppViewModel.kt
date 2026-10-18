package com.slop4hexagon.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.slop4hexagon.app.AppContainer
import com.slop4hexagon.app.SlopApplication

/** The container every screen's ViewModel is built from. */
@Composable
fun rememberContainer(): AppContainer =
    (LocalContext.current.applicationContext as SlopApplication).container

/**
 * Manual ViewModel factory: four screens do not justify a DI framework, but
 * ViewModels must still own their state across configuration changes.
 */
@Composable
inline fun <reified T : ViewModel> appViewModel(crossinline create: (AppContainer) -> T): T {
    val container = rememberContainer()
    return viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = create(container) as V
        },
    )
}
