package com.example.displayapp

import android.app.Application
import com.example.displayapp.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

class DisplayApp : Application() {

    lateinit var appContainer: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        appContainer = AppContainer(this)

        // Run retention policy on startup, honoring the user's preference.
        appScope.launch {
            val retentionDays = appContainer.appPreferencesRepository.settings
                .first()
                .retention
                .days
            appContainer.retentionPolicy.enforce(overrideDays = retentionDays)
        }
    }
}
