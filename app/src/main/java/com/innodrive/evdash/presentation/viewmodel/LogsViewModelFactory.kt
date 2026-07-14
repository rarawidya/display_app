package com.innodrive.evdash.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.innodrive.evdash.data.persistence.export.CsvExporter
import com.innodrive.evdash.domain.repository.TripRepository

class LogsViewModelFactory(
    private val tripRepository: TripRepository,
    private val exporter: CsvExporter
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LogsViewModel::class.java)) {
            return LogsViewModel(tripRepository, exporter) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
