package com.example.relay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
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
    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

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

    private val _isAudioPlaying = MutableStateFlow(false)
    val isAudioPlaying: StateFlow<Boolean> = _isAudioPlaying.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    // Conflated channel holds only newest un-decoded video frame
    private var videoReceiveChannel = Channel<ByteArray>(Channel.CONFLATED)

    private var audioTrack: AudioTrack? = null

    private val bitmapOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
        inMutable = true
    }

    private var targetHost: String = ""
    private var targetPort: Int = 4120

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                audioTrack?.setVolume(if (muted) 0f else 1f)
            }
        } catch (_: Exception) {}
    }

    fun connect(host: String, port: Int = 4120) {
        disconnect()

        targetHost = host.trim()
        targetPort = port
        isManuallyStopped.set(false)
        isRunning.set(true)
        videoReceiveChannel = Channel(Channel.CONFLATED)

        startDecoderThread()

        connectionJob = clientScope.launch {
            while (isRunning.get() && !isManuallyStopped.get()) {
                _connectionState.value = RxConnectionState.CONNECTING
                onLog("RX-Relay", "Connecting to $targetHost:$targetPort (Video + Audio)...", false)

                var socket: Socket? = null
                try {
                    val s = Socket()
                    s.tcpNoDelay = true
                    s.receiveBufferSize = 256 * 1024
                    s.connect(InetSocketAddress(targetHost, targetPort), 4000)
                    socket = s

                    _connectionState.value = RxConnectionState.CONNECTED
                    onLog("RX-Relay", "Connected! Streaming synchronized video & audio...", false)

                    initAudioTrack()

                    val inputStream = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))

                    while (isRunning.get() && !s.isClosed && s.isConnected) {
                        val packetType = inputStream.readByte()
                        val length = inputStream.readInt()
                        if (length <= 0 || length > 10_000_000) {
                            throw IllegalStateException("Invalid packet length: $length (Type: $packetType)")
                        }

                        val payload = ByteArray(length)
                        inputStream.readFully(payload)

                        when (packetType) {
                            RelayServer.PKT_TYPE_VIDEO -> {
                                videoReceiveChannel.trySend(payload)
                            }
                            RelayServer.PKT_TYPE_AUDIO -> {
                                if (!_isMuted.value) {
                                    audioTrack?.write(payload, 0, payload.size)
                                }
                                if (!_isAudioPlaying.value) {
                                    _isAudioPlaying.value = true
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!isManuallyStopped.get()) {
                        onLog("RX-Relay", "Connection dropped: ${e.message ?: "Disconnected"}", true)
                    }
                } finally {
                    _isAudioPlaying.value = false
                    releaseAudioTrack()
                    try { socket?.close() } catch (_: Exception) {}
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

    private fun initAudioTrack() {
        try {
            val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 2
            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    minBufSize,
                    AudioTrack.MODE_STREAM
                )
            }
            track.play()
            audioTrack = track
        } catch (e: Exception) {
            onLog("RX-Relay", "AudioTrack init note: ${e.message}", false)
        }
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    private fun startDecoderThread() {
        decoderJob?.cancel()
        decoderJob = clientScope.launch(Dispatchers.Default) {
            var frameCount = 0
            var lastMeasureTime = SystemClock.elapsedRealtime()
            var totalCount = 0L

            while (isActive && isRunning.get()) {
                val frame = videoReceiveChannel.receive()

                if (frame.isNotEmpty()) {
                    val cleanFrame = JpegUtils.extractCleanJpeg(frame)
                    val decodeStart = SystemClock.elapsedRealtime()

                    var decoded = try {
                        BitmapFactory.decodeByteArray(cleanFrame, 0, cleanFrame.size, bitmapOptions)
                    } catch (_: Exception) {
                        null
                    }
                    if (decoded == null) {
                        decoded = try {
                            BitmapFactory.decodeByteArray(cleanFrame, 0, cleanFrame.size)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (decoded == null) {
                        val fallbackOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        decoded = try {
                            BitmapFactory.decodeByteArray(cleanFrame, 0, cleanFrame.size, fallbackOpts)
                        } catch (_: Exception) {
                            null
                        }
                    }

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
                                frameSizeBytes = cleanFrame.size,
                                totalFrames = totalCount
                            )
                        } else {
                            _stats.value = _stats.value.copy(
                                decodeTimeMs = decodeTime,
                                frameSizeBytes = cleanFrame.size,
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
        _isAudioPlaying.value = false

        connectionJob?.cancel()
        connectionJob = null

        decoderJob?.cancel()
        decoderJob = null

        releaseAudioTrack()

        _currentBitmap.value = null
        onLog("RX-Relay", "Disconnected from server", false)
    }
}
