package com.gwgm.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import kotlin.coroutines.resume

data class Endpoint(val name: String, val host: String, val port: Int)

/**
 * Finds wireless-debugging services on the local Wi-Fi via mDNS/DNS-SD, so you don't have to type
 * IPs and ports. Note: if THIS phone also has Wireless debugging on, it will show up too.
 */
class AdbDiscovery(context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)

    suspend fun scan(serviceType: String, durationMs: Long = 4_000): List<Endpoint> {
        val found = Collections.synchronizedList(mutableListOf<NsdServiceInfo>())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { found += serviceInfo }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            delay(durationMs)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        val out = mutableListOf<Endpoint>()
        // The legacy resolve API only allows one resolve at a time, so do them sequentially.
        for (info in found.toList().distinctBy { it.serviceName }) {
            resolve(info)?.let { out += it }
        }
        return out.distinctBy { "${it.host}:${it.port}" }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): Endpoint? = withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress
                    if (cont.isActive) {
                        cont.resume(host?.let { Endpoint(serviceInfo.serviceName, it, serviceInfo.port) })
                    }
                }
            })
        }
    }

    companion object {
        const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"
        const val CONNECT_SERVICE = "_adb-tls-connect._tcp"
    }
}
