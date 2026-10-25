package com.example.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class UdpRelayClient(
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    private val clientScope = CoroutineScope(Dispatchers.IO)
    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    private var socket: DatagramSocket? = null
    private var serverAddress: InetSocketAddress? = null

    var onVideoFrameReceived: ((data: ByteArray, codecId: Short, isKeyframe: Boolean) -> Unit)? = null
    var onAudioChunkReceived: ((pcmBytes: ByteArray) -> Unit)? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _rttMs = MutableStateFlow(0f)
    val rttMs: StateFlow<Float> = _rttMs.asStateFlow()

    // Video reassembly state
    private var currentVideoFrameId = -1
    private var videoFragmentsReceived = 0
    private var totalVideoFragments = 0
    private var videoCodecId: Short = 0
    private var isCurrentKeyframe = false
    private var videoFrameParts = HashMap<Int, ByteArray>()

    // Audio reassembly state
    private var currentAudioFrameId = -1
    private var audioFragmentsReceived = 0
    private var totalAudioFragments = 0
    private var audioFrameParts = HashMap<Int, ByteArray>()

    fun connect(host: String, port: Int) {
        disconnect()

        try {
            val addr = InetSocketAddress(host, port)
            serverAddress = addr
            val s = DatagramSocket()
            s.receiveBufferSize = 512 * 1024
            s.sendBufferSize = 128 * 1024
            try {
                s.trafficClass = 0x10 // IPTOS_LOWDELAY
            } catch (_: Exception) {}
            socket = s
            isRunning.set(true)
            _isConnected.value = true

            onLog("RX-UDP", "UDP Client connected to $host:$port", false)

            // 1. Send registration packet immediately
            sendRegistration(s, addr)

            // 2. Start heartbeat loop
            heartbeatJob = clientScope.launch {
                while (isActive && isRunning.get()) {
                    sendHeartbeat(s, addr)
                    delay(500)
                }
            }

            // 3. Start receive loop
            receiveJob = clientScope.launch {
                val recvBuf = ByteArray(2048)
                val packet = DatagramPacket(recvBuf, recvBuf.size)

                while (isActive && isRunning.get() && !s.isClosed) {
                    try {
                        s.receive(packet)
                        val length = packet.length
                        val parsed = UdpTransport.parseHeader(recvBuf, length) ?: continue

                        when (parsed.type) {
                            UdpTransport.TYPE_VIDEO -> {
                                handleVideoFragment(recvBuf, parsed)
                            }
                            UdpTransport.TYPE_AUDIO -> {
                                handleAudioFragment(recvBuf, parsed)
                            }
                            UdpTransport.TYPE_HEARTBEAT -> {
                                if (parsed.payloadSize >= 8) {
                                    val bb = ByteBuffer.wrap(recvBuf, parsed.payloadOffset, 8)
                                    val sentNs = bb.getLong()
                                    val rtt = (System.nanoTime() - sentNs) / 1_000_000f
                                    if (rtt in 0.05f..1000f) {
                                        _rttMs.value = (Math.round(rtt * 10f) / 10f)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            onLog("RX-UDP", "UDP packet receive error: ${e.message}", false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            onLog("RX-UDP", "Failed to connect UDP client: ${e.message}", true)
            disconnect()
        }
    }

    private fun handleVideoFragment(data: ByteArray, parsed: UdpTransport.ParsedPacket) {
        val frameId = parsed.frameId

        // If a newer frame arrives, drop any older incomplete frame immediately
        if (frameId > currentVideoFrameId) {
            currentVideoFrameId = frameId
            videoFragmentsReceived = 0
            totalVideoFragments = parsed.totalFragments
            videoCodecId = parsed.codecId
            isCurrentKeyframe = (parsed.flags.toInt() and UdpTransport.FLAG_KEYFRAME.toInt()) != 0
            videoFrameParts.clear()
        }

        if (frameId == currentVideoFrameId) {
            val payload = ByteArray(parsed.payloadSize)
            System.arraycopy(data, parsed.payloadOffset, payload, 0, parsed.payloadSize)

            if (!videoFrameParts.containsKey(parsed.fragmentIndex)) {
                videoFrameParts[parsed.fragmentIndex] = payload
                videoFragmentsReceived++

                if (videoFragmentsReceived >= totalVideoFragments) {
                    // Frame fully assembled!
                    val totalSize = videoFrameParts.values.sumOf { it.size }
                    val completeFrame = ByteArray(totalSize)
                    var dst = 0
                    for (i in 0 until totalVideoFragments) {
                        val part = videoFrameParts[i] ?: return // Fragment missing
                        System.arraycopy(part, 0, completeFrame, dst, part.size)
                        dst += part.size
                    }

                    onVideoFrameReceived?.invoke(completeFrame, videoCodecId, isCurrentKeyframe)
                    videoFrameParts.clear()
                }
            }
        }
    }

    private fun handleAudioFragment(data: ByteArray, parsed: UdpTransport.ParsedPacket) {
        val audioId = parsed.frameId

        if (parsed.totalFragments == 1) {
            val pcm = ByteArray(parsed.payloadSize)
            System.arraycopy(data, parsed.payloadOffset, pcm, 0, parsed.payloadSize)
            onAudioChunkReceived?.invoke(pcm)
            return
        }

        if (audioId > currentAudioFrameId) {
            currentAudioFrameId = audioId
            audioFragmentsReceived = 0
            totalAudioFragments = parsed.totalFragments
            audioFrameParts.clear()
        }

        if (audioId == currentAudioFrameId) {
            val payload = ByteArray(parsed.payloadSize)
            System.arraycopy(data, parsed.payloadOffset, payload, 0, parsed.payloadSize)

            if (!audioFrameParts.containsKey(parsed.fragmentIndex)) {
                audioFrameParts[parsed.fragmentIndex] = payload
                audioFragmentsReceived++

                if (audioFragmentsReceived >= totalAudioFragments) {
                    val totalSize = audioFrameParts.values.sumOf { it.size }
                    val completeChunk = ByteArray(totalSize)
                    var dst = 0
                    for (i in 0 until totalAudioFragments) {
                        val part = audioFrameParts[i] ?: return
                        System.arraycopy(part, 0, completeChunk, dst, part.size)
                        dst += part.size
                    }

                    onAudioChunkReceived?.invoke(completeChunk)
                    audioFrameParts.clear()
                }
            }
        }
    }

    private fun sendRegistration(s: DatagramSocket, addr: InetSocketAddress) {
        try {
            val buf = ByteArray(UdpTransport.HEADER_SIZE)
            val bb = ByteBuffer.wrap(buf)
            UdpTransport.writeHeader(
                buffer = bb,
                type = UdpTransport.TYPE_REGISTRATION,
                flags = 0,
                payloadSize = 0,
                sequenceNumber = 1,
                frameId = 0,
                fragmentIndex = 0,
                totalFragments = 1
            )
            s.send(DatagramPacket(buf, buf.size, addr))
        } catch (_: Exception) {}
    }

    private fun sendHeartbeat(s: DatagramSocket, addr: InetSocketAddress) {
        try {
            val payload = ByteArray(8)
            ByteBuffer.wrap(payload).putLong(System.nanoTime())

            val buf = ByteArray(UdpTransport.HEADER_SIZE + 8)
            val bb = ByteBuffer.wrap(buf)
            UdpTransport.writeHeader(
                buffer = bb,
                type = UdpTransport.TYPE_HEARTBEAT,
                flags = 0,
                payloadSize = 8,
                sequenceNumber = 2,
                frameId = 0,
                fragmentIndex = 0,
                totalFragments = 1
            )
            System.arraycopy(payload, 0, buf, UdpTransport.HEADER_SIZE, 8)
            s.send(DatagramPacket(buf, buf.size, addr))
        } catch (_: Exception) {}
    }

    fun disconnect() {
        isRunning.set(false)
        _isConnected.value = false
        receiveJob?.cancel()
        heartbeatJob?.cancel()
        receiveJob = null
        heartbeatJob = null

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        onLog("RX-UDP", "UDP Client disconnected", false)
    }
}
