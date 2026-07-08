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
        // MapLibre must be initialized once before any MapView is inflated. No API
        // key here — the renderer is authenticated (if at all) by the style URL.
        org.maplibre.android.MapLibre.getInstance(this)
        appContainer = AppContainer(this)

        // Android doesn't always rebind the notification listener after an app
        // install/update (it can stay dead until a reboot or an access toggle),
        // so ask it to rebind at cold start when access is already granted.
        com.example.displayapp.service.NotificationRelayService.requestRebindIfGranted(this)

        // Calls don't arrive through the notification listener — the telephony
        // relay follows the same "Mirror to vehicle display" toggle.
        appContainer.callStateRelay.start()

        // The reverse direction: cluster Answer/End button taps (0xAF05 uplink)
        // acting on the phone call. Same toggle gates it.
        appContainer.callControlHandler.start()

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
