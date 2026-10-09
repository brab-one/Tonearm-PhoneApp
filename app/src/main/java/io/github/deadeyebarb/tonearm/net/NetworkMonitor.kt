package io.github.deadeyebarb.tonearm.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Mobile data in a tunnel keeps its network, only suspended: no connection all the same.
                val suspended = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !suspended
            }

            override fun onLost(network: Network) {
                _online.value = false
            }
        })
    }

    /** True on mobile data and metered Wi-Fi hotspots. */
    fun isMetered(): Boolean = connectivity.isActiveNetworkMetered
}
