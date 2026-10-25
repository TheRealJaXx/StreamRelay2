package com.example.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UdpRelayServer(
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    private val serverScope = CoroutineScope(Dispatchers.IO)
    private var listenerJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    private var socket: DatagramSocket? = null
    @Volatile
    private var clientAddress: SocketAddress? = null

    private val sequenceCounter = AtomicInteger(0)
    private val videoFrameCounter = AtomicInteger(0)
    private val audioChunkCounter = AtomicInteger(0)

    private val _connectedClients = MutableStateFlow(0)
    val connectedClients: StateFlow<Int> = _connectedClients.asStateFlow()

    private val _connectedClientAddress = MutableStateFlow<String?>(null)
    val connectedClientAddress: StateFlow<String?> = _connectedClientAddress.asStateFlow()

    private val _isServerActive = MutableStateFlow(false)
    val isServerActive: StateFlow<Boolean> = _isServerActive.asStateFlow()

    var targetFps: Int = 60
    var send30FpsOnly: Boolean = false
    private var lastSendTimestamp = 0L

    fun start(port: Int) {
        if (isRunning.get()) stop()

        try {
            val s = DatagramSocket(port)
            s.sendBufferSize = 512 * 1024
            s.receiveBufferSize = 256 * 1024
            try {
                s.trafficClass = 0x10 // IPTOS_LOWDELAY
            } catch (_: Exception) {}
            socket = s
            isRunning.set(true)
            _isServerActive.value = true

            onLog("TX-UDP", "UDP Relay Server listening on port $port", false)

            listenerJob = serverScope.launch {
                val recvBuf = ByteArray(2048)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)

                while (isActive && isRunning.get() && !s.isClosed) {
                    try {
                        s.receive(recvPacket)
                        val length = recvPacket.length
                        if (length >= UdpTransport.HEADER_SIZE) {
                            val parsed = UdpTransport.parseHeader(recvBuf, length)
                            if (parsed != null) {
                                when (parsed.type) {
                                    UdpTransport.TYPE_REGISTRATION,
                                    UdpTransport.TYPE_HEARTBEAT -> {
                                        val sender = recvPacket.socketAddress
                                        if (clientAddress != sender) {
                                            clientAddress = sender
                                            _connectedClients.value = 1
                                            _connectedClientAddress.value = sender.toString()
                                            onLog("TX-UDP", "Client registered via UDP: $sender", false)
                                        }

                                        // Echo heartbeat back for RTT calculation
                                        if (parsed.type == UdpTransport.TYPE_HEARTBEAT) {
                                            val echoPacket = DatagramPacket(
                                                recvBuf.copyOf(length),
                                                length,
                                                sender
                                            )
                                            s.send(echoPacket)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            onLog("TX-UDP", "UDP listener error: ${e.message}", false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            onLog("TX-UDP", "Failed to start UDP Server on port $port: ${e.message}", true)
            stop()
        }
    }

    fun stop() {
        isRunning.set(false)
        _isServerActive.value = false
        _connectedClients.value = 0
        _connectedClientAddress.value = null
        clientAddress = null

        listenerJob?.cancel()
        listenerJob = null

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        onLog("TX-UDP", "UDP Relay Server stopped", false)
    }

    fun submitVideoFrame(
        frameData: ByteArray,
        size: Int = frameData.size,
        codecId: Short = 0,
        isKeyframe: Boolean = false,
        onWritten: (() -> Unit)? = null
    ) {
        val dest = clientAddress
        val s = socket
        if (dest == null || s == null || !isRunning.get() || size <= 0) {
            onWritten?.invoke()
            return
        }

        if (send30FpsOnly) {
            val now = System.currentTimeMillis()
            if (now - lastSendTimestamp < 30) {
                onWritten?.invoke()
                return
            }
            lastSendTimestamp = now
        }

        try {
            val frameId = videoFrameCounter.incrementAndGet()
            val maxPayload = UdpTransport.MAX_PAYLOAD_SIZE
            val totalFragments = ((size + maxPayload - 1) / maxPayload).coerceAtLeast(1)

            val flags: Byte = if (isKeyframe) UdpTransport.FLAG_KEYFRAME else 0

            var offset = 0
            for (fragIdx in 0 until totalFragments) {
                val chunkSize = minOf(maxPayload, size - offset)
                val packetBytes = ByteArray(UdpTransport.HEADER_SIZE + chunkSize)
                val buffer = ByteBuffer.wrap(packetBytes)

                UdpTransport.writeHeader(
                    buffer = buffer,
                    type = UdpTransport.TYPE_VIDEO,
                    flags = flags,
                    payloadSize = chunkSize.toShort(),
                    sequenceNumber = sequenceCounter.incrementAndGet(),
                    frameId = frameId,
                    fragmentIndex = fragIdx.toShort(),
                    totalFragments = totalFragments.toShort(),
                    codecId = codecId
                )

                System.arraycopy(frameData, offset, packetBytes, UdpTransport.HEADER_SIZE, chunkSize)
                offset += chunkSize

                val datagram = DatagramPacket(packetBytes, packetBytes.size, dest)
                s.send(datagram)
            }
        } catch (e: Exception) {
            // UDP drops gracefully on send queue overflow
        } finally {
            onWritten?.invoke()
        }
    }

    fun submitAudio(pcmChunk: ByteArray) {
        val dest = clientAddress
        val s = socket
        if (dest == null || s == null || !isRunning.get() || pcmChunk.isEmpty()) {
            return
        }

        try {
            val audioId = audioChunkCounter.incrementAndGet()
            val maxPayload = 1280 // Perfectly splits 3840 bytes into exactly 3 chunks of 1280 bytes
            val size = pcmChunk.size
            val totalFragments = ((size + maxPayload - 1) / maxPayload).coerceAtLeast(1)

            var offset = 0
            for (fragIdx in 0 until totalFragments) {
                val chunkSize = minOf(maxPayload, size - offset)
                val packetBytes = ByteArray(UdpTransport.HEADER_SIZE + chunkSize)
                val buffer = ByteBuffer.wrap(packetBytes)

                UdpTransport.writeHeader(
                    buffer = buffer,
                    type = UdpTransport.TYPE_AUDIO,
                    flags = 0,
                    payloadSize = chunkSize.toShort(),
                    sequenceNumber = sequenceCounter.incrementAndGet(),
                    frameId = audioId,
                    fragmentIndex = fragIdx.toShort(),
                    totalFragments = totalFragments.toShort(),
                    codecId = 0
                )

                System.arraycopy(pcmChunk, offset, packetBytes, UdpTransport.HEADER_SIZE, chunkSize)
                offset += chunkSize

                val datagram = DatagramPacket(packetBytes, packetBytes.size, dest)
                s.send(datagram)
            }
        } catch (_: Exception) {}
    }
}
