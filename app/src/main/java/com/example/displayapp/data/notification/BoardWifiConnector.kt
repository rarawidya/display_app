package com.example.displayapp.data.notification

import com.example.displayapp.domain.repository.AppPreferencesRepository
import kotlinx.coroutines.flow.first

/**
 * One place that reads the stored phone-hotspot credentials and pushes them to the
 * board's Wi-Fi STA over `0xAF07` (the SSID → PSK → JOIN sequence, see
 * [BoardWifiCommands] / docs/BOARD-WIFI-STA-INTEGRATION.md).
 *
 * Extracted so the two call sites share one path: the Settings "Send Wi-Fi to
 * display" action AND the one-tap header hotspot button (which sends the
 * credentials automatically before deep-linking to the phone's tethering settings,
 * so the user never has to open Settings to hand the display its Wi-Fi).
 */
class BoardWifiConnector(
    private val appPreferences: AppPreferencesRepository,
    private val sender: PhoneNotificationSender,
) {
    enum class Result {
        /** No SSID stored yet — the user must set the hotspot credentials first. */
        NOT_CONFIGURED,

        /** All three frames (SSID, PSK, JOIN) reached the board. */
        SENT,

        /** At least one frame didn't get through — the display is likely disconnected. */
        FAILED,
    }

    /**
     * Push the stored hotspot credentials to the board. Reads the latest snapshot so
     * it always sends what the user last saved, regardless of the caller's context.
     */
    suspend fun pushCredentials(): Result {
        val app = appPreferences.settings.first()
        if (app.hotspotSsid.isBlank()) return Result.NOT_CONFIGURED
        val ok = BoardWifiCommands.joinSequence(app.hotspotSsid, app.hotspotPassword)
            .all { sender.send(it) }
        return if (ok) Result.SENT else Result.FAILED
    }

    /** Tell the board to wipe its stored credentials and return to AP mode. */
    suspend fun forget(): Boolean = sender.send(BoardWifiCommands.forget())
}
