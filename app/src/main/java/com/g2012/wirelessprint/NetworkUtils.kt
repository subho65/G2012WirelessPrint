package com.g2012.wirelessprint

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object NetworkUtils {

    fun getWifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return net
            }
        }
        return null
    }

    fun connectToServer(context: Context, ip: String, port: Int, timeoutMs: Int = 8000): Socket {
        // First try explicitly binding to Wi-Fi network (crucial when Hotspot has NO internet and 5G is ON!)
        val wifiNet = getWifiNetwork(context)
        if (wifiNet != null) {
            try {
                val sock = wifiNet.socketFactory.createSocket()
                sock.connect(InetSocketAddress(ip, port), timeoutMs)
                return sock
            } catch (_: Exception) {
                // Fall back to default socket (e.g. if this phone is hosting the hotspot)
            }
        }
        val sock = Socket()
        sock.connect(InetSocketAddress(ip, port), timeoutMs)
        return sock
    }

    fun getBestLocalIp(): String {
        val candidates = mutableListOf<Pair<Int, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                val priority = when {
                    name.startsWith("wlan") -> 1
                    name.startsWith("ap") || name.startsWith("swlan") -> 2
                    name.startsWith("eth") -> 3
                    name.startsWith("rmnet") || name.startsWith("ccmni") -> 9 // Skip mobile data if possible
                    else -> 5
                }
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("127.")) {
                            candidates.add(priority to ip)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return candidates.minByOrNull { it.first }?.second ?: "Not connected to Wi-Fi"
    }
}
