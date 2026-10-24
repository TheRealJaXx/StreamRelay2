package com.example.relay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.example.model.DiscoveredTransmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Open Media Transport (OMT) LAN Discovery Manager.
 * Implements dual-layer zero-configuration discovery:
 * 1. Standard DNS-SD / mDNS via Android NsdManager (_omt._tcp)
 * 2. High-speed OMT UDP Broadcast Beacons on port 6399
 */
class OmtDiscoveryManager(
    private val context: Context,
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    companion object {
        const val OMT_SERVICE_TYPE = "_omt._tcp."
        const val STREAM_RELAY_SERVICE_TYPE = "_streamrelay._tcp."
        const val OMT_DISCOVERY_PORT = 6399
        private const val PRUNE_TIMEOUT_MS = 6000L
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null

    // Broadcast (TX) state
    private var isBroadcasting = false
    private var broadcastJob: Job? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var activeBroadcastName: String? = null

    // Scanner (RX) state
    private var isScanning = false
    private var udpListenerJob: Job? = null
    private var pruneJob: Job? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val discoveredMap = ConcurrentHashMap<String, DiscoveredTransmitter>()
    private val _discoveredTransmitters = MutableStateFlow<List<DiscoveredTransmitter>>(emptyList())
    val discoveredTransmitters: StateFlow<List<DiscoveredTransmitter>> = _discoveredTransmitters.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    fun getPublishedStreamName(): String {
        val deviceModel = Build.MODEL.trim()
        val deviceMfg = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return "$deviceMfg $deviceModel (Stream Relay OMT)"
    }

    /**
     * Starts advertising this device on the local network as an active OMT source.
     */
    fun startBroadcasting(port: Int, fps: Int = 60, hasAudio: Boolean = true) {
        if (isBroadcasting) stopBroadcasting()
        isBroadcasting = true

        val streamName = getPublishedStreamName()
        activeBroadcastName = streamName

        // 1. Android NsdManager (DNS-SD / mDNS)
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = streamName
                serviceType = OMT_SERVICE_TYPE
                setPort(port)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAttribute("fps", fps.toString())
                    setAttribute("audio", if (hasAudio) "1" else "0")
                    setAttribute("proto", "OMT")
                }
            }

            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    onLog("OMT-Discovery", "mDNS Registered: ${info.serviceName} on port $port", false)
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    onLog("OMT-Discovery", "mDNS Registration error code: $errorCode", false)
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    onLog("OMT-Discovery", "mDNS Unregistered: ${info.serviceName}", false)
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            registrationListener = listener
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            onLog("OMT-Discovery", "NsdManager broadcast notice: ${e.message}", false)
        }

        // 2. High-speed OMT UDP broadcast beacon (every 2.0s)
        broadcastJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true

                while (isActive && isBroadcasting) {
                    val localIp = NetworkUtils.getLocalIpAddress()
                    if (localIp != "127.0.0.1") {
                        val json = JSONObject().apply {
                            put("name", streamName)
                            put("host", localIp)
                            put("port", port)
                            put("fps", fps)
                            put("audio", hasAudio)
                            put("protocol", "OMT/SR2")
                        }.toString()

                        val bytes = json.toByteArray(Charsets.UTF_8)
                        val broadcastAddresses = listOf(
                            InetAddress.getByName("255.255.255.255"),
                            InetAddress.getByName("239.255.255.250")
                        )

                        for (addr in broadcastAddresses) {
                            try {
                                val packet = DatagramPacket(bytes, bytes.size, addr, OMT_DISCOVERY_PORT)
                                socket.send(packet)
                            } catch (_: Exception) {}
                        }
                    }
                    delay(2000)
                }
            } catch (e: Exception) {
                if (isBroadcasting) {
                    onLog("OMT-Discovery", "UDP Beacon note: ${e.message}", false)
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
        onLog("OMT-Discovery", "Broadcasting stream identity: $streamName", false)
    }

    fun stopBroadcasting() {
        isBroadcasting = false
        broadcastJob?.cancel()
        broadcastJob = null

        registrationListener?.let {
            try { nsdManager?.unregisterService(it) } catch (_: Exception) {}
        }
        registrationListener = null
        activeBroadcastName = null
    }

    /**
     * Starts scanning the local network for active OMT / Stream Relay transmitters.
     */
    fun startScanning() {
        if (isScanning) return
        isScanning = true
        _isDiscovering.value = true

        discoveredMap.clear()
        _discoveredTransmitters.value = emptyList()

        acquireMulticastLock()

        // 1. Android NsdManager Scanner
        try {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    onLog("OMT-Discovery", "mDNS Scan start failed: $errorCode", false)
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

                override fun onDiscoveryStarted(serviceType: String) {
                    onLog("OMT-Discovery", "mDNS Scanning started for $serviceType", false)
                }

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType.contains("omt") || serviceInfo.serviceType.contains("streamrelay")) {
                        resolveNsdService(serviceInfo)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val key = serviceInfo.serviceName
                    discoveredMap.remove(key)
                    updateTransmittersList()
                }
            }
            discoveryListener = listener
            nsdManager?.discoverServices(OMT_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            onLog("OMT-Discovery", "mDNS discovery notice: ${e.message}", false)
        }

        // 2. UDP Beacon Listener (Port 6399)
        udpListenerJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(OMT_DISCOVERY_PORT))
                    broadcast = true
                    soTimeout = 3000
                }

                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive && isScanning) {
                    try {
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                        if (text.startsWith("{") && text.endsWith("}")) {
                            val json = JSONObject(text)
                            val name = json.optString("name", "Unknown Stream")
                            val host = json.optString("host", packet.address.hostAddress ?: "")
                            val port = json.optInt("port", 7000)
                            val fps = json.optInt("fps", 60)
                            val hasAudio = json.optBoolean("audio", true)

                            if (host.isNotBlank() && port > 0) {
                                val id = "$host:$port"
                                val transmitter = DiscoveredTransmitter(
                                    id = id,
                                    name = name,
                                    host = host,
                                    port = port,
                                    fps = fps,
                                    hasAudio = hasAudio,
                                    discoveryMethod = "OMT Beacon",
                                    lastSeenMs = System.currentTimeMillis()
                                )
                                discoveredMap[id] = transmitter
                                updateTransmittersList()
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // normal receive loop timeout
                    } catch (e: Exception) {
                        if (!isScanning) break
                    }
                }
            } catch (e: Exception) {
                if (isScanning) {
                    onLog("OMT-Discovery", "UDP listener port 6399 notice: ${e.message}", false)
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }

        // 3. Periodic Pruning of stale transmitters (> 6s without beacon)
        pruneJob = scope.launch {
            while (isActive && isScanning) {
                delay(2000)
                val now = System.currentTimeMillis()
                var changed = false
                val iterator = discoveredMap.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (now - entry.value.lastSeenMs > PRUNE_TIMEOUT_MS) {
                        iterator.remove()
                        changed = true
                    }
                }
                if (changed) {
                    updateTransmittersList()
                }
            }
        }
    }

    private fun resolveNsdService(serviceInfo: NsdServiceInfo) {
        nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress ?: return
                val port = info.port
                val name = info.serviceName
                var fps = 60
                var hasAudio = true

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val attrs = info.attributes
                    attrs["fps"]?.let { fps = String(it, Charsets.UTF_8).toIntOrNull() ?: 60 }
                    attrs["audio"]?.let { hasAudio = String(it, Charsets.UTF_8) == "1" }
                }

                val id = "$host:$port"
                val transmitter = DiscoveredTransmitter(
                    id = id,
                    name = name,
                    host = host,
                    port = port,
                    fps = fps,
                    hasAudio = hasAudio,
                    discoveryMethod = "mDNS / DNS-SD",
                    lastSeenMs = System.currentTimeMillis()
                )
                discoveredMap[id] = transmitter
                updateTransmittersList()
            }
        })
    }

    private fun updateTransmittersList() {
        val list = discoveredMap.values.sortedBy { it.name }
        _discoveredTransmitters.value = list
    }

    fun stopScanning() {
        isScanning = false
        _isDiscovering.value = false

        udpListenerJob?.cancel()
        udpListenerJob = null

        pruneJob?.cancel()
        pruneJob = null

        discoveryListener?.let {
            try { nsdManager?.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        discoveryListener = null

        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null && wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("OMTDiscoveryLock").apply {
                    setReferenceCounted(true)
                    acquire()
                }
            }
        } catch (_: Exception) {}
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
            multicastLock = null
        } catch (_: Exception) {}
    }

    fun release() {
        stopBroadcasting()
        stopScanning()
    }
}
