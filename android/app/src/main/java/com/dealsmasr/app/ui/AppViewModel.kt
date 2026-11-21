package com.dealsmasr.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dealsmasr.app.AppContainer
import com.dealsmasr.app.DealsApp

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as DealsApp).container

/** viewModel() with access to the app container (manual DI, no Hilt). */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline factory: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(
        key = key,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = factory(container) as T
        },
    )
}
