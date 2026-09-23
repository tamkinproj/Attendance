package com.muslimedu.attendance.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live "does this device currently have a usable internet connection"
 * signal - this app is offline-first by design (every scan already saves
 * locally and queues for sync regardless of connectivity, see
 * `SyncQueueManager`), so nothing here gates *whether* a scan is accepted.
 * It exists purely so the UI can tell a teacher, at a glance, whether a scan
 * is likely to sync right away or sit queued for later - there was
 * previously no network-state indicator anywhere in the app at all, only
 * [com.muslimedu.attendance.rfid.RfidManager.status] for the RFID reader
 * itself.
 *
 * Uses `registerDefaultNetworkCallback` (tracks whatever network the system
 * considers "the" active one, matching what every app's actual traffic
 * uses) filtered to [NetworkCapabilities.NET_CAPABILITY_VALIDATED] - a wifi
 * network with no real internet behind it (a captive portal, a
 * router with no upstream) reports connected but not validated, and would
 * otherwise show "online" right up until every sync attempt failed anyway.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        _isOnline.value = connectivityManager.activeNetworkIsValidated()

        connectivityManager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isOnline.value = true
                }

                override fun onLost(network: Network) {
                    _isOnline.value = connectivityManager.activeNetworkIsValidated()
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    _isOnline.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            },
        )
    }

    private fun ConnectivityManager.activeNetworkIsValidated(): Boolean {
        val capabilities = getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
