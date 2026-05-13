package com.example.displayapp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.displayapp.data.diagnostics.DiagnosticsRepository
import com.example.displayapp.data.energy.EfficiencyTracker
import com.example.displayapp.domain.repository.VehicleRepository

class DashboardViewModelFactory(
    private val repository: VehicleRepository,
    private val efficiencyTracker: EfficiencyTracker,
    private val diagnosticsRepository: DiagnosticsRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
            return DashboardViewModel(repository, efficiencyTracker, diagnosticsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
