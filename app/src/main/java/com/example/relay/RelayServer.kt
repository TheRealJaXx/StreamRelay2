package com.example.relay

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
import java.io.BufferedOutputStream
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
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    private var videoServerSocket: ServerSocket? = null
    private var audioServerSocket: ServerSocket? = null

    private val isRunning = AtomicBoolean(false)
    private var serverScope: CoroutineScope? = null

    private val _txStats = MutableStateFlow(TxRelayStats())
    val txStats: StateFlow<TxRelayStats> = _txStats.asStateFlow()

    private val _connectedClients = MutableStateFlow(0)
    val connectedClients: StateFlow<Int> = _connectedClients.asStateFlow()

    private val _serverPort = MutableStateFlow(4120)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    private val _isServerActive = MutableStateFlow(false)
    val isServerActive: StateFlow<Boolean> = _isServerActive.asStateFlow()

    // Internal session holder for video client
    private class VideoClientSession(
        val id: Int,
        val socket: Socket,
        val job: Job,
        val channel: Channel<ByteArray>
    )

    // Internal session holder for audio client
    private class AudioClientSession(
        val id: Int,
        val socket: Socket,
        val job: Job,
        val channel: Channel<ByteArray>
    )

    private val clientCounter = AtomicInteger(0)

    @Volatile
    private var currentVideoSession: VideoClientSession? = null

    @Volatile
    private var currentAudioSession: AudioClientSession? = null

    // Counter accumulators
    private var framesCapturedCount = 0L
    private var framesSubmittedCount = 0L
    private var framesSentCount = 0L
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

        // Requirement A: serverScope must be CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = scope

        val videoPort = port
        val audioPort = port + 1

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
                        if (!isRunning.get() || ss.isClosed) {
                            break
                        }
                        onLog("TX-Relay", "Video server accept transient notice: ${e.message}", false)
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
                        if (!isRunning.get() || ss.isClosed) {
                            break
                        }
                        onLog("TX-Relay", "Audio server accept transient notice: ${e.message}", false)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    onLog("TX-Relay", "Failed to start Audio server on port $audioPort: ${e.message}", true)
                }
            }
        }
    }

    private fun handleNewVideoClient(socket: Socket, scope: CoroutineScope) {
        val clientId = clientCounter.incrementAndGet()
        val clientAddress = socket.inetAddress?.hostAddress ?: "Unknown"
        // Requirement A: Log every accept
        onLog("TX-Relay", "Video client #$clientId accepted from $clientAddress", false)

        // Requirement A: When client is replaced, cancel its Job and close its socket immediately
        currentVideoSession?.let { old ->
            onLog("TX-Relay", "Ending prior video client #${old.id} (replaced by client #$clientId)", false)
            try { old.socket.close() } catch (_: Exception) {}
            old.job.cancel()
        }

        // Requirement B: Video: keep only the newest frame
        val videoChannel = Channel<ByteArray>(Channel.CONFLATED)

        lateinit var session: VideoClientSession
        val job = scope.launch {
            var closeReason = "Normal completion"
            try {
                // Requirement B: TCP_NODELAY and 64 KB send and receive buffers
                socket.tcpNoDelay = true
                socket.sendBufferSize = 64 * 1024
                socket.receiveBufferSize = 64 * 1024

                // Immediate disconnect detector on incoming stream
                launch {
                    try {
                        val input = socket.getInputStream()
                        val dummy = ByteArray(32)
                        while (isActive && !socket.isClosed && socket.isConnected) {
                            val r = input.read(dummy)
                            if (r == -1) break
                        }
                    } catch (_: Exception) {}
                    if (isActive) {
                        closeReason = "Client closed connection (EOF)"
                        try { socket.close() } catch (_: Exception) {}
                    }
                }

                val outputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))

                while (isActive && isRunning.get() && !socket.isClosed && socket.isConnected) {
                    val frame = videoChannel.receive()
                    if (frame.isNotEmpty()) {
                        try {
                            outputStream.writeInt(frame.size)
                            outputStream.write(frame)
                            outputStream.flush()
                            framesSentCount++
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
                // Requirement A: Log every client end with the reason
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
        // Requirement A: Log every accept
        onLog("TX-Relay", "Audio client #$clientId accepted from $clientAddress", false)

        // Requirement A: When client is replaced, cancel its Job and close its socket immediately
        currentAudioSession?.let { old ->
            onLog("TX-Relay", "Ending prior audio client #${old.id} (replaced by client #$clientId)", false)
            try { old.socket.close() } catch (_: Exception) {}
            old.job.cancel()
        }

        // Requirement B: Audio: bounded queue of at most 5 chunks (about 100 ms); drop the oldest when full
        val audioChannel = Channel<ByteArray>(5, BufferOverflow.DROP_OLDEST)

        lateinit var session: AudioClientSession
        val job = scope.launch {
            var closeReason = "Normal completion"
            try {
                // Requirement B: TCP_NODELAY and 64 KB send and receive buffers
                socket.tcpNoDelay = true
                socket.sendBufferSize = 64 * 1024
                socket.receiveBufferSize = 64 * 1024

                // Immediate disconnect detector on incoming stream
                launch {
                    try {
                        val input = socket.getInputStream()
                        val dummy = ByteArray(32)
                        while (isActive && !socket.isClosed && socket.isConnected) {
                            val r = input.read(dummy)
                            if (r == -1) break
                        }
                    } catch (_: Exception) {}
                    if (isActive) {
                        closeReason = "Client closed connection (EOF)"
                        try { socket.close() } catch (_: Exception) {}
                    }
                }

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
                // Requirement A: Log every client end with the reason
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
        val count = if (currentVideoSession != null) 1 else 0
        _connectedClients.value = count
        maybeUpdateStats(force = true)
    }

    /**
     * Tapped directly from UVC callback when a raw frame is captured.
     */
    fun recordFrameCaptured() {
        framesCapturedCount++
        maybeUpdateStats()
    }

    /**
     * Submits an isolated JPEG frame for network transmission.
     */
    fun submitFrame(jpegBytes: ByteArray) {
        framesSubmittedCount++
        lastFrameSize = jpegBytes.size

        if (jpegBytes.size >= 2) {
            lastStartsSoi = (jpegBytes[0].toInt() and 0xFF) == 0xFF && (jpegBytes[1].toInt() and 0xFF) == 0xD8
            lastEndsEoi = (jpegBytes[jpegBytes.size - 2].toInt() and 0xFF) == 0xFF && (jpegBytes[jpegBytes.size - 1].toInt() and 0xFF) == 0xD9
        } else {
            lastStartsSoi = false
            lastEndsEoi = false
        }

        val session = currentVideoSession
        if (session != null && session.job.isActive && !session.socket.isClosed && isRunning.get()) {
            session.channel.trySend(jpegBytes)
        }
        maybeUpdateStats()
    }

    /**
     * Submits an audio PCM chunk for network transmission.
     */
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
            val dropped = if (framesSubmittedCount > framesSentCount) framesSubmittedCount - framesSentCount else 0L
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

    /**
     * Requirement A: stop() must cancel everything and start() must create a fresh scope,
     * so repeated start/stop works cleanly.
     */
    fun stop() {
        isRunning.set(false)
        _isServerActive.value = false
        _connectedClients.value = 0

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

        serverScope?.cancel()
        serverScope = null

        maybeUpdateStats(force = true)
        onLog("TX-Relay", "RelayServer stopped completely", false)
    }
}
