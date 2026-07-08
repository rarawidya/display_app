package com.example.displayapp.data.notification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.example.displayapp.data.bluetooth.BluetoothDataSource
import com.example.displayapp.data.protocol.CallControlSchema
import com.example.displayapp.domain.repository.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Acts on the cluster's call buttons: decodes board→phone `CallControl` events
 * from the `0xAF05` uplink ([BluetoothDataSource.controlFrames],
 * docs/CALL-CONTROL-INTEGRATION.md) and answers/ends the phone call via
 * [TelecomManager].
 *
 * Gates, in order: frame validity (CRC etc. — [CallControlSchema.decode]),
 * retransmit dedup (`seq` repeat of the last event), the user's *Mirror to
 * vehicle display* toggle, then the `ANSWER_PHONE_CALLS` runtime permission.
 * Everything that doesn't act is logged and dropped — there is no error reply
 * in the v1 contract; the board's implicit ACK is the banner update that
 * `CallStateRelay` pushes when the call state actually changes.
 *
 * API notes: `acceptRingingCall` needs API 26+, `endCall` API 28+. Both are
 * deprecated (Google steers companion devices to CDM + InCallService) but remain
 * the pragmatic path for a cockpit app; if answering proves unreliable on some
 * OEM, the wire contract stays and only this class changes.
 */
class CallControlHandler(
    private val context: Context,
    private val dataSource: BluetoothDataSource,
    private val prefs: AppPreferencesRepository,
    private val scope: CoroutineScope,
) {

    private var lastSeq = -1

    /** Start consuming control frames; call once from app startup. */
    fun start() {
        scope.launch {
            dataSource.controlFrames.collect { frame -> onFrame(frame) }
        }
    }

    private suspend fun onFrame(frame: ByteArray) {
        val event = CallControlSchema.decode(frame)
        if (event == null) {
            Timber.tag(TAG).w("call-control: bad frame (%d B) — dropped", frame.size)
            return
        }
        if (event.seq == lastSeq) {
            Timber.tag(TAG).d("call-control: duplicate seq=%d — retransmit dropped", event.seq)
            return
        }
        lastSeq = event.seq

        if (!relayEnabled()) {
            Timber.tag(TAG).i("call-control: relay toggle off — action=%d ignored", event.action)
            return
        }
        if (!hasPermission()) {
            Timber.tag(TAG).w("call-control: ANSWER_PHONE_CALLS not granted — action=%d ignored", event.action)
            return
        }

        when (event.action) {
            CallControlSchema.ACTION_ANSWER -> answer(event)
            CallControlSchema.ACTION_HANG_UP -> hangUp(event)
            // Board-local banner dismiss; informational only in v1.
            CallControlSchema.ACTION_DISMISS ->
                Timber.tag(TAG).d("call-control: dismiss seq=%d (no phone action)", event.seq)
            else ->
                Timber.tag(TAG).w("call-control: unknown action=%d — ignored", event.action)
        }
    }

    private fun answer(event: CallControlSchema.CallControl) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Timber.tag(TAG).w("call-control: answer needs API 26+ — ignored")
            return
        }
        val result = runCatching {
            @Suppress("DEPRECATION")
            telecom()?.acceptRingingCall()
        }
        Timber.tag(TAG).i(
            "call-control: action=%d seq=%d → %s",
            event.action, event.seq, if (result.isSuccess) "answered" else "failed: ${result.exceptionOrNull()}"
        )
    }

    private fun hangUp(event: CallControlSchema.CallControl) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Timber.tag(TAG).w("call-control: hangUp needs API 28+ — ignored")
            return
        }
        val result = runCatching {
            @Suppress("DEPRECATION")
            telecom()?.endCall()
        }
        Timber.tag(TAG).i(
            "call-control: action=%d seq=%d → %s",
            event.action, event.seq, if (result.isSuccess) "ended" else "failed: ${result.exceptionOrNull()}"
        )
    }

    private fun telecom(): TelecomManager? =
        context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun relayEnabled(): Boolean =
        runCatching { prefs.settings.first().notificationRelayEnabled }.getOrDefault(false)

    private companion object {
        const val TAG = "NotifRelay"
    }
}
