package com.innodrive.evdash.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.innodrive.evdash.data.diagnostics.DiagnosticsRepository
import com.innodrive.evdash.data.energy.EfficiencyTracker
import com.innodrive.evdash.data.notification.PhoneNotificationSender
import com.innodrive.evdash.domain.repository.AppPreferencesRepository
import com.innodrive.evdash.domain.repository.VehicleRepository

class DashboardViewModelFactory(
    private val repository: VehicleRepository,
    private val efficiencyTracker: EfficiencyTracker,
    private val diagnosticsRepository: DiagnosticsRepository,
    private val notificationSender: PhoneNotificationSender,
    private val appPreferences: AppPreferencesRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
            return DashboardViewModel(repository, efficiencyTracker, diagnosticsRepository, notificationSender, appPreferences) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
