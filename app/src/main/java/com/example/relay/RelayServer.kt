package com.example.relay

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RelayServer(
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
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

    // Holds only the latest un-transmitted frame; older frames are immediately dropped
    private val latestFrame = AtomicReference<ByteArray?>(null)
    private val frameSignal = Object()

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

        serverJob = serverScope.launch {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = ss
                onLog("TX-Relay", "TCP Server listening on port $port (TCP_NODELAY enabled)", false)

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
        onLog("TX-Relay", "Client connected from $clientAddress", false)
        _connectedClients.value = 1

        serverScope.launch {
            serveClient(newSocket)
        }
    }

    private fun serveClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 256 * 1024
            val outputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))

            while (isRunning.get() && !socket.isClosed && socket.isConnected) {
                var frame: ByteArray? = latestFrame.getAndSet(null)

                if (frame == null) {
                    synchronized(frameSignal) {
                        frame = latestFrame.getAndSet(null)
                        if (frame == null) {
                            frameSignal.wait(50) // Wait up to 50ms for the next frame
                            frame = latestFrame.getAndSet(null)
                        }
                    }
                }

                if (frame != null && frame.isNotEmpty()) {
                    // Send 4-byte big-endian length followed by raw JPEG bytes
                    outputStream.writeInt(frame.size)
                    outputStream.write(frame)
                    outputStream.flush()
                    _framesSent.value++
                }
            }
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
     * Tapped directly from UVC callback.
     * Takes < 1 microsecond: replaces the latest frame reference without blocking the UVC ingestion thread.
     */
    fun submitFrame(jpegBytes: ByteArray) {
        if (!isRunning.get() || _connectedClients.value == 0) return

        latestFrame.set(jpegBytes)
        synchronized(frameSignal) {
            frameSignal.notify()
        }
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

        latestFrame.set(null)
        synchronized(frameSignal) {
            frameSignal.notifyAll()
        }
        onLog("TX-Relay", "TCP Server stopped", false)
    }
}
