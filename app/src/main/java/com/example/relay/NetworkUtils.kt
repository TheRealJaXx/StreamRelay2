package com.example.relay

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * Retrieves the device's local IPv4 address on the current network interface (Wi-Fi, Ethernet, Hotspot).
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            val fallbackIps = mutableListOf<String>()

            for (networkInterface in interfaces) {
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val host = address.hostAddress ?: continue
                        if (host != "127.0.0.1") {
                            // Prioritize standard local area network subnets
                            if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                                return host
                            }
                            fallbackIps.add(host)
                        }
                    }
                }
            }
            return fallbackIps.firstOrNull() ?: "127.0.0.1"
        } catch (_: Exception) {
            return "127.0.0.1"
        }
    }
}
