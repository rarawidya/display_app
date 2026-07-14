package com.innodrive.evdash.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.innodrive.evdash.data.energy.EfficiencyTracker
import com.innodrive.evdash.domain.repository.VehicleRepository

class ChartsViewModelFactory(
    private val repository: VehicleRepository,
    private val efficiencyTracker: EfficiencyTracker
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChartsViewModel::class.java)) {
            return ChartsViewModel(repository, efficiencyTracker) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
