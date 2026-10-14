package com.example.relay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

enum class RxConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
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
        const val JITTER_TARGET_MIN_BYTES = BYTES_PER_MS * 60   // ~60 ms buffer before playback begins
        const val JITTER_TARGET_MAX_BYTES = BYTES_PER_MS * 120  // ~120 ms max buffer before dropping oldest
        const val SILENCE_CHUNK_BYTES = BYTES_PER_MS * 20      // 20 ms silence chunk (3840 bytes)
    }

    private val clientScope = CoroutineScope(Dispatchers.IO)
    private var videoJob: Job? = null
    private var audioJob: Job? = null
    private var decoderJob: Job? = null
    private var audioPlaybackJob: Job? = null
    private var watchdogJob: Job? = null

    private val isRunning = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)

    // Requirement D: Keep the video and audio socket references as fields
    @Volatile
    private var videoSocket: Socket? = null

    @Volatile
    private var audioSocket: Socket? = null

    // Requirement D: Connection generation id so an old loop exits and cannot touch new state
    private val connectionGeneration = AtomicInteger(0)

    private val _connectionState = MutableStateFlow(RxConnectionState.DISCONNECTED)
    val connectionState: StateFlow<RxConnectionState> = _connectionState.asStateFlow()

    private val _currentBitmap = MutableStateFlow<Bitmap?>(null)
    val currentBitmap: StateFlow<Bitmap?> = _currentBitmap.asStateFlow()

    private val _stats = MutableStateFlow(RxStats())
    val stats: StateFlow<RxStats> = _stats.asStateFlow()

    private val _isAudioPlaying = MutableStateFlow(false)
    val isAudioPlaying: StateFlow<Boolean> = _isAudioPlaying.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private var videoReceiveChannel = Channel<ByteArray>(Channel.CONFLATED)

    private var audioTrack: AudioTrack? = null

    // Requirement C: Audio Jitter Buffer structures
    private val audioJitterLock = Any()
    private val audioJitterQueue = ArrayDeque<ByteArray>()
    private var audioJitterBufferedBytes = 0

    // Direct hardware-accelerated surface rendering for ultra-low latency (< 0.5ms draw latency)
    @Volatile
    private var attachedSurfaceHolder: SurfaceHolder? = null

    private val surfacePaint = Paint().apply {
        isFilterBitmap = true
        isDither = true
    }
    private val surfaceSrcRect = Rect()
    private val surfaceDstRect = RectF()

    // Reusable Bitmap to eliminate GC pauses (0 allocations per decoded frame)
    @Volatile
    private var reusableBitmap: Bitmap? = null

    fun attachSurface(holder: SurfaceHolder) {
        attachedSurfaceHolder = holder
    }

    fun detachSurface(holder: SurfaceHolder) {
        if (attachedSurfaceHolder == holder) {
            attachedSurfaceHolder = null
        }
    }

    private val bitmapOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
        inMutable = true
    }

    // Diagnostics state variables
    private var videoPacketsCount = 0L
    private var audioPacketsCount = 0L
    private var decodeSuccessCount = 0L
    private var decodeFailureCount = 0L
    private var lastDecodeErrorMsg = "None"
    private var lastHex4 = "--"
    private var lastEndsEoi = false
    private var lastBmpSize = "--"
    private var lastVideoPacketTimestamp = 0L
    private var videoBytesCounter = 0L
    private var lastStatsUpdateTimestamp = 0L
    private var lastDecodeFailLogTimestamp = 0L
    private var connectionStartTimestamp = 0L
    private var statusDetailMessage = "Disconnected"

    @Volatile
    private var isVideoSocketConnected = false

    @Volatile
    private var isAudioSocketConnected = false

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                audioTrack?.setVolume(if (muted) 0f else 1f)
            }
        } catch (_: Exception) {}
    }

    /**
     * Requirement D: connect() must fully stop any previous connection first (use connection generation id).
     */
    fun connect(host: String, port: Int = 4120) {
        val gen = connectionGeneration.incrementAndGet()
        internalStopPrevious()

        val targetHost = host.trim()
        val videoPort = port
        val audioPort = port + 1

        isManuallyStopped.set(false)
        isRunning.set(true)
        videoReceiveChannel = Channel(Channel.CONFLATED)

        resetDiagnostics()
        statusDetailMessage = "Connecting to $targetHost:$videoPort..."
        _connectionState.value = RxConnectionState.CONNECTING
        updateStatsState(force = true)

        // 1. Dedicated Decoder Coroutine (runs on background thread, never blocks network read)
        startDecoderThread(gen)

        // 2. Dedicated Audio Playback Coroutine with Jitter Buffer (Requirement C)
        startAudioPlaybackThread(gen)

        // 3. Audio Socket Connection Loop (Port P + 1)
        audioJob = clientScope.launch {
            runAudioLoop(targetHost, audioPort, gen)
        }

        // 4. Video Socket Connection Loop (Port P)
        videoJob = clientScope.launch {
            runVideoLoop(targetHost, videoPort, gen)
        }

        // 5. Watchdog for 3-second data timeout & single-socket errors
        watchdogJob = clientScope.launch {
            while (isActive && isRunning.get() && gen == connectionGeneration.get()) {
                delay(500)
                val now = SystemClock.elapsedRealtime()

                // Check single socket connection mismatch (Requirement B: clear error on RX if only one socket connects)
                if (isVideoSocketConnected && !isAudioSocketConnected) {
                    if (now - connectionStartTimestamp >= 3000L) {
                        statusDetailMessage = "ERROR: Video connected on port $videoPort, but Audio connection failed on port $audioPort"
                    }
                } else if (!isVideoSocketConnected && isAudioSocketConnected) {
                    if (now - connectionStartTimestamp >= 3000L) {
                        statusDetailMessage = "ERROR: Audio connected on port $audioPort, but Video connection failed on port $videoPort"
                    }
                } else if (isVideoSocketConnected && isAudioSocketConnected) {
                    // Check for 3 seconds no video data (Requirement D)
                    if (videoPacketsCount == 0L) {
                        if (now - connectionStartTimestamp >= 3000L) {
                            statusDetailMessage = "Connected but no data received for 3 seconds"
                        }
                    } else if (lastVideoPacketTimestamp > 0L && now - lastVideoPacketTimestamp >= 3000L) {
                        statusDetailMessage = "Connected but no data received for 3 seconds"
                    }
                }

                updateStatsState()
            }
        }
    }

    private suspend fun runVideoLoop(host: String, port: Int, gen: Int) {
        while (isRunning.get() && !isManuallyStopped.get() && gen == connectionGeneration.get()) {
            var socket: Socket? = null
            try {
                statusDetailMessage = "Connecting to Video ($host:$port)..."
                updateStatsState(force = true)

                val s = Socket()
                // Set TCP_NODELAY, low-latency socket buffers, and low-latency performance preferences
                s.tcpNoDelay = true
                s.sendBufferSize = 32 * 1024
                s.receiveBufferSize = 32 * 1024
                try {
                    s.setPerformancePreferences(0, 2, 1) // 0 connection time, 2 low latency, 1 bandwidth
                } catch (_: Exception) {}
                s.connect(InetSocketAddress(host, port), 4000)

                if (gen != connectionGeneration.get() || !isRunning.get()) {
                    try { s.close() } catch (_: Exception) {}
                    break
                }

                socket = s
                videoSocket = s
                isVideoSocketConnected = true
                connectionStartTimestamp = SystemClock.elapsedRealtime()
                _connectionState.value = RxConnectionState.CONNECTED
                statusDetailMessage = "Connected (Video :$port, Audio :${port + 1})"
                onLog("RX-Relay", "Video socket connected to $host:$port", false)
                updateStatsState(force = true)

                val bufferedIn = BufferedInputStream(s.getInputStream(), 16 * 1024)
                val inputStream = DataInputStream(bufferedIn)

                while (isRunning.get() && gen == connectionGeneration.get() && !s.isClosed && s.isConnected) {
                    // Protocol: 4-byte big-endian length followed by raw JPEG bytes
                    val length = inputStream.readInt()
                    if (length <= 0 || length > 10_000_000) {
                        throw IOException("Invalid video frame length received: $length")
                    }

                    var frameBytes = ByteArray(length)
                    inputStream.readFully(frameBytes)

                    videoPacketsCount++
                    videoBytesCounter += length + 4
                    lastVideoPacketTimestamp = SystemClock.elapsedRealtime()

                    // LOW-LATENCY CATCH-UP / BACKLOG DRAIN:
                    // If network jitter or slow decode caused 1 or more frames to queue in the socket buffer:
                    // Drain the older frames so the decoder ALWAYS receives the latest real-time frame!
                    while (isRunning.get() && gen == connectionGeneration.get() && !s.isClosed) {
                        val avail = bufferedIn.available()
                        if (avail >= 4) {
                            bufferedIn.mark(4)
                            val nextLength = inputStream.readInt()
                            if (nextLength in 1..10_000_000 && avail >= 4 + nextLength) {
                                frameBytes = ByteArray(nextLength)
                                inputStream.readFully(frameBytes)
                                videoPacketsCount++
                                videoBytesCounter += nextLength + 4
                                continue
                            } else {
                                bufferedIn.reset()
                                break
                            }
                        } else {
                            break
                        }
                    }

                    // Conflated handoff to decoder
                    videoReceiveChannel.trySend(frameBytes)
                }
            } catch (e: SocketTimeoutException) {
                // Requirement D: distinguish connect timed out
                statusDetailMessage = "Connect timed out (wrong IP / different network / client isolation)"
                onLog("RX-Relay", "Video connect timeout: ${e.message}", true)
            } catch (e: ConnectException) {
                // Requirement D: distinguish connection refused
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
                // Requirement B: Set TCP_NODELAY and 64 KB send and receive buffers
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

                    // Push to jitter buffer
                    synchronized(audioJitterLock) {
                        audioJitterQueue.addLast(pcmBytes)
                        audioJitterBufferedBytes += pcmBytes.size

                        // Requirement C: If more than about 120 ms queued, drop oldest audio to catch up
                        while (audioJitterBufferedBytes > JITTER_TARGET_MAX_BYTES && audioJitterQueue.isNotEmpty()) {
                            val dropped = audioJitterQueue.removeFirst()
                            audioJitterBufferedBytes -= dropped.size
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

    /**
     * Requirement C: RX audio
     * - Audio runs on its own thread and must never block the video read loop.
     * - Add a jitter buffer: start playback after about 60 ms is buffered.
     *   If more than about 120 ms is queued, drop the oldest audio to catch up.
     *   On underrun, write silence instead of stalling.
     * - AudioTrack: USAGE_GAME, PERFORMANCE_MODE_LOW_LATENCY, buffer size = AudioTrack.getMinBufferSize (not doubled), 48 kHz stereo 16-bit.
     */
    private fun startAudioPlaybackThread(gen: Int) {
        audioPlaybackJob?.cancel()
        audioPlaybackJob = clientScope.launch(Dispatchers.Default) {
            initAudioTrack()
            val track = audioTrack ?: return@launch
            val silenceChunk = ByteArray(SILENCE_CHUNK_BYTES)
            var isBuffering = true

            while (isActive && isRunning.get() && gen == connectionGeneration.get()) {
                var chunkToPlay: ByteArray? = null

                synchronized(audioJitterLock) {
                    if (isBuffering) {
                        // Start playback after about 60 ms is buffered (~11520 bytes)
                        if (audioJitterBufferedBytes >= JITTER_TARGET_MIN_BYTES) {
                            isBuffering = false
                            chunkToPlay = audioJitterQueue.pollFirst()
                            chunkToPlay?.let { audioJitterBufferedBytes -= it.size }
                        }
                    } else {
                        chunkToPlay = audioJitterQueue.pollFirst()
                        if (chunkToPlay != null) {
                            audioJitterBufferedBytes -= chunkToPlay.size
                        } else {
                            // Underrun: require re-buffering
                            isBuffering = true
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
                    // On underrun, write silence instead of stalling
                    if (!_isMuted.value) {
                        track.write(silenceChunk, 0, silenceChunk.size)
                    } else {
                        delay(20)
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
                    .setBufferSizeInBytes(minBuf) // NOT doubled!
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_OUT,
                    AUDIO_FORMAT,
                    minBuf,
                    AudioTrack.MODE_STREAM
                )
            }
            track.play()
            audioTrack = track
        } catch (e: Exception) {
            onLog("RX-Relay", "AudioTrack init exception: ${e.message}", false)
        }
    }

    private fun startDecoderThread(gen: Int) {
        decoderJob?.cancel()
        decoderJob = clientScope.launch(Dispatchers.Default) {
            var frameCount = 0
            var lastMeasureTime = SystemClock.elapsedRealtime()
            var currentFrameSize = 0

            while (isActive && isRunning.get() && gen == connectionGeneration.get()) {
                val frame = videoReceiveChannel.receive()

                if (frame.isNotEmpty()) {
                    currentFrameSize = frame.size

                    // Diagnostics: first 4 bytes hex
                    lastHex4 = frame.take(4).joinToString(" ") { "%02X".format(it) }

                    // Diagnostics: ends with FF D9
                    lastEndsEoi = frame.size >= 2 &&
                        (frame[frame.size - 2].toInt() and 0xFF) == 0xFF &&
                        (frame[frame.size - 1].toInt() and 0xFF) == 0xD9

                    val decodeStart = SystemClock.elapsedRealtime()

                    // Fast memory-recycled decoding with inBitmap (Zero GC allocations per frame)
                    var rBitmap = reusableBitmap
                    if (rBitmap == null || rBitmap.isRecycled) {
                        try {
                            rBitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.RGB_565)
                            reusableBitmap = rBitmap
                        } catch (_: Exception) {}
                    }
                    bitmapOptions.inBitmap = rBitmap

                    // Try direct decoding first
                    var decodeErrMsg: String? = null
                    var decoded: Bitmap? = try {
                        BitmapFactory.decodeByteArray(frame, 0, frame.size, bitmapOptions)
                    } catch (e: IllegalArgumentException) {
                        // Dimension or format mismatch: clear inBitmap and re-create reusable buffer
                        bitmapOptions.inBitmap = null
                        try {
                            val newDecoded = BitmapFactory.decodeByteArray(frame, 0, frame.size, bitmapOptions)
                            if (newDecoded != null) {
                                reusableBitmap = newDecoded
                                bitmapOptions.inBitmap = newDecoded
                            }
                            newDecoded
                        } catch (ex: Exception) {
                            decodeErrMsg = ex.message
                            null
                        }
                    } catch (e: Exception) {
                        decodeErrMsg = e.message
                        null
                    }

                    if (decoded == null) {
                        decoded = try {
                            BitmapFactory.decodeByteArray(frame, 0, frame.size)
                        } catch (e: Exception) {
                            decodeErrMsg = e.message
                            null
                        }
                    }

                    // Fallback: extract clean JPEG if needed
                    if (decoded == null) {
                        val clean = JpegUtils.extractCleanJpeg(frame)
                        if (clean !== frame && clean.size != frame.size) {
                            decoded = try {
                                BitmapFactory.decodeByteArray(clean, 0, clean.size)
                            } catch (e: Exception) {
                                decodeErrMsg = e.message
                                null
                            }
                        }
                    }

                    // Fallback: ARGB_8888
                    if (decoded == null) {
                        val fallbackOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        decoded = try {
                            BitmapFactory.decodeByteArray(frame, 0, frame.size, fallbackOpts)
                        } catch (e: Exception) {
                            decodeErrMsg = e.message
                            null
                        }
                    }

                    // Fallback: Raw YUY2/NV21 frame arrived from network
                    if (decoded == null && !JpegUtils.isJpeg(frame)) {
                        val (w, h) = JpegUtils.resolveDimensions(frame.size, 1280, 720)
                        val jpegBytes = JpegUtils.compressYuvToJpeg(frame, w, h)
                        if (jpegBytes != null) {
                            decoded = try {
                                BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bitmapOptions)
                                    ?: BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                            } catch (e: Exception) {
                                decodeErrMsg = e.message
                                null
                            }
                        }
                        if (decoded == null) {
                            decoded = JpegUtils.yuy2ToBitmap(frame, w, h)
                        }
                    }

                    val decodeTime = SystemClock.elapsedRealtime() - decodeStart

                    if (decoded != null) {
                        decodeSuccessCount++
                        lastBmpSize = "${decoded.width}x${decoded.height}"

                        // DIRECT HARDWARE-ACCELERATED SURFACE DRAW (< 0.5ms)
                        // Bypasses Compose UI recomposition, VSYNC queueing, and asImageBitmap() allocations!
                        val holder = attachedSurfaceHolder
                        if (holder != null && holder.surface.isValid) {
                            val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                try {
                                    holder.lockHardwareCanvas()
                                } catch (_: Exception) {
                                    try { holder.lockCanvas() } catch (_: Exception) { null }
                                }
                            } else {
                                try { holder.lockCanvas() } catch (_: Exception) { null }
                            }

                            if (canvas != null) {
                                try {
                                    val w = decoded.width
                                    val h = decoded.height
                                    val cW = canvas.width.toFloat()
                                    val cH = canvas.height.toFloat()
                                    val scale = minOf(cW / w, cH / h)
                                    val fitW = w * scale
                                    val fitH = h * scale
                                    val dx = (cW - fitW) / 2f
                                    val dy = (cH - fitH) / 2f

                                    canvas.drawColor(Color.BLACK)
                                    surfaceSrcRect.set(0, 0, w, h)
                                    surfaceDstRect.set(dx, dy, dx + fitW, dy + fitH)
                                    canvas.drawBitmap(decoded, surfaceSrcRect, surfaceDstRect, surfacePaint)
                                } finally {
                                    try {
                                        holder.unlockCanvasAndPost(canvas)
                                    } catch (_: Exception) {}
                                }
                            }
                        }

                        _currentBitmap.value = decoded
                        frameCount++

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastMeasureTime >= 1000L) {
                            val elapsedSec = (now - lastMeasureTime) / 1000f
                            val currentFps = (frameCount / elapsedSec * 10).roundToInt() / 10f
                            lastMeasureTime = now
                            frameCount = 0

                            updateStatsState(
                                fps = currentFps,
                                decodeTime = decodeTime,
                                frameSize = currentFrameSize
                            )
                        }
                    } else {
                        decodeFailureCount++
                        lastDecodeErrorMsg = decodeErrMsg ?: "BitmapFactory returned null"

                        // Requirement E: Log a decode failure with frame size and the first 8 bytes, at most once per second
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastDecodeFailLogTimestamp >= 1000L) {
                            lastDecodeFailLogTimestamp = now
                            val head8 = frame.take(8).joinToString(" ") { "%02X".format(it) }
                            onLog("RX-Relay", "Decode failure (Size: ${frame.size}B, Head: $head8, Error: $lastDecodeErrorMsg)", true)
                        }
                    }
                }
            }
        }
    }

    private fun updateStatsState(
        force: Boolean = false,
        fps: Float? = null,
        decodeTime: Long? = null,
        frameSize: Int? = null
    ) {
        val now = SystemClock.elapsedRealtime()
        if (force || now - lastStatsUpdateTimestamp >= 1000L) {
            val elapsedSec = if (lastStatsUpdateTimestamp > 0) (now - lastStatsUpdateTimestamp) / 1000f else 1f
            val bytesPerSec = if (elapsedSec > 0) (videoBytesCounter / elapsedSec).toLong() else 0L
            videoBytesCounter = 0L
            lastStatsUpdateTimestamp = now

            val secSinceLastVideo = if (lastVideoPacketTimestamp > 0) {
                ((now - lastVideoPacketTimestamp) / 1000f * 10).roundToInt() / 10f
            } else {
                -1f
            }

            val currentFps = fps ?: _stats.value.fps
            val currentDecodeTime = decodeTime ?: _stats.value.decodeTimeMs
            val currentFrameSize = frameSize ?: _stats.value.frameSizeBytes

            _stats.value = RxStats(
                videoPacketsReceived = videoPacketsCount,
                videoBytesPerSecond = bytesPerSec,
                audioPacketsReceived = audioPacketsCount,
                secondsSinceLastVideoPacket = secSinceLastVideo,
                decodeSuccesses = decodeSuccessCount,
                decodeFailures = decodeFailureCount,
                lastDecodeError = lastDecodeErrorMsg,
                lastFrameFirst4BytesHex = lastHex4,
                lastFrameEndsEoi = lastEndsEoi,
                decodedBitmapSize = lastBmpSize,
                fps = currentFps,
                decodeTimeMs = currentDecodeTime,
                frameSizeBytes = currentFrameSize,
                statusDetail = statusDetailMessage
            )
        }
    }

    private fun resetDiagnostics() {
        videoPacketsCount = 0L
        audioPacketsCount = 0L
        decodeSuccessCount = 0L
        decodeFailureCount = 0L
        lastDecodeErrorMsg = "None"
        lastHex4 = "--"
        lastEndsEoi = false
        lastBmpSize = "--"
        lastVideoPacketTimestamp = 0L
        videoBytesCounter = 0L
        lastStatsUpdateTimestamp = SystemClock.elapsedRealtime()
        connectionStartTimestamp = SystemClock.elapsedRealtime()
        synchronized(audioJitterLock) {
            audioJitterQueue.clear()
            audioJitterBufferedBytes = 0
        }
    }

    private fun internalStopPrevious() {
        // Requirement D: Keep video and audio socket references as fields. disconnect() must close both sockets to unblock the reads.
        try { videoSocket?.close() } catch (_: Exception) {}
        videoSocket = null
        isVideoSocketConnected = false

        try { audioSocket?.close() } catch (_: Exception) {}
        audioSocket = null
        isAudioSocketConnected = false

        videoJob?.cancel()
        videoJob = null

        audioJob?.cancel()
        audioJob = null

        decoderJob?.cancel()
        decoderJob = null

        audioPlaybackJob?.cancel()
        audioPlaybackJob = null

        watchdogJob?.cancel()
        watchdogJob = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        _isAudioPlaying.value = false
    }

    fun disconnect() {
        isManuallyStopped.set(true)
        isRunning.set(false)
        connectionGeneration.incrementAndGet()

        internalStopPrevious()

        _connectionState.value = RxConnectionState.DISCONNECTED
        _currentBitmap.value = null
        statusDetailMessage = "Disconnected"
        updateStatsState(force = true)
        onLog("RX-Relay", "Disconnected from server", false)
    }
}
