package com.hainesy.karoogarage

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Best-effort mDNS discovery of Home Assistant on the local network
 * (HA advertises _home-assistant._tcp). Fills the URL field so most
 * users never type their server address. Failures are silent — the
 * user can always type the URL.
 */
class HaDiscovery(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Calls [onFound] on an arbitrary thread with (instanceName, baseUrl). */
    fun start(onFound: (String, String) -> Unit) {
        val manager = nsdManager ?: return
        stop()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                manager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val addr = info.host ?: return
                            val raw = addr.hostAddress ?: return
                            // IPv6 literals need brackets, minus any %scope suffix.
                            val host = if (addr is java.net.Inet6Address) {
                                "[${raw.substringBefore('%')}]"
                            } else {
                                raw
                            }
                            val url = "http://$host:${info.port}"
                            Log.d(TAG, "Resolved HA instance ${info.serviceName} at $url")
                            onFound(info.serviceName, url)
                        }
                    },
                )
            }
        }
        discoveryListener = listener
        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            Log.w(TAG, "mDNS discovery unavailable", it)
            discoveryListener = null
        }
    }

    fun stop() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        runCatching { nsdManager?.stopServiceDiscovery(listener) }
    }

    companion object {
        private const val TAG = "HaDiscovery"
        private const val SERVICE_TYPE = "_home-assistant._tcp."
    }
}
