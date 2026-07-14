package com.innodrive.evdash.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.innodrive.evdash.DisplayApp
import com.innodrive.evdash.MainActivity
import com.innodrive.evdash.R
import com.innodrive.evdash.data.preferences.DevicePreferences
import com.innodrive.evdash.domain.model.ConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Foreground service that maintains the Bluetooth telemetry connection
 * even when the app is in the background.
 *
 * Why a foreground service:
 * - Android kills background Bluetooth connections after ~30s on modern OS
 * - A foreground service with notification keeps the connection alive
 * - Required for trip recording to work while screen is off
 *
 * Lifecycle:
 *   startService() → foreground notification → connect to BT → stream data
 *   stopService() → disconnect BT → remove notification
 *
 * The service exposes its state through the AppContainer's existing
 * StateFlow infrastructure — the UI observes the same flows regardless
 * of whether the service is running.
 */
class TelemetryService : LifecycleService() {

    private val binder = LocalBinder()
    private var connectionJob: Job? = null

    inner class LocalBinder : Binder() {
        val service: TelemetryService get() = this@TelemetryService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.i("TelemetryService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                val name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "EV Controller"
                startForeground(NOTIFICATION_ID, buildNotification("Connecting to $name..."))
                if (address != null) {
                    connectToDevice(address, name)
                } else {
                    autoConnect()
                }
            }
            ACTION_STOP -> {
                disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_DISCONNECT -> {
                disconnect()
                updateNotification("Disconnected")
            }
        }

        return START_STICKY
    }

    private fun connectToDevice(address: String, name: String) {
        connectionJob?.cancel()
        connectionJob = lifecycleScope.launch {
            val container = (application as DisplayApp).appContainer
            container.switchDataSource(simulator = false)

            // Save as last device for auto-reconnect
            val prefs = DevicePreferences(applicationContext)
            prefs.saveDevice(address, name)

            try {
                container.vehicleRepository.connect(address)
                updateNotification("Connected to $name")

                // Monitor connection state and update notification
                container.vehicleRepository.connectionState.collect { state ->
                    val text = when (state) {
                        ConnectionState.CONNECTED -> "Connected to $name"
                        ConnectionState.CONNECTING -> "Connecting to $name..."
                        ConnectionState.RECONNECTING -> "Reconnecting to $name..."
                        ConnectionState.DISCONNECTED -> "Disconnected"
                        ConnectionState.SCANNING -> "Scanning..."
                    }
                    updateNotification(text)
                }
            } catch (e: Exception) {
                Timber.e(e, "Connection failed in service")
                updateNotification("Connection failed")
            }
        }
    }

    private fun autoConnect() {
        lifecycleScope.launch {
            val prefs = DevicePreferences(applicationContext)
            val saved = prefs.lastDevice.first()
            if (saved != null && saved.autoConnect) {
                Timber.i("Auto-connecting to ${saved.name} (${saved.address})")
                connectToDevice(saved.address, saved.name)
            } else {
                Timber.d("No saved device for auto-connect")
                updateNotification("No device configured")
                // Wait briefly then stop if nothing to connect to
                delay(5000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        val container = (application as DisplayApp).appContainer
        container.vehicleRepository.disconnect()
        Timber.i("Service disconnected")
    }

    override fun onDestroy() {
        disconnect()
        Timber.i("TelemetryService destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "EV Telemetry",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active telemetry connection"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TelemetryService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EV Dashboard")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(0, "Disconnect", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_START = "com.innodrive.evdash.action.START_TELEMETRY"
        const val ACTION_STOP = "com.innodrive.evdash.action.STOP_TELEMETRY"
        const val ACTION_DISCONNECT = "com.innodrive.evdash.action.DISCONNECT"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME = "device_name"

        private const val CHANNEL_ID = "telemetry_channel"
        private const val NOTIFICATION_ID = 1001

        fun startIntent(context: Context, address: String, name: String): Intent {
            return Intent(context, TelemetryService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_ADDRESS, address)
                putExtra(EXTRA_DEVICE_NAME, name)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, TelemetryService::class.java).apply {
                action = ACTION_STOP
            }
        }

        fun autoConnectIntent(context: Context): Intent {
            return Intent(context, TelemetryService::class.java).apply {
                action = ACTION_START
            }
        }
    }
}
