package com.example.displayapp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.displayapp.domain.model.ThemeMode
import com.example.displayapp.domain.model.ThemeSettings
import com.example.displayapp.domain.repository.ThemeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Activity-scoped VM exposing the current [ThemeSettings] as a [StateFlow].
 *
 * Hosted at the [com.example.displayapp.MainActivity] level so theme changes
 * recompose only the root MaterialTheme — child screens read tokens via
 * [androidx.compose.material3.MaterialTheme] and recompose only on the
 * specific color reads they perform.
 */
class ThemeViewModel(
    private val repository: ThemeRepository
) : ViewModel() {

    /**
     * Eagerly started so MainActivity's first composition has a real value rather
     * than briefly flashing the default ThemeSettings while DataStore reads.
     */
    val settings: StateFlow<ThemeSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ThemeSettings()
    )

    fun setMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { repository.setDynamicColor(enabled) }
    }
}

class ThemeViewModelFactory(
    private val repository: ThemeRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ThemeViewModel::class.java)) {
            return ThemeViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
