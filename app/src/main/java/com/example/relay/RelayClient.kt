package com.example.relay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

enum class RxConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

data class RxStats(
    val fps: Float = 0f,
    val decodeTimeMs: Long = 0L,
    val frameSizeBytes: Int = 0,
    val totalFrames: Long = 0L
)

class RelayClient(
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    private val clientScope = CoroutineScope(Dispatchers.IO)
    private var connectionJob: Job? = null
    private var decoderJob: Job? = null

    private val isRunning = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow(RxConnectionState.DISCONNECTED)
    val connectionState: StateFlow<RxConnectionState> = _connectionState.asStateFlow()

    private val _currentBitmap = MutableStateFlow<Bitmap?>(null)
    val currentBitmap: StateFlow<Bitmap?> = _currentBitmap.asStateFlow()

    private val _stats = MutableStateFlow(RxStats())
    val stats: StateFlow<RxStats> = _stats.asStateFlow()

    // Holds only the latest un-decoded frame for 0-latency decoding
    private val latestReceivedFrame = AtomicReference<ByteArray?>(null)
    private val decodeSignal = Object()

    private val bitmapOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565 // Low memory & fast decoding
    }

    private var targetHost: String = ""
    private var targetPort: Int = 4120

    fun connect(host: String, port: Int) {
        disconnect()

        targetHost = host.trim()
        targetPort = port
        isManuallyStopped.set(false)
        isRunning.set(true)

        startDecoderThread()

        connectionJob = clientScope.launch {
            while (isRunning.get() && !isManuallyStopped.get()) {
                _connectionState.value = RxConnectionState.CONNECTING
                onLog("RX-Relay", "Connecting to $targetHost:$targetPort...", false)

                var socket: Socket? = null
                try {
                    val s = Socket()
                    s.tcpNoDelay = true
                    s.receiveBufferSize = 256 * 1024
                    s.connect(InetSocketAddress(targetHost, targetPort), 4000)
                    socket = s

                    _connectionState.value = RxConnectionState.CONNECTED
                    onLog("RX-Relay", "Connected to transmitter! Streaming video...", false)

                    val inputStream = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))

                    while (isRunning.get() && !s.isClosed && s.isConnected) {
                        val length = inputStream.readInt()
                        if (length <= 0 || length > 10_000_000) {
                            throw IllegalStateException("Invalid frame length received: $length")
                        }

                        val frameBytes = ByteArray(length)
                        inputStream.readFully(frameBytes)

                        // Immediately replace reference with newest frame, dropping older un-decoded frames
                        latestReceivedFrame.set(frameBytes)
                        synchronized(decodeSignal) {
                            decodeSignal.notify()
                        }
                    }
                } catch (e: Exception) {
                    if (!isManuallyStopped.get()) {
                        onLog("RX-Relay", "Connection dropped: ${e.message ?: "Disconnected"}", true)
                    }
                } finally {
                    try {
                        socket?.close()
                    } catch (_: Exception) {}
                }

                if (!isManuallyStopped.get()) {
                    _connectionState.value = RxConnectionState.RECONNECTING
                    onLog("RX-Relay", "Reconnecting in 2 seconds...", false)
                    delay(2000)
                } else {
                    break
                }
            }
            _connectionState.value = RxConnectionState.DISCONNECTED
        }
    }

    private fun startDecoderThread() {
        decoderJob?.cancel()
        decoderJob = clientScope.launch(Dispatchers.Default) {
            var frameCount = 0
            var lastMeasureTime = SystemClock.elapsedRealtime()
            var totalCount = 0L

            while (isActive && isRunning.get()) {
                var frame: ByteArray? = latestReceivedFrame.getAndSet(null)

                if (frame == null) {
                    synchronized(decodeSignal) {
                        frame = latestReceivedFrame.getAndSet(null)
                        if (frame == null) {
                            decodeSignal.wait(50)
                            frame = latestReceivedFrame.getAndSet(null)
                        }
                    }
                }

                if (frame != null && frame.isNotEmpty()) {
                    val decodeStart = SystemClock.elapsedRealtime()
                    val decoded = BitmapFactory.decodeByteArray(frame, 0, frame.size, bitmapOptions)
                    val decodeTime = SystemClock.elapsedRealtime() - decodeStart

                    if (decoded != null) {
                        _currentBitmap.value = decoded
                        frameCount++
                        totalCount++

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastMeasureTime >= 1000L) {
                            val elapsedSec = (now - lastMeasureTime) / 1000f
                            val currentFps = frameCount / elapsedSec
                            lastMeasureTime = now
                            frameCount = 0

                            _stats.value = RxStats(
                                fps = (currentFps * 10).roundToInt() / 10f,
                                decodeTimeMs = decodeTime,
                                frameSizeBytes = frame.size,
                                totalFrames = totalCount
                            )
                        } else {
                            _stats.value = _stats.value.copy(
                                decodeTimeMs = decodeTime,
                                frameSizeBytes = frame.size,
                                totalFrames = totalCount
                            )
                        }
                    }
                }
            }
        }
    }

    fun disconnect() {
        isManuallyStopped.set(true)
        isRunning.set(false)
        _connectionState.value = RxConnectionState.DISCONNECTED

        connectionJob?.cancel()
        connectionJob = null

        decoderJob?.cancel()
        decoderJob = null

        latestReceivedFrame.set(null)
        synchronized(decodeSignal) {
            decodeSignal.notifyAll()
        }
        _currentBitmap.value = null
        onLog("RX-Relay", "Disconnected from server", false)
    }
}
