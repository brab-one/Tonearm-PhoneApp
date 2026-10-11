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
    // As it is now: started without any network, no callback comes until one does, and that has to count as coming back.
    private val _online = MutableStateFlow(onlineWith(connectivity.getNetworkCapabilities(connectivity.activeNetwork)))
    val online: StateFlow<Boolean> = _online

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _online.value = onlineWith(caps)
            }

            override fun onLost(network: Network) {
                _online.value = false
            }
        })
    }

    /** True on mobile data and metered Wi-Fi hotspots. */
    fun isMetered(): Boolean = connectivity.isActiveNetworkMetered

    private fun onlineWith(caps: NetworkCapabilities?): Boolean {
        if (caps == null) return false
        // Mobile data in a tunnel keeps its network, only suspended: no connection all the same.
        val suspended = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !suspended
    }
}
