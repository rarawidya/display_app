package com.example.displayapp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.displayapp.domain.model.AppSettings
import com.example.displayapp.domain.model.ThemeSettings
import com.example.displayapp.domain.repository.AppPreferencesRepository
import com.example.displayapp.domain.repository.ThemeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Activity-scoped hub for the two cross-cutting preference flows.
 *
 * - [themeSettings] drives [com.example.displayapp.ui.theme.DisplayAppTheme]
 *   at the very root of the composition tree.
 * - [appSettings] is exposed through [com.example.displayapp.presentation.ui.common.LocalAppSettings]
 *   so any composable can read units / time format directly.
 *
 * Both flows are [SharingStarted.Eagerly] so the first composition has real
 * values instead of briefly flashing defaults — there's only ever one of each
 * Activity instance, so the cost is one in-memory subscription.
 */
class RootViewModel(
    themeRepository: ThemeRepository,
    appPreferencesRepository: AppPreferencesRepository
) : ViewModel() {

    val themeSettings: StateFlow<ThemeSettings> = themeRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ThemeSettings()
    )

    val appSettings: StateFlow<AppSettings> = appPreferencesRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings()
    )
}

class RootViewModelFactory(
    private val themeRepository: ThemeRepository,
    private val appPreferencesRepository: AppPreferencesRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RootViewModel::class.java)) {
            return RootViewModel(themeRepository, appPreferencesRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
