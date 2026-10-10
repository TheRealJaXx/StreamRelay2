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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private var audioRecord: AudioRecord? = null
    private var localAudioTrack: AudioTrack? = null

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _isLocalPlaybackEnabled = MutableStateFlow(true)
    val isLocalPlaybackEnabled: StateFlow<Boolean> = _isLocalPlaybackEnabled.asStateFlow()

    private val _activeDeviceName = MutableStateFlow("Detecting USB Audio...")
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
                        onLog("TX-Audio", "USB Audio connected: ${usb.productName}", false)
                        _activeDeviceName.value = usb.productName?.toString() ?: "USB Capture Audio"
                        if (isRunning.get() && audioRecord == null) {
                            startCaptureLoop()
                        }
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    val usb = removedDevices?.firstOrNull { isUsbAudioDevice(it) }
                    if (usb != null) {
                        onLog("TX-Audio", "USB Audio disconnected: ${usb.productName}", false)
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

    fun setLocalPlaybackEnabled(enabled: Boolean) {
        _isLocalPlaybackEnabled.value = enabled
        if (!enabled) {
            try {
                localAudioTrack?.pause()
                localAudioTrack?.flush()
            } catch (_: Exception) {}
        } else {
            try {
                localAudioTrack?.play()
            } catch (_: Exception) {}
        }
        onLog("TX-Audio", if (enabled) "Phone speaker audio enabled" else "Phone speaker audio muted", false)
    }

    fun start() {
        if (isRunning.get()) return

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            onLog("TX-Audio", "Microphone/Audio permission required for HDMI audio capture.", false)
            return
        }

        isRunning.set(true)
        startCaptureLoop()
    }

    private fun startCaptureLoop() {
        captureJob?.cancel()
        captureJob = audioScope.launch {
            val usbDevice = findUsbAudioDevice()
            val deviceName = usbDevice?.productName?.toString() ?: "USB Capture Audio"
            _activeDeviceName.value = deviceName

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

            val track = initLocalAudioTrack(SAMPLE_RATE)
            localAudioTrack = track
            if (_isLocalPlaybackEnabled.value) {
                try { track?.play() } catch (_: Exception) {}
            }

            try {
                record.startRecording()
                _isCapturing.value = true
                onLog("TX-Audio", "Audio live from $deviceName (48kHz Stereo)", false)

                // 20ms chunks (48000 samples/sec * 4 bytes/sample / 50 = 3840 bytes)
                val chunkSize = (SAMPLE_RATE * 4) / 50
                val pcmBuffer = ByteArray(chunkSize)

                while (isRunning.get() && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readBytes = record.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readBytes > 0) {
                        // 1. Play on phone speakers/earpiece if enabled
                        if (_isLocalPlaybackEnabled.value) {
                            localAudioTrack?.write(pcmBuffer, 0, readBytes)
                        }

                        // 2. Transmit to network receiver
                        val copy = pcmBuffer.copyOf(readBytes)
                        onAudioChunkCaptured?.invoke(copy)
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

                try {
                    track?.stop()
                    track?.release()
                } catch (_: Exception) {}
                localAudioTrack = null
            }
        }
    }

    private fun initAudioRecord(sampleRate: Int, bufferSize: Int, usbDevice: AudioDeviceInfo?): AudioRecord? {
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
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, CHANNEL_CONFIG_OUT, AUDIO_FORMAT) * 2
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
                            .setSampleRate(sampleRate)
                            .setChannelMask(CHANNEL_CONFIG_OUT)
                            .build()
                    )
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

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            localAudioTrack?.stop()
            localAudioTrack?.release()
        } catch (_: Exception) {}
        localAudioTrack = null
    }

    fun release() {
        stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null && deviceCallback != null) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            deviceCallback = null
        }
    }
}
