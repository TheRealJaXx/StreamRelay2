package com.example.relay

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureEngine(
    private val context: Context,
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_STEREO
        const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val audioScope = CoroutineScope(Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private var captureJob: Job? = null
    private var localPlaybackJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var localAudioTrack: AudioTrack? = null

    // Non-blocking channel for local playback loop: drops oldest audio if playback falls behind
    private val localPlaybackChannel = Channel<ByteArray>(capacity = 3, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    // CRITICAL: Local phone speaker monitor MUST default to FALSE (muted).
    // Routing microphone input straight into the phone speaker creates an immediate,
    // deafening acoustic feedback loop (howl-round).
    private val _isLocalPlaybackEnabled = MutableStateFlow(false)
    val isLocalPlaybackEnabled: StateFlow<Boolean> = _isLocalPlaybackEnabled.asStateFlow()

    private val _isUsbAudioConnected = MutableStateFlow(false)
    val isUsbAudioConnected: StateFlow<Boolean> = _isUsbAudioConnected.asStateFlow()

    private val _activeDeviceName = MutableStateFlow("Waiting for USB Audio...")
    val activeDeviceName: StateFlow<String> = _activeDeviceName.asStateFlow()

    var onAudioChunkCaptured: ((ByteArray) -> Unit)? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var deviceCallback: AudioDeviceCallback? = null

    init {
        setupDeviceCallback()
        val initialUsb = findUsbAudioDevice()
        if (initialUsb != null) {
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                initialUsb.productName?.toString() ?: "USB Capture Audio"
            } else "USB Capture Audio"
            _activeDeviceName.value = name
            _isUsbAudioConnected.value = true
        }
    }

    private fun setupDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null) {
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    val usb = addedDevices?.firstOrNull { isUsbAudioDevice(it) }
                    if (usb != null) {
                        val name = usb.productName?.toString() ?: "USB Capture Audio"
                        onLog("TX-Audio", "USB Audio device connected: $name", false)
                        _activeDeviceName.value = name
                        _isUsbAudioConnected.value = true
                        if (isRunning.get()) {
                            startCaptureLoop()
                        }
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    val usb = removedDevices?.firstOrNull { isUsbAudioDevice(it) }
                    if (usb != null) {
                        onLog("TX-Audio", "USB Audio device disconnected: ${usb.productName}", false)
                        _isUsbAudioConnected.value = false
                        _activeDeviceName.value = "No USB Audio (Phone Mic)"

                        // Immediately kill local phone speaker to prevent howling feedback
                        stopLocalPlayback()
                        _isLocalPlaybackEnabled.value = false

                        if (isRunning.get()) {
                            startCaptureLoop()
                        }
                    }
                }
            }
            audioManager.registerAudioDeviceCallback(callback, null)
            deviceCallback = callback
        }
    }

    private fun isUsbAudioDevice(device: AudioDeviceInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.isSource && (
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            )
        } else false
    }

    private fun findUsbAudioDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            return devices.firstOrNull { isUsbAudioDevice(it) }
        }
        return null
    }

    /**
     * Toggles local audio playback through the phone speaker.
     * ANTI-FEEDBACK GUARD: Local speaker playback is strictly prohibited when using
     * the phone's internal microphone, as doing so creates a violent howl-round.
     */
    fun setLocalPlaybackEnabled(enabled: Boolean) {
        if (enabled && !_isUsbAudioConnected.value) {
            onLog(
                "TX-Audio",
                "Speaker monitor blocked: internal mic cannot be played on phone speaker (prevents howl-round). Connect a USB audio card first.",
                true
            )
            _isLocalPlaybackEnabled.value = false
            stopLocalPlayback()
            return
        }

        _isLocalPlaybackEnabled.value = enabled
        if (!enabled) {
            stopLocalPlayback()
            onLog("TX-Audio", "Phone speaker monitor muted", false)
        } else {
            startLocalPlaybackWorker()
            onLog("TX-Audio", "Phone speaker monitor enabled (USB Audio)", false)
        }
    }

    private fun stopLocalPlayback() {
        localPlaybackJob?.cancel()
        localPlaybackJob = null
        try {
            localAudioTrack?.stop()
            localAudioTrack?.release()
        } catch (_: Exception) {}
        localAudioTrack = null
    }

    private fun startLocalPlaybackWorker() {
        if (!_isUsbAudioConnected.value || !_isLocalPlaybackEnabled.value) {
            stopLocalPlayback()
            return
        }

        if (localAudioTrack == null) {
            localAudioTrack = initLocalAudioTrack(SAMPLE_RATE)
        }
        try {
            localAudioTrack?.play()
        } catch (_: Exception) {}

        if (localPlaybackJob?.isActive != true) {
            localPlaybackJob = audioScope.launch {
                while (isActive && isRunning.get()) {
                    val chunk = localPlaybackChannel.receive()
                    if (_isLocalPlaybackEnabled.value && _isUsbAudioConnected.value && localAudioTrack != null) {
                        try {
                            localAudioTrack?.write(chunk, 0, chunk.size)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    fun start() {
        if (isRunning.get()) return

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            onLog("TX-Audio", "Microphone/Audio permission required for audio capture.", false)
            return
        }

        isRunning.set(true)
        startCaptureLoop()
    }

    private fun startCaptureLoop() {
        captureJob?.cancel()
        stopLocalPlayback()

        // 1. Check for USB Audio hardware
        val usbDevice = findUsbAudioDevice()
        val isUsb = usbDevice != null
        _isUsbAudioConnected.value = isUsb

        val deviceName = if (isUsb) {
            usbDevice?.productName?.toString() ?: "USB Capture Audio"
        } else {
            "No USB Audio (Phone Mic)"
        }
        _activeDeviceName.value = deviceName

        // 2. Setup local speaker playback ONLY if explicitly enabled AND audio is from USB hardware
        if (_isLocalPlaybackEnabled.value && isUsb) {
            startLocalPlaybackWorker()
        } else {
            _isLocalPlaybackEnabled.value = false
        }

        // 3. High-priority capture loop with Stereo -> Mono fallback
        captureJob = audioScope.launch {
            var channelConfig = AudioFormat.CHANNEL_IN_STEREO
            var minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, AUDIO_FORMAT)
            var isMono = false

            if (minBufSize <= 0) {
                channelConfig = AudioFormat.CHANNEL_IN_MONO
                minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, AUDIO_FORMAT)
                isMono = true
            }

            val bytesPerMs = if (isMono) (SAMPLE_RATE * 2 / 1000) else (SAMPLE_RATE * 4 / 1000)
            val bufferSize = maxOf(if (minBufSize > 0) minBufSize else bytesPerMs * 50, bytesPerMs * 50)

            var record = initAudioRecord(SAMPLE_RATE, channelConfig, bufferSize, usbDevice)
            if (record == null && !isMono) {
                // If stereo initialization failed on hardware, fallback to mono
                channelConfig = AudioFormat.CHANNEL_IN_MONO
                val monoMin = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, AUDIO_FORMAT)
                val monoBytesPerMs = SAMPLE_RATE * 2 / 1000
                val monoBuf = maxOf(if (monoMin > 0) monoMin else monoBytesPerMs * 50, monoBytesPerMs * 50)
                record = initAudioRecord(SAMPLE_RATE, channelConfig, monoBuf, usbDevice)
                if (record != null) {
                    isMono = true
                }
            }

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                onLog("TX-Audio", "Failed to initialize AudioRecord. Retrying in 2s...", true)
                _isCapturing.value = false
                delay(2000)
                if (isRunning.get()) {
                    startCaptureLoop()
                }
                return@launch
            }

            audioRecord = record

            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}

            try {
                record.startRecording()
                _isCapturing.value = true
                val modeDesc = if (isMono) "48kHz Mono -> Stereo" else "48kHz Stereo"
                if (isUsb) {
                    onLog("TX-Audio", "Audio capturing from USB card: $deviceName ($modeDesc)", false)
                } else {
                    onLog("TX-Audio", "Audio capturing from mic ($modeDesc, Local speaker muted to prevent howl-round)", false)
                }

                // 20ms chunks: Mono = 1920 bytes, Stereo = 3840 bytes
                val targetChunkSize = if (isMono) (SAMPLE_RATE * 2) / 50 else (SAMPLE_RATE * 4) / 50
                val readBuf = ByteArray(targetChunkSize)
                var accumBuf = ByteArray(targetChunkSize * 4)
                var accumCount = 0

                while (isRunning.get() && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readBytes = record.read(readBuf, 0, readBuf.size)
                    if (readBytes > 0) {
                        // Ensure accumulator has enough capacity
                        if (accumCount + readBytes > accumBuf.size) {
                            val newSize = maxOf(accumBuf.size * 2, accumCount + readBytes)
                            val newBuf = ByteArray(newSize)
                            System.arraycopy(accumBuf, 0, newBuf, 0, accumCount)
                            accumBuf = newBuf
                        }
                        System.arraycopy(readBuf, 0, accumBuf, accumCount, readBytes)
                        accumCount += readBytes

                        // Extract complete 20ms aligned frames
                        while (accumCount >= targetChunkSize) {
                            val rawFrame = ByteArray(targetChunkSize)
                            System.arraycopy(accumBuf, 0, rawFrame, 0, targetChunkSize)

                            val remaining = accumCount - targetChunkSize
                            if (remaining > 0) {
                                System.arraycopy(accumBuf, targetChunkSize, accumBuf, 0, remaining)
                            }
                            accumCount = remaining

                            val stereoChunk = if (isMono) {
                                monoToStereo(rawFrame, targetChunkSize)
                            } else {
                                rawFrame
                            }

                            // ANTI-FEEDBACK: Only send to local phone speaker if capturing from USB hardware
                            if (_isLocalPlaybackEnabled.value && _isUsbAudioConnected.value) {
                                localPlaybackChannel.trySend(stereoChunk)
                            }

                            // Forward to network relay (stream to remote RX)
                            onAudioChunkCaptured?.invoke(stereoChunk)
                        }
                    }
                }
            } catch (e: Exception) {
                onLog("TX-Audio", "Audio capture loop ended: ${e.message}", false)
            } finally {
                _isCapturing.value = false
                try {
                    record.stop()
                    record.release()
                } catch (_: Exception) {}
                audioRecord = null
            }
        }
    }

    private fun monoToStereo(mono: ByteArray, length: Int): ByteArray {
        val numSamples = length / 2
        val stereo = ByteArray(numSamples * 4)
        var src = 0
        var dst = 0
        for (i in 0 until numSamples) {
            val b0 = mono[src]
            val b1 = mono[src + 1]
            src += 2
            stereo[dst] = b0
            stereo[dst + 1] = b1
            stereo[dst + 2] = b0
            stereo[dst + 3] = b1
            dst += 4
        }
        return stereo
    }

    private fun initAudioRecord(
        sampleRate: Int,
        channelConfig: Int,
        bufferSize: Int,
        usbDevice: AudioDeviceInfo?
    ): AudioRecord? {
        val audioSources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.UNPROCESSED
        )

        for (source in audioSources) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val builder = AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AUDIO_FORMAT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)

                    val record = builder.build()
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        if (usbDevice != null) {
                            try { record.setPreferredDevice(usbDevice) } catch (_: Exception) {}
                        }
                        return record
                    } else {
                        record.release()
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val record = AudioRecord(source, sampleRate, channelConfig, AUDIO_FORMAT, bufferSize)
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        return record
                    } else {
                        record.release()
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun initLocalAudioTrack(sampleRate: Int): AudioTrack? {
        return try {
            val minBufSize = maxOf(
                AudioTrack.getMinBufferSize(sampleRate, CHANNEL_CONFIG_OUT, AUDIO_FORMAT),
                (sampleRate * 4) / 20
            )
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(sampleRate)
                .setChannelMask(CHANNEL_CONFIG_OUT)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBufSize)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    CHANNEL_CONFIG_OUT,
                    AUDIO_FORMAT,
                    minBufSize,
                    AudioTrack.MODE_STREAM
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun stop() {
        isRunning.set(false)
        _isCapturing.value = false

        captureJob?.cancel()
        captureJob = null

        stopLocalPlayback()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    fun release() {
        stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null && deviceCallback != null) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            deviceCallback = null
        }
    }
}
