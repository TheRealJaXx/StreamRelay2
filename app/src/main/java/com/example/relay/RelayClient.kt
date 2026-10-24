package com.example.relay

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.view.SurfaceHolder
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
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class RxConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

enum class AudioLatencyProfile(val label: String, val targetMinMs: Int, val capMaxMs: Int) {
    GAME("Game (40/80 ms)", 40, 80),
    BALANCED("Balanced (60/120 ms)", 60, 120),
    SMOOTH("Smooth (120/200 ms)", 120, 200)
}

data class RxStats(
    val videoPacketsReceived: Long = 0L,
    val videoBytesPerSecond: Long = 0L,
    val audioPacketsReceived: Long = 0L,
    val secondsSinceLastVideoPacket: Float = 0f,
    val decodeSuccesses: Long = 0L,
    val decodeFailures: Long = 0L,
    val lastDecodeError: String = "None",
    val lastFrameFirst4BytesHex: String = "--",
    val lastFrameEndsEoi: Boolean = false,
    val decodedBitmapSize: String = "--",
    val fps: Float = 0f,
    val decodeTimeMs: Long = 0L,
    val decodeTimeMsAvg: Float = 0f,
    val decodeTimeMsMax: Long = 0L,
    val frameAgeMs: Float = 0f,
    val arrivalIntervalP95Ms: Float = 0f,
    val droppedFrames: Long = 0L,
    val rttAvgMs: Float = 0f,
    val rttP95Ms: Float = 0f,
    val audioUnderrunCount: Int = 0,
    val frameSizeBytes: Int = 0,
    val statusDetail: String = "Disconnected"
)

class RelayClient(
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // 1 ms of 48kHz stereo 16-bit = 48 * 4 = 192 bytes
        // 20ms chunk = 3840 bytes
        const val BYTES_PER_MS = 192
        const val SILENCE_CHUNK_BYTES = BYTES_PER_MS * 20 // 3840 bytes
    }

    private val clientScope = CoroutineScope(Dispatchers.IO)
    private var videoJob: Job? = null
    private var audioJob: Job? = null
    private var rttJob: Job? = null
    private var audioPlaybackJob: Job? = null
    private var statsJob: Job? = null

    private val isRunning = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)

    @Volatile
    private var videoSocket: Socket? = null

    @Volatile
    private var audioSocket: Socket? = null

    val isVideoConnected: Boolean
        get() = isVideoSocketConnected

    val isAudioConnected: Boolean
        get() = isAudioSocketConnected

    @Volatile
    private var isVideoSocketConnected = false

    @Volatile
    private var isAudioSocketConnected = false

    private val connectionGeneration = AtomicInteger(0)

    private val _connectionState = MutableStateFlow(RxConnectionState.DISCONNECTED)
    val connectionState: StateFlow<RxConnectionState> = _connectionState.asStateFlow()

    private val _stats = MutableStateFlow(RxStats())
    val stats: StateFlow<RxStats> = _stats.asStateFlow()

    // Dedicated MjpegPlayer instance
    private val mjpegPlayer = MjpegPlayer { tag, msg, err -> onLog(tag, msg, err) }
    val currentBitmap: StateFlow<Bitmap?> = mjpegPlayer.currentBitmap

    // Audio Playback Engine
    private var audioTrack: AudioTrack? = null
    private val _isAudioPlaying = MutableStateFlow(false)
    val isAudioPlaying: StateFlow<Boolean> = _isAudioPlaying.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _audioLatencyProfile = MutableStateFlow(AudioLatencyProfile.BALANCED)
    val audioLatencyProfile: StateFlow<AudioLatencyProfile> = _audioLatencyProfile.asStateFlow()

    private val _audioDelayMs = MutableStateFlow(0)
    val audioDelayMs: StateFlow<Int> = _audioDelayMs.asStateFlow()

    // Jitter buffer queue
    private val audioJitterLock = Object()
    private val audioJitterQueue = ArrayDeque<ByteArray>()
    private var audioJitterBufferedBytes = 0

    // RTT measurements
    private val rttSamplesMs = ArrayList<Float>(35)
    private var rttAvgMs = 0f
    private var rttP95Ms = 0f

    // Diagnostics & Counters
    private var videoPacketsCount = 0L
    private var audioPacketsCount = 0L
    private var videoBytesCounter = 0L
    private var lastBytesSecondTimestamp = SystemClock.elapsedRealtime()
    private var currentBytesPerSec = 0L
    private var lastVideoPacketTimestamp = 0L
    private var statusDetailMessage = "Disconnected"

    init {
        // Collect MjpegPlayer stats and update unified RxStats
        clientScope.launch {
            while (true) {
                delay(1000)
                updateStatsState()
            }
        }
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
    }

    fun setAudioLatencyProfile(profile: AudioLatencyProfile) {
        _audioLatencyProfile.value = profile
    }

    fun setAudioDelayMs(delayMs: Int) {
        _audioDelayMs.value = delayMs.coerceIn(0, 200)
    }

    fun attachSurface(holder: SurfaceHolder) {
        mjpegPlayer.attachSurface(holder)
    }

    fun detachSurface(holder: SurfaceHolder? = null) {
        mjpegPlayer.detachSurface()
    }

    fun connect(host: String, port: Int) {
        val gen = connectionGeneration.incrementAndGet()
        isManuallyStopped.set(false)
        isRunning.set(true)
        _connectionState.value = RxConnectionState.CONNECTING
        statusDetailMessage = "Starting connections to $host:$port..."

        synchronized(audioJitterLock) {
            audioJitterQueue.clear()
            audioJitterBufferedBytes = 0
        }

        // Cancel previous jobs
        videoJob?.cancel()
        audioJob?.cancel()
        rttJob?.cancel()
        audioPlaybackJob?.cancel()

        try { videoSocket?.close() } catch (_: Exception) {}
        try { audioSocket?.close() } catch (_: Exception) {}

        startAudioPlaybackThread(gen)
        startRttProbe(host, port, gen)

        videoJob = clientScope.launch {
            runVideoLoop(host, port, gen)
        }

        audioJob = clientScope.launch {
            runAudioLoop(host, port + 1, gen)
        }
    }

    fun disconnect() {
        isManuallyStopped.set(true)
        isRunning.set(false)
        connectionGeneration.incrementAndGet()

        videoJob?.cancel()
        audioJob?.cancel()
        rttJob?.cancel()
        audioPlaybackJob?.cancel()

        try { videoSocket?.close() } catch (_: Exception) {}
        try { audioSocket?.close() } catch (_: Exception) {}
        videoSocket = null
        audioSocket = null
        isVideoSocketConnected = false
        isAudioSocketConnected = false

        releaseAudioTrack()

        _connectionState.value = RxConnectionState.DISCONNECTED
        statusDetailMessage = "Disconnected"
        updateStatsState(force = true)
        onLog("RX-Relay", "Client disconnected completely", false)
    }

    private suspend fun runVideoLoop(host: String, port: Int, gen: Int) {
        while (isRunning.get() && !isManuallyStopped.get() && gen == connectionGeneration.get()) {
            var socket: Socket? = null
            try {
                statusDetailMessage = "Connecting to Video ($host:$port)..."
                updateStatsState(force = true)

                val s = Socket()
                s.tcpNoDelay = true
                s.sendBufferSize = 64 * 1024
                s.receiveBufferSize = 64 * 1024 // 64 KB SO_RCVBUF
                try {
                    s.setPerformancePreferences(0, 2, 1)
                } catch (_: Exception) {}
                s.connect(InetSocketAddress(host, port), 4000)

                if (gen != connectionGeneration.get() || !isRunning.get()) {
                    try { s.close() } catch (_: Exception) {}
                    break
                }

                socket = s
                videoSocket = s
                isVideoSocketConnected = true
                _connectionState.value = RxConnectionState.CONNECTED
                statusDetailMessage = "Connected (Video :$port, Audio :${port + 1})"
                onLog("RX-Relay", "Video socket connected to $host:$port", false)
                updateStatsState(force = true)

                val bufferedIn = BufferedInputStream(s.getInputStream(), 32 * 1024)
                val inputStream = DataInputStream(bufferedIn)

                while (isRunning.get() && gen == connectionGeneration.get() && !s.isClosed && s.isConnected) {
                    // 16-byte big-endian header: magic (4B) + length (4B) + seq (4B) + flags (4B)
                    val magic = inputStream.readInt()
                    if (magic != RelayServer.VIDEO_FRAME_MAGIC) {
                        throw IOException("Header magic mismatch: 0x%08X (expected 0x%08X). Reconnecting...".format(magic, RelayServer.VIDEO_FRAME_MAGIC))
                    }

                    val length = inputStream.readInt()
                    if (length <= 0 || length > 10_000_000) {
                        throw IOException("Invalid video frame length received: $length")
                    }

                    val seq = inputStream.readInt()
                    val flags = inputStream.readInt() // flags (0)

                    val buffer = mjpegPlayer.acquireBuffer(length)
                    inputStream.readFully(buffer, 0, length)

                    val arrivalNs = System.nanoTime()
                    videoPacketsCount++
                    videoBytesCounter += length + 16
                    lastVideoPacketTimestamp = SystemClock.elapsedRealtime()

                    // Hand to MjpegPlayer (latest-frame-wins, kernel buffer always drains, no ACK idling)
                    mjpegPlayer.submit(buffer, length, seq, arrivalNs)
                }
            } catch (e: SocketTimeoutException) {
                statusDetailMessage = "Connect timed out (wrong IP / different network / client isolation)"
                onLog("RX-Relay", "Video connect timeout: ${e.message}", true)
            } catch (e: ConnectException) {
                statusDetailMessage = "Connection refused (TX not running or wrong port)"
                onLog("RX-Relay", "Video connection refused: ${e.message}", true)
            } catch (e: Exception) {
                if (!isManuallyStopped.get() && gen == connectionGeneration.get()) {
                    statusDetailMessage = "Video error: ${e.message ?: "Connection closed"}"
                    onLog("RX-Relay", "Video connection exception: ${e.message}", false)
                }
            } finally {
                isVideoSocketConnected = false
                try { socket?.close() } catch (_: Exception) {}
                if (gen == connectionGeneration.get()) {
                    videoSocket = null
                }
            }

            if (!isManuallyStopped.get() && gen == connectionGeneration.get()) {
                _connectionState.value = RxConnectionState.RECONNECTING
                updateStatsState(force = true)
                delay(2000)
            } else {
                break
            }
        }
    }

    private suspend fun runAudioLoop(host: String, port: Int, gen: Int) {
        while (isRunning.get() && !isManuallyStopped.get() && gen == connectionGeneration.get()) {
            var socket: Socket? = null
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.sendBufferSize = 64 * 1024
                s.receiveBufferSize = 64 * 1024
                s.connect(InetSocketAddress(host, port), 4000)

                if (gen != connectionGeneration.get() || !isRunning.get()) {
                    try { s.close() } catch (_: Exception) {}
                    break
                }

                socket = s
                audioSocket = s
                isAudioSocketConnected = true
                onLog("RX-Relay", "Audio socket connected to $host:$port", false)

                val inputStream = DataInputStream(BufferedInputStream(s.getInputStream(), 16 * 1024))

                while (isRunning.get() && gen == connectionGeneration.get() && !s.isClosed && s.isConnected) {
                    val length = inputStream.readInt()
                    if (length <= 0 || length > 100_000) {
                        throw IOException("Invalid audio chunk length received: $length")
                    }

                    val pcmBytes = ByteArray(length)
                    inputStream.readFully(pcmBytes)

                    audioPacketsCount++

                    val profile = _audioLatencyProfile.value
                    val delayMs = _audioDelayMs.value
                    val capMaxBytes = (profile.capMaxMs + delayMs) * BYTES_PER_MS

                    // Push to jitter buffer & keep capped
                    synchronized(audioJitterLock) {
                        audioJitterQueue.addLast(pcmBytes)
                        audioJitterBufferedBytes += pcmBytes.size

                        // When above the cap drop the oldest in 20 ms steps
                        while (audioJitterBufferedBytes > capMaxBytes && audioJitterQueue.isNotEmpty()) {
                            val dropped = audioJitterQueue.pollFirst()
                            if (dropped != null) {
                                audioJitterBufferedBytes -= dropped.size
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!isManuallyStopped.get() && gen == connectionGeneration.get()) {
                    onLog("RX-Relay", "Audio socket note: ${e.message}", false)
                }
            } finally {
                isAudioSocketConnected = false
                try { socket?.close() } catch (_: Exception) {}
                if (gen == connectionGeneration.get()) {
                    audioSocket = null
                }
            }

            if (!isManuallyStopped.get() && gen == connectionGeneration.get()) {
                delay(2000)
            } else {
                break
            }
        }
    }

    private fun startRttProbe(host: String, port: Int, gen: Int) {
        rttJob?.cancel()
        rttJob = clientScope.launch {
            val rttPort = port + 2
            while (isRunning.get() && !isManuallyStopped.get() && gen == connectionGeneration.get()) {
                var socket: Socket? = null
                try {
                    val s = Socket()
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(host, rttPort), 3000)
                    socket = s
                    val dout = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1024))
                    val din = DataInputStream(BufferedInputStream(s.getInputStream(), 1024))

                    while (isRunning.get() && gen == connectionGeneration.get() && !s.isClosed && s.isConnected) {
                        val sentNs = System.nanoTime()
                        dout.writeLong(sentNs)
                        dout.flush()

                        val echoedNs = din.readLong()
                        val rttMs = (System.nanoTime() - echoedNs) / 1_000_000f
                        if (rttMs in 0.05f..1000f) {
                            synchronized(rttSamplesMs) {
                                rttSamplesMs.add(rttMs)
                                if (rttSamplesMs.size > 30) {
                                    rttSamplesMs.removeAt(0)
                                }
                                rttAvgMs = (rttSamplesMs.average().toFloat() * 10f).let { Math.round(it) / 10f }
                                val sorted = rttSamplesMs.sorted()
                                val p95Idx = ((sorted.size - 1) * 0.95f).toInt()
                                rttP95Ms = (sorted[p95Idx] * 10f).let { Math.round(it) / 10f }
                            }
                        }
                        delay(500)
                    }
                } catch (_: Exception) {
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                }
                delay(2000)
            }
        }
    }

    private fun startAudioPlaybackThread(gen: Int) {
        audioPlaybackJob?.cancel()
        audioPlaybackJob = clientScope.launch(Dispatchers.Default) {
            initAudioTrack()
            val track = audioTrack ?: return@launch
            val silenceChunk = ByteArray(SILENCE_CHUNK_BYTES)
            var isPrebuffering = true

            while (isActive && isRunning.get() && gen == connectionGeneration.get()) {
                val profile = _audioLatencyProfile.value
                val delayMs = _audioDelayMs.value
                val targetMinBytes = (profile.targetMinMs + delayMs) * BYTES_PER_MS
                val capMaxBytes = (profile.capMaxMs + delayMs) * BYTES_PER_MS

                var chunkToPlay: ByteArray? = null

                synchronized(audioJitterLock) {
                    if (isPrebuffering) {
                        if (audioJitterBufferedBytes >= targetMinBytes) {
                            isPrebuffering = false
                            chunkToPlay = audioJitterQueue.pollFirst()
                            chunkToPlay?.let { audioJitterBufferedBytes -= it.size }
                        }
                    } else {
                        chunkToPlay = audioJitterQueue.pollFirst()
                        if (chunkToPlay != null) {
                            audioJitterBufferedBytes -= chunkToPlay.size
                        }
                    }

                    // Strict cap enforcement
                    while (audioJitterBufferedBytes > capMaxBytes && audioJitterQueue.isNotEmpty()) {
                        val dropped = audioJitterQueue.pollFirst()
                        if (dropped != null) {
                            audioJitterBufferedBytes -= dropped.size
                        }
                    }
                }

                if (chunkToPlay != null) {
                    if (!_isMuted.value) {
                        track.write(chunkToPlay, 0, chunkToPlay.size)
                    }
                    if (!_isAudioPlaying.value) {
                        _isAudioPlaying.value = true
                    }
                } else {
                    // On underrun write at most 20 ms of silence and resume as soon as one chunk arrives (no full re-prebuffer)
                    if (!isPrebuffering) {
                        if (!_isMuted.value) {
                            track.write(silenceChunk, 0, silenceChunk.size)
                        } else {
                            delay(20)
                        }
                    } else {
                        delay(5)
                    }
                }
            }
        }
    }

    private fun initAudioTrack() {
        try {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG_OUT)
                .build()

            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuf)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } else {
                AudioTrack(
                    attributes,
                    format,
                    minBuf,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    track.setBufferSizeInFrames(minBuf / 4)
                } catch (_: Exception) {}
            }

            track.play()
            audioTrack = track
        } catch (e: Exception) {
            onLog("RX-Audio", "Failed to initialize AudioTrack: ${e.message}", true)
        }
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        _isAudioPlaying.value = false
    }

    private fun updateStatsState(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBytesSecondTimestamp >= 1000L) {
            val elapsedSec = (now - lastBytesSecondTimestamp) / 1000f
            currentBytesPerSec = if (elapsedSec > 0f) (videoBytesCounter / elapsedSec).toLong() else 0L
            videoBytesCounter = 0L
            lastBytesSecondTimestamp = now
        }

        val sinceLastVideo = if (lastVideoPacketTimestamp > 0L) {
            (now - lastVideoPacketTimestamp) / 1000f
        } else 0f

        val playerStats = mjpegPlayer.stats.value

        val underruns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { audioTrack?.underrunCount ?: 0 } catch (_: Exception) { 0 }
        } else 0

        _stats.value = RxStats(
            videoPacketsReceived = videoPacketsCount,
            videoBytesPerSecond = currentBytesPerSec,
            audioPacketsReceived = audioPacketsCount,
            secondsSinceLastVideoPacket = (sinceLastVideo * 10f).let { Math.round(it) / 10f },
            decodeSuccesses = playerStats.decodeSuccessCount,
            decodeFailures = playerStats.decodeFailureCount,
            lastDecodeError = playerStats.lastError ?: "None",
            lastFrameFirst4BytesHex = if (playerStats.lastHex4.isNotEmpty()) playerStats.lastHex4 else "--",
            lastFrameEndsEoi = playerStats.lastEndsEoi,
            decodedBitmapSize = if (playerStats.resolution.isNotEmpty()) playerStats.resolution else "--",
            fps = playerStats.fps,
            decodeTimeMs = playerStats.decodeTimeMsAvg.toLong(),
            decodeTimeMsAvg = playerStats.decodeTimeMsAvg,
            decodeTimeMsMax = playerStats.decodeTimeMsMax,
            frameAgeMs = playerStats.frameAgeMs,
            arrivalIntervalP95Ms = playerStats.arrivalIntervalP95Ms,
            droppedFrames = playerStats.droppedFrames,
            rttAvgMs = rttAvgMs,
            rttP95Ms = rttP95Ms,
            audioUnderrunCount = underruns,
            frameSizeBytes = playerStats.lastFrameSizeBytes,
            statusDetail = statusDetailMessage
        )
    }

    fun release() {
        disconnect()
        mjpegPlayer.release()
    }
}
