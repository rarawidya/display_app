package com.example.displayapp.data.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Emits whether the phone is currently connected to Wi-Fi.
 *
 * Backed by [ConnectivityManager.registerNetworkCallback] with a TRANSPORT_WIFI
 * request, so the [Flow] updates as soon as the OS notices a state change.
 * Requires the (normal-level) `ACCESS_NETWORK_STATE` permission — declared
 * in the manifest, no runtime grant needed.
 */
class WifiStateMonitor(private val context: Context) {

    val isWifiConnected: Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Seed the flow with the current state so the UI doesn't briefly show "disconnected"
        val initial = cm.activeNetwork
            ?.let(cm::getNetworkCapabilities)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        trySend(initial)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network)      { trySend(false) }
        }
        cm.registerNetworkCallback(request, callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
