package com.example.displayapp

import android.app.Application
import com.example.displayapp.di.AppContainer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

class DisplayApp : Application() {

    lateinit var appContainer: AppContainer
        private set

    // Without an explicit handler, an uncaught throwable in any appScope.launch
    // (e.g. DataStore IOException, DB locked during cold start) propagates to
    // Thread.defaultUncaughtExceptionHandler and crashes the process before
    // the launcher activity is even drawn. Log + swallow instead.
    private val appScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            CoroutineExceptionHandler { _, t ->
                Timber.e(t, "Background work in appScope failed")
            }
    )

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

        // Hardware-free demo: when running against the simulator and the trips
        // table is empty, seed a handful of plausible past trips so the Logs
        // page has content. No-op once any trip exists, so user deletes stick.
        if (appContainer.useSimulator) {
            appScope.launch {
                appContainer.sampleTripSeeder.seedIfEmpty()
            }
        }

        // If Simulator mode is on, open the simulated session at launch so
        // telemetry streams immediately — otherwise the fake source is selected
        // but idle (nothing calls connect() on it) and every screen reads
        // "Disconnected". The Settings toggle drives the same path at runtime.
        appScope.launch {
            if (appContainer.appPreferencesRepository.settings.first().simulatorMode) {
                appContainer.setSimulatorSession(true)
            }
        }
    }
}
