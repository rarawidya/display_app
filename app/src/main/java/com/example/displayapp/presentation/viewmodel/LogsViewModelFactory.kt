package com.example.displayapp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.displayapp.data.persistence.dao.TripDao
import com.example.displayapp.data.persistence.export.CsvExporter

class LogsViewModelFactory(
    private val tripDao: TripDao,
    private val exporter: CsvExporter
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LogsViewModel::class.java)) {
            return LogsViewModel(tripDao, exporter) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
