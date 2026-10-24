package com.example.relay

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class TxRelayStats(
    val framesCaptured: Long = 0L,
    val framesSubmitted: Long = 0L,
    val framesSent: Long = 0L,
    val framesDropped: Long = 0L,
    val lastFrameSizeBytes: Int = 0,
    val lastFrameStartsSoi: Boolean = false,
    val lastFrameEndsEoi: Boolean = false,
    val isClientConnected: Boolean = false,
    val connectedClientsCount: Int = 0,
    val audioChunksCaptured: Long = 0L,
    val audioChunksSent: Long = 0L
)

class RelayServer(
    private val context: Context? = null,
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    companion object {
        // Video frame header magic (0x53523202: 'S','R','2', 0x02)
        const val VIDEO_FRAME_MAGIC = 0x53523202.toInt()
    }

    private var videoServerSocket: ServerSocket? = null
    private var audioServerSocket: ServerSocket? = null
    private var rttServerSocket: ServerSocket? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val isRunning = AtomicBoolean(false)
    private var serverScope: CoroutineScope? = null

    private val _txStats = MutableStateFlow(TxRelayStats())
    val txStats: StateFlow<TxRelayStats> = _txStats.asStateFlow()

    private val _connectedClients = MutableStateFlow(0)
    val connectedClients: StateFlow<Int> = _connectedClients.asStateFlow()

    private val _connectedClientAddress = MutableStateFlow<String?>(null)
    val connectedClientAddress: StateFlow<String?> = _connectedClientAddress.asStateFlow()

    private val _serverPort = MutableStateFlow(4120)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    private val _isServerActive = MutableStateFlow(false)
    val isServerActive: StateFlow<Boolean> = _isServerActive.asStateFlow()

    class VideoFramePayload(
        val data: ByteArray,
        val size: Int,
        val sequenceNumber: Int,
        val onWritten: (() -> Unit)? = null
    )

    // Internal session holder for video client
    private class VideoClientSession(
        val id: Int,
        val socket: Socket,
        val job: Job,
        val channel: Channel<VideoFramePayload>
    )

    // Internal session holder for audio client
    private class AudioClientSession(
        val id: Int,
        val socket: Socket,
        val job: Job,
        val channel: Channel<ByteArray>
    )

    private val clientCounter = AtomicInteger(0)
    private val videoSeqCounter = AtomicInteger(0)

    @Volatile
    private var currentVideoSession: VideoClientSession? = null

    @Volatile
    private var currentAudioSession: AudioClientSession? = null

    @Volatile
    var targetFps: Int = 60

    @Volatile
    var send30FpsOnly: Boolean = false

    private var send30FpsSkipNext = false

    // Counter accumulators
    private var framesCapturedCount = 0L
    private var framesSubmittedCount = 0L
    private var framesSentCount = 0L
    private var framesDroppedCount = 0L
    private var audioCapturedCount = 0L
    private var audioSentCount = 0L

    private var lastFrameSize = 0
    private var lastStartsSoi = false
    private var lastEndsEoi = false
    private var lastStatsUpdateTime = 0L

    fun start(port: Int = _serverPort.value) {
        if (isRunning.get()) {
            if (_serverPort.value == port) return
            stop()
        }

        _serverPort.value = port
        isRunning.set(true)
        _isServerActive.value = true

        // Acquire WakeLock & Low Latency WifiLock while server is running
        context?.let { ctx ->
            try {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamRelay:TxWakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L)
                }
            } catch (e: Exception) {
                onLog("TX-Relay", "WakeLock notice: ${e.message}", false)
            }

            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(wifiMode, "StreamRelay:TxWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                onLog("TX-Relay", "WifiLock notice: ${e.message}", false)
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = scope

        val videoPort = port
        val audioPort = port + 1
        val rttPort = port + 2

        // 1. Video Accept Loop (Port P)
        scope.launch {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", videoPort))
                videoServerSocket = ss
                onLog("TX-Relay", "Video TCP Server listening on port $videoPort", false)

                while (isRunning.get() && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        handleNewVideoClient(client, scope)
                    } catch (e: IOException) {
                        if (!isRunning.get() || ss.isClosed) break
                        onLog("TX-Relay", "Video server accept notice: ${e.message}", false)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    onLog("TX-Relay", "Failed to start Video server on port $videoPort: ${e.message}", true)
                }
            }
        }

        // 2. Audio Accept Loop (Port P + 1)
        scope.launch {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", audioPort))
                audioServerSocket = ss
                onLog("TX-Relay", "Audio TCP Server listening on port $audioPort", false)

                while (isRunning.get() && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        handleNewAudioClient(client, scope)
                    } catch (e: IOException) {
                        if (!isRunning.get() || ss.isClosed) break
                        onLog("TX-Relay", "Audio server accept notice: ${e.message}", false)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    onLog("TX-Relay", "Failed to start Audio server on port $audioPort: ${e.message}", true)
                }
            }
        }

        // 3. RTT Echo Loop (Port P + 2) - Immediately echoes back 8-byte nanoTime
        scope.launch {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", rttPort))
                rttServerSocket = ss
                onLog("TX-Relay", "RTT Echo Server listening on port $rttPort", false)

                while (isRunning.get() && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        launch {
                            try {
                                client.tcpNoDelay = true
                                val din = DataInputStream(BufferedInputStream(client.getInputStream(), 1024))
                                val dout = DataOutputStream(BufferedOutputStream(client.getOutputStream(), 1024))
                                while (isActive && isRunning.get() && !client.isClosed) {
                                    val t = din.readLong()
                                    dout.writeLong(t)
                                    dout.flush()
                                }
                            } catch (_: Exception) {}
                            finally {
                                try { client.close() } catch (_: Exception) {}
                            }
                        }
                    } catch (e: IOException) {
                        if (!isRunning.get() || ss.isClosed) break
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    onLog("TX-Relay", "RTT server notice on port $rttPort: ${e.message}", false)
                }
            }
        }
    }

    private fun handleNewVideoClient(socket: Socket, scope: CoroutineScope) {
        val clientId = clientCounter.incrementAndGet()
        val clientAddress = socket.inetAddress?.hostAddress ?: "Unknown"
        onLog("TX-Relay", "Video client #$clientId accepted from $clientAddress", false)

        currentVideoSession?.let { old ->
            onLog("TX-Relay", "Ending prior video client #${old.id} (replaced by client #$clientId)", false)
            try { old.socket.close() } catch (_: Exception) {}
            old.job.cancel()
        }

        // CONFLATED hand-off on TX: a writer thread writes newest frame; blocking write is backpressure
        val videoChannel = Channel<VideoFramePayload>(Channel.CONFLATED)

        lateinit var session: VideoClientSession
        val job = scope.launch {
            var closeReason = "Normal completion"
            try {
                // Low latency TCP configuration
                socket.tcpNoDelay = true
                socket.sendBufferSize = 48 * 1024 // 48 KB SO_SNDBUF
                socket.receiveBufferSize = 64 * 1024
                try {
                    socket.setPerformancePreferences(0, 2, 1)
                } catch (_: Exception) {}

                val outputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 16 * 1024))

                var discardNextFrame = false
                while (isActive && isRunning.get() && !socket.isClosed && socket.isConnected) {
                    var frame = videoChannel.receive()

                    // Drain conflated channel to ensure we write the absolute freshest frame
                    while (true) {
                        val newer = videoChannel.tryReceive().getOrNull() ?: break
                        frame.onWritten?.invoke() // release older replaced buffer
                        framesDroppedCount++
                        frame = newer
                    }

                    if (discardNextFrame) {
                        discardNextFrame = false
                        frame.onWritten?.invoke()
                        framesDroppedCount++
                        maybeUpdateStats()
                        continue
                    }

                    if (frame.size > 0) {
                        try {
                            val writeStartNs = System.nanoTime()
                            // 16-byte big-endian header: magic (4B) + length (4B) + seq (4B) + flags (4B)
                            outputStream.writeInt(VIDEO_FRAME_MAGIC)
                            outputStream.writeInt(frame.size)
                            outputStream.writeInt(frame.sequenceNumber)
                            outputStream.writeInt(0) // flags
                            outputStream.write(frame.data, 0, frame.size)
                            outputStream.flush()
                            val writeDurationNs = System.nanoTime() - writeStartNs

                            val fps = targetFps
                            val frameIntervalMs = if (fps > 0) 1000L / fps else 33L
                            val thresholdNs = 2L * frameIntervalMs * 1_000_000L
                            if (writeDurationNs > thresholdNs) {
                                discardNextFrame = true
                            }

                            frame.onWritten?.invoke() // release buffer back to pool
                            framesSentCount++
                            maybeUpdateStats()
                        } catch (e: IOException) {
                            frame.onWritten?.invoke()
                            closeReason = "Write failed: ${e.message ?: "Broken pipe"}"
                            break
                        }
                    } else {
                        frame.onWritten?.invoke()
                    }
                }
            } catch (e: IOException) {
                closeReason = "Network I/O: ${e.message ?: "Connection closed"}"
            } catch (e: Exception) {
                closeReason = "Exception: ${e.message ?: "Error"}"
            } finally {
                onLog("TX-Relay", "Video client #$clientId ended. Reason: $closeReason", false)
                try { socket.close() } catch (_: Exception) {}
                if (currentVideoSession === session) {
                    currentVideoSession = null
                    updateConnectedClients()
                }
            }
        }

        session = VideoClientSession(clientId, socket, job, videoChannel)
        currentVideoSession = session
        updateConnectedClients()
    }

    private fun handleNewAudioClient(socket: Socket, scope: CoroutineScope) {
        val clientId = clientCounter.incrementAndGet()
        val clientAddress = socket.inetAddress?.hostAddress ?: "Unknown"
        onLog("TX-Relay", "Audio client #$clientId accepted from $clientAddress", false)

        currentAudioSession?.let { old ->
            onLog("TX-Relay", "Ending prior audio client #${old.id} (replaced by client #$clientId)", false)
            try { old.socket.close() } catch (_: Exception) {}
            old.job.cancel()
        }

        val audioChannel = Channel<ByteArray>(capacity = 6, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        lateinit var session: AudioClientSession
        val job = scope.launch {
            var closeReason = "Normal completion"
            try {
                socket.tcpNoDelay = true
                socket.sendBufferSize = 64 * 1024
                socket.receiveBufferSize = 64 * 1024
                try {
                    socket.setPerformancePreferences(0, 2, 1)
                } catch (_: Exception) {}

                val outputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 16 * 1024))

                while (isActive && isRunning.get() && !socket.isClosed && socket.isConnected) {
                    val chunk = audioChannel.receive()

                    if (chunk.isNotEmpty()) {
                        try {
                            outputStream.writeInt(chunk.size)
                            outputStream.write(chunk)
                            outputStream.flush()
                            audioSentCount++
                            maybeUpdateStats()
                        } catch (e: IOException) {
                            closeReason = "Write failed: ${e.message ?: "Broken pipe"}"
                            break
                        }
                    }
                }
            } catch (e: IOException) {
                closeReason = "Network I/O: ${e.message ?: "Connection closed"}"
            } catch (e: Exception) {
                closeReason = "Exception: ${e.message ?: "Error"}"
            } finally {
                onLog("TX-Relay", "Audio client #$clientId ended. Reason: $closeReason", false)
                try { socket.close() } catch (_: Exception) {}
                if (currentAudioSession === session) {
                    currentAudioSession = null
                }
            }
        }

        session = AudioClientSession(clientId, socket, job, audioChannel)
        currentAudioSession = session
    }

    private fun updateConnectedClients() {
        val session = currentVideoSession
        val count = if (session != null) 1 else 0
        _connectedClients.value = count
        _connectedClientAddress.value = session?.socket?.inetAddress?.hostAddress
        maybeUpdateStats(force = true)
    }

    fun recordFrameCaptured() {
        framesCapturedCount++
        maybeUpdateStats()
    }

    fun submitFrame(jpegBytes: ByteArray, size: Int = jpegBytes.size, onWritten: (() -> Unit)? = null) {
        framesSubmittedCount++
        lastFrameSize = size
        val seq = videoSeqCounter.incrementAndGet()

        if (size >= 2) {
            lastStartsSoi = (jpegBytes[0].toInt() and 0xFF) == 0xFF && (jpegBytes[1].toInt() and 0xFF) == 0xD8
            lastEndsEoi = (jpegBytes[size - 2].toInt() and 0xFF) == 0xFF && (jpegBytes[size - 1].toInt() and 0xFF) == 0xD9
        } else {
            lastStartsSoi = false
            lastEndsEoi = false
        }

        if (send30FpsOnly) {
            send30FpsSkipNext = !send30FpsSkipNext
            if (send30FpsSkipNext) {
                onWritten?.invoke()
                maybeUpdateStats()
                return
            }
        }

        val session = currentVideoSession
        if (session != null && session.job.isActive && !session.socket.isClosed && isRunning.get()) {
            val payload = VideoFramePayload(jpegBytes, size, seq, onWritten)
            val sent = session.channel.trySend(payload)
            if (!sent.isSuccess) {
                onWritten?.invoke()
            }
        } else {
            onWritten?.invoke()
        }
        maybeUpdateStats()
    }

    fun submitAudio(pcmBytes: ByteArray) {
        audioCapturedCount++
        val session = currentAudioSession
        if (session != null && session.job.isActive && !session.socket.isClosed && isRunning.get()) {
            session.channel.trySend(pcmBytes)
        }
        maybeUpdateStats()
    }

    private fun maybeUpdateStats(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastStatsUpdateTime >= 1000L) {
            lastStatsUpdateTime = now
            val isConn = currentVideoSession != null
            val dropped = if (framesSubmittedCount > framesSentCount) framesSubmittedCount - framesSentCount else framesDroppedCount
            _txStats.value = TxRelayStats(
                framesCaptured = framesCapturedCount,
                framesSubmitted = framesSubmittedCount,
                framesSent = framesSentCount,
                framesDropped = dropped,
                lastFrameSizeBytes = lastFrameSize,
                lastFrameStartsSoi = lastStartsSoi,
                lastFrameEndsEoi = lastEndsEoi,
                isClientConnected = isConn,
                connectedClientsCount = if (isConn) 1 else 0,
                audioChunksCaptured = audioCapturedCount,
                audioChunksSent = audioSentCount
            )
        }
    }

    fun stop() {
        isRunning.set(false)
        _isServerActive.value = false
        _connectedClients.value = 0
        _connectedClientAddress.value = null

        currentVideoSession?.let {
            try { it.socket.close() } catch (_: Exception) {}
            it.job.cancel()
        }
        currentVideoSession = null

        currentAudioSession?.let {
            try { it.socket.close() } catch (_: Exception) {}
            it.job.cancel()
        }
        currentAudioSession = null

        try { videoServerSocket?.close() } catch (_: Exception) {}
        videoServerSocket = null

        try { audioServerSocket?.close() } catch (_: Exception) {}
        audioServerSocket = null

        try { rttServerSocket?.close() } catch (_: Exception) {}
        rttServerSocket = null

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
        wifiLock = null

        serverScope?.cancel()
        serverScope = null

        maybeUpdateStats(force = true)
        onLog("TX-Relay", "RelayServer stopped completely", false)
    }
}
