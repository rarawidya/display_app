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

        // Reconcile the data + location sources with the persisted Simulator-mode
        // preference at cold start. `useSimulator` defaults true, so a real-mode user
        // must be switched to the real transport + fused GPS explicitly — otherwise the
        // map shows the simulated fixed location and demo trips leak in before any
        // device connects.
        appScope.launch {
            val simulatorMode = appContainer.appPreferencesRepository.settings.first().simulatorMode
            if (simulatorMode) {
                // Open the simulated session so telemetry streams immediately (a freshly
                // selected SimulatedDataSource is idle until connect()).
                appContainer.setSimulatorSession(true)
                // Hardware-free demo: seed a few past trips so Logs isn't empty.
                // Idempotent — no-op once any trip exists, so user deletes stick.
                appContainer.sampleTripSeeder.seedIfEmpty()
            } else {
                // Simulator off → real BLE transport + fused GPS; no seeded demo trips.
                appContainer.switchDataSource(simulator = false)
            }
        }
    }
}
