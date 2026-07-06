package com.example.displayapp.data.bluetooth.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Low-level SPP socket client. Handles:
 * - Socket creation and connection with timeout
 * - Continuous byte stream reading
 * - Clean socket teardown on cancellation or error
 *
 * This class is NOT thread-safe. It should be driven from a single coroutine.
 * The calling coroutine's cancellation triggers socket close and clean exit.
 */
class SppSocketClient(
    private val adapter: BluetoothAdapter,
    private val connectTimeoutMs: Long = CONNECTION_TIMEOUT_MS
) {
    private var socket: BluetoothSocket? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    /**
     * Connects to the remote device. Blocks (suspends) until connected or timeout.
     * Throws [IOException] on failure, [CancellationException] if coroutine is cancelled.
     *
     * Two-stage per capnp.md §1:
     *  1. Preferred — SDP-resolved secure RFCOMM ([createRfcommSocketToServiceRecord]).
     *  2. Fallback — reflection insecure channel-[FALLBACK_CHANNEL] socket, for phones/
     *     stacks whose SDP lookup for the SPP UUID fails. The board advertises the
     *     service on channel 1, so this recovers those handsets.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(address: String) {
        withContext(Dispatchers.IO) {
            try {
                val device = adapter.getRemoteDevice(address)
                adapter.cancelDiscovery()

                // 1) Preferred: SDP-resolved secure RFCOMM.
                Timber.d("Connecting to $address via SDP...")
                val sdpSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                if (tryConnect(sdpSocket)) {
                    socket = sdpSocket
                    Timber.i("Connected to $address (SDP)")
                    return@withContext
                }
                sdpSocket.runCatching { close() }

                // 2) Fallback: reflection insecure channel-1.
                Timber.w("SDP connect failed; trying insecure channel-$FALLBACK_CHANNEL fallback")
                val fallbackSocket = createInsecureChannelSocket(device)
                if (fallbackSocket != null && tryConnect(fallbackSocket)) {
                    socket = fallbackSocket
                    Timber.i("Connected to $address (insecure channel-$FALLBACK_CHANNEL)")
                    return@withContext
                }
                fallbackSocket?.runCatching { close() }

                throw IOException("Connection to $address failed (SDP + channel-$FALLBACK_CHANNEL fallback)")
            } catch (e: SecurityException) {
                // BLUETOOTH_CONNECT missing/revoked (Android 12+). Surface as an
                // IOException so the data source's normal connection-lost path handles
                // it instead of an uncaught crash in a supervisor scope.
                throw IOException("Bluetooth permission not granted", e)
            }
        }
    }

    /**
     * Attempts a single blocking connect within [connectTimeoutMs]. Returns true on
     * success; any failure returns false (caller tries the next path); genuine
     * coroutine cancellation closes the socket and propagates.
     *
     * `BluetoothSocket.connect()` is a non-cancellable blocking native call, so
     * `withTimeoutOrNull` cannot interrupt it. The timeout is instead enforced by a
     * sibling coroutine that closes the socket once [connectTimeoutMs] elapses —
     * closing unblocks `connect()` with an IOException, which we treat as a failed
     * attempt.
     */
    private suspend fun tryConnect(sock: BluetoothSocket): Boolean = withContext(Dispatchers.IO) {
        val abort = launch {
            delay(connectTimeoutMs)
            Timber.w("Connect exceeded ${connectTimeoutMs}ms — closing socket to abort")
            sock.runCatching { close() }
        }
        try {
            sock.connect()
            abort.cancel()
            true
        } catch (e: CancellationException) {
            sock.runCatching { close() }
            throw e
        } catch (e: Exception) {
            // IOException (incl. the timeout-close) or SecurityException → failed attempt.
            abort.cancel()
            Timber.w(e, "RFCOMM connect attempt failed")
            false
        }
    }

    /**
     * Builds the hidden-API insecure RFCOMM socket on [FALLBACK_CHANNEL] via
     * reflection. Returns null if the method is unavailable on this device.
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun createInsecureChannelSocket(device: BluetoothDevice): BluetoothSocket? =
        try {
            val method = device.javaClass
                .getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            method.invoke(device, FALLBACK_CHANNEL) as BluetoothSocket
        } catch (t: Throwable) {
            Timber.e(t, "createInsecureRfcommSocket reflection unavailable")
            null
        }

    /**
     * Reads from the socket continuously, invoking [onChunk] for each read.
     * Returns normally when the stream ends (remote close).
     * Throws [IOException] on read error, [CancellationException] on coroutine cancellation.
     *
     * The [onChunk] callback receives a ByteArray of exactly the bytes read (no trailing garbage).
     */
    suspend fun readLoop(onChunk: suspend (ByteArray) -> Unit) {
        withContext(Dispatchers.IO) {
            val inputStream: InputStream = socket?.inputStream
                ?: throw IOException("Socket not connected")
            val buffer = ByteArray(READ_BUFFER_SIZE)

            try {
                while (coroutineContext.isActive) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) {
                        Timber.d("Stream ended (remote close)")
                        break
                    }
                    if (bytesRead > 0) {
                        onChunk(buffer.copyOf(bytesRead))
                    }
                }
            } catch (e: IOException) {
                if (coroutineContext.isActive) {
                    Timber.w(e, "Read error")
                    throw e
                }
                // If coroutine was cancelled, the socket close caused this IOException — expected
            }
        }
    }

    /**
     * Writes data to the socket. Throws [IOException] on failure.
     */
    suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            val outputStream = socket?.outputStream
                ?: throw IOException("Socket not connected")
            outputStream.write(data)
            outputStream.flush()
        }
    }

    /**
     * Closes the socket. Safe to call multiple times.
     * After close, the read loop will exit with an IOException (caught internally if cancelled).
     */
    fun close() {
        socket?.let { s ->
            Timber.d("Closing socket")
            s.runCatching { close() }
            socket = null
        }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val READ_BUFFER_SIZE = 1024
        private const val CONNECTION_TIMEOUT_MS = 10_000L

        // Board advertises SPP on RFCOMM channel 1; used only by the reflection
        // fallback when SDP service discovery fails (capnp.md §1).
        private const val FALLBACK_CHANNEL = 1
    }
}
