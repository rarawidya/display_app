package com.innodrive.evdash.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.innodrive.evdash.data.preferences.DevicePreferences
import com.innodrive.evdash.domain.repository.VehicleRepository

class DeviceViewModelFactory(
    private val repository: VehicleRepository,
    private val devicePreferences: DevicePreferences
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DeviceViewModel::class.java)) {
            return DeviceViewModel(repository, devicePreferences) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
