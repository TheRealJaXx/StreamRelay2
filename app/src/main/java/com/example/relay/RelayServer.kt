package com.example.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class RelayServer(
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    companion object {
        const val PKT_TYPE_VIDEO: Byte = 0x01
        const val PKT_TYPE_AUDIO: Byte = 0x02
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var serverJob: Job? = null
    private val serverScope = CoroutineScope(Dispatchers.IO)

    private val _connectedClients = MutableStateFlow(0)
    val connectedClients: StateFlow<Int> = _connectedClients.asStateFlow()

    private val _serverPort = MutableStateFlow(4120)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    private val _isServerActive = MutableStateFlow(false)
    val isServerActive: StateFlow<Boolean> = _isServerActive.asStateFlow()

    private val _framesSent = MutableStateFlow(0L)
    val framesSent: StateFlow<Long> = _framesSent.asStateFlow()

    // Conflated channel holds only the newest video frame (drops older frames if client lags)
    private var videoChannel = Channel<ByteArray>(Channel.CONFLATED)

    // Buffered channel preserves smooth real-time audio chunks (20ms chunks)
    private var audioChannel = Channel<ByteArray>(Channel.BUFFERED)

    @Volatile
    private var activeClientSocket: Socket? = null

    fun start(port: Int = _serverPort.value) {
        if (isRunning.get()) {
            if (_serverPort.value == port) return
            stop()
        }

        _serverPort.value = port
        isRunning.set(true)
        _isServerActive.value = true
        videoChannel = Channel(Channel.CONFLATED)
        audioChannel = Channel(Channel.BUFFERED)

        serverJob = serverScope.launch {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = ss
                onLog("TX-Relay", "TCP Unified Server (Video + Audio) listening on port $port", false)

                while (isRunning.get()) {
                    try {
                        val client = ss.accept()
                        handleNewClient(client)
                    } catch (e: IOException) {
                        if (isRunning.get()) {
                            onLog("TX-Relay", "Server accept error: ${e.message}", true)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                onLog("TX-Relay", "Failed to start server on port $port: ${e.message}", true)
            } finally {
                _isServerActive.value = false
            }
        }
    }

    private fun handleNewClient(newSocket: Socket) {
        // Enforce 1 client at a time; close prior client cleanly if reconnected
        activeClientSocket?.let { old ->
            try {
                old.close()
            } catch (_: Exception) {}
        }

        activeClientSocket = newSocket
        val clientAddress = newSocket.inetAddress.hostAddress ?: "Unknown"
        onLog("TX-Relay", "Client connected from $clientAddress (Streaming Video + Audio)", false)
        _connectedClients.value = 1

        serverScope.launch {
            serveClient(newSocket)
        }
    }

    private suspend fun serveClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 256 * 1024
            val outputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
            val writeLock = Any()

            val videoSenderJob = serverScope.launch {
                while (isActive && isRunning.get() && !socket.isClosed && socket.isConnected) {
                    val frame = videoChannel.receive()
                    if (frame.isNotEmpty()) {
                        synchronized(writeLock) {
                            outputStream.writeByte(PKT_TYPE_VIDEO.toInt())
                            outputStream.writeInt(frame.size)
                            outputStream.write(frame)
                            outputStream.flush()
                        }
                        _framesSent.value++
                    }
                }
            }

            val audioSenderJob = serverScope.launch {
                while (isActive && isRunning.get() && !socket.isClosed && socket.isConnected) {
                    val audioChunk = audioChannel.receive()
                    if (audioChunk.isNotEmpty()) {
                        synchronized(writeLock) {
                            outputStream.writeByte(PKT_TYPE_AUDIO.toInt())
                            outputStream.writeInt(audioChunk.size)
                            outputStream.write(audioChunk)
                            outputStream.flush()
                        }
                    }
                }
            }

            joinAll(videoSenderJob, audioSenderJob)
        } catch (e: Exception) {
            onLog("TX-Relay", "Client disconnected: ${e.message ?: "Connection closed"}", false)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
            if (activeClientSocket == socket) {
                activeClientSocket = null
                _connectedClients.value = 0
            }
        }
    }

    /**
     * Tapped directly from UVC video callback.
     * Non-blocking: drops older un-transmitted frame in conflated channel.
     */
    fun submitFrame(jpegBytes: ByteArray) {
        if (!isRunning.get() || _connectedClients.value == 0) return
        videoChannel.trySend(jpegBytes)
    }

    /**
     * Tapped directly from Audio capture loop.
     * Multiplexes PCM audio chunks into the same stream.
     */
    fun submitAudio(pcmBytes: ByteArray) {
        if (!isRunning.get() || _connectedClients.value == 0) return
        audioChannel.trySend(pcmBytes)
    }

    fun stop() {
        isRunning.set(false)
        _isServerActive.value = false
        _connectedClients.value = 0

        try {
            activeClientSocket?.close()
        } catch (_: Exception) {}
        activeClientSocket = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        serverJob?.cancel()
        serverJob = null

        onLog("TX-Relay", "TCP Server stopped", false)
    }
}
