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

        // 3. High-priority capture loop
        captureJob = audioScope.launch {
            val minBufSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT),
                SAMPLE_RATE * 4 / 20 // 50ms buffer
            )

            val record = initAudioRecord(SAMPLE_RATE, minBufSize, usbDevice)
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                onLog("TX-Audio", "Failed to initialize AudioRecord. Retrying...", true)
                _isCapturing.value = false
                return@launch
            }

            audioRecord = record

            try {
                record.startRecording()
                _isCapturing.value = true
                if (isUsb) {
                    onLog("TX-Audio", "Audio capturing from USB card: $deviceName (48kHz Stereo)", false)
                } else {
                    onLog("TX-Audio", "Audio capturing from mic (Local speaker muted to prevent howl-round)", false)
                }

                // 20ms chunks (48000 samples/sec * 4 bytes/sample / 50 = 3840 bytes)
                val chunkSize = (SAMPLE_RATE * 4) / 50
                val pcmBuffer = ByteArray(chunkSize)

                while (isRunning.get() && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readBytes = record.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readBytes > 0) {
                        val chunkCopy = pcmBuffer.copyOf(readBytes)

                        // ANTI-FEEDBACK: Only send to local phone speaker if capturing from USB hardware
                        if (_isLocalPlaybackEnabled.value && _isUsbAudioConnected.value) {
                            localPlaybackChannel.trySend(chunkCopy)
                        }

                        // Forward to network relay (stream to remote RX)
                        onAudioChunkCaptured?.invoke(chunkCopy)
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

    private fun initAudioRecord(sampleRate: Int, bufferSize: Int, usbDevice: AudioDeviceInfo?): AudioRecord? {
        val audioSources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.CAMCORDER
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
                                .setChannelMask(CHANNEL_CONFIG_IN)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)

                    val record = builder.build()
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        if (usbDevice != null) {
                            record.setPreferredDevice(usbDevice)
                        }
                        return record
                    } else {
                        record.release()
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val record = AudioRecord(source, sampleRate, CHANNEL_CONFIG_IN, AUDIO_FORMAT, bufferSize)
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
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
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
