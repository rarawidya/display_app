package com.example.displayapp.data.bluetooth.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(address: String) {
        withContext(Dispatchers.IO) {
            val device = adapter.getRemoteDevice(address)
            adapter.cancelDiscovery()

            Timber.d("Connecting to $address...")
            val rfcommSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)

            try {
                withTimeout(connectTimeoutMs) {
                    rfcommSocket.connect()
                }
                socket = rfcommSocket
                Timber.i("Connected to $address")
            } catch (e: Exception) {
                rfcommSocket.runCatching { close() }
                when (e) {
                    is CancellationException -> throw e
                    else -> throw IOException("Connection to $address failed: ${e.message}", e)
                }
            }
        }
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
    }
}
