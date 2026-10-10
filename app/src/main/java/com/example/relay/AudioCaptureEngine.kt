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
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

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

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _outputDeviceType = MutableStateFlow("speaker")
    val outputDeviceType: StateFlow<String> = _outputDeviceType.asStateFlow()

    private val audioQueue = ArrayDeque<ByteArray>()
    private val queuedBytes = AtomicInteger(0)
    private val maxQueuedBytes = (SAMPLE_RATE * 2 * 2 * 0.04f).roundToInt()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var deviceCallback: AudioDeviceCallback? = null

    init {
        setupDeviceCallback()
    }

    private fun setupDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null) {
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    val outputType = detectOutputDeviceType()
                    _outputDeviceType.value = outputType
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    _outputDeviceType.value = detectOutputDeviceType()
                }
            }
            audioManager.registerAudioDeviceCallback(callback, null)
            deviceCallback = callback
        }
    }

    private fun detectOutputDeviceType(): String {
        if (audioManager == null) return "speaker"
        return try {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            if (outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) {
                "bluetooth"
            } else if (outputs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }) {
                "wired"
            } else {
                "speaker"
            }
        } catch (_: SecurityException) {
            "speaker"
        }
    }

    private fun isUsbAudioDevice(device: AudioDeviceInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.isSource && (
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                )
        } else {
            false
        }
    }

    private fun findUsbAudioDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null) {
            return try {
                audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { isUsbAudioDevice(it) }
            } catch (_: SecurityException) {
                null
            }
        }
        return null
    }

    fun setLocalPlaybackEnabled(enabled: Boolean) {
        setMuted(!enabled)
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        updateTrackVolume()
    }

    fun setVolume(value: Float) {
        _volume.value = value.coerceIn(0f, 1f)
        updateTrackVolume()
    }

    private fun updateTrackVolume() {
        val target = if (_isMuted.value) 0f else _volume.value.coerceIn(0f, 1f)
        val track = localAudioTrack
        if (track != null) {
            try {
                track.setStereoVolume(target, target)
            } catch (_: Exception) {
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
            onLog("USB-Audio", "Audio permission required for USB audio capture.", false)
            return
        }

        isRunning.set(true)
        startCaptureLoop()
    }

    private fun startCaptureLoop() {
        captureJob?.cancel()
        captureJob = audioScope.launch {
            val usbDevice = findUsbAudioDevice()
            val deviceName = usbDevice?.productName?.toString() ?: "USB capture audio"
            _outputDeviceType.value = detectOutputDeviceType()

            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
            val chunkBytes = (SAMPLE_RATE * 0.01f * 4f).roundToInt()
            val bufferSize = maxOf(minBufferSize, chunkBytes * 2)
            val record = initAudioRecord(SAMPLE_RATE, bufferSize, usbDevice)

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                onLog("USB-Audio", "Failed to initialize AudioRecord. Retrying...", true)
                _isCapturing.value = false
                isRunning.set(false)
                return@launch
            }

            audioRecord = record
            val track = initLocalAudioTrack(SAMPLE_RATE)
            localAudioTrack = track
            updateTrackVolume()
            if (track != null) {
                try {
                    track.play()
                } catch (_: Exception) {
                }
            }

            try {
                record.startRecording()
                _isCapturing.value = true
                onLog("USB-Audio", "Audio live from $deviceName (48kHz stereo)", false)

                val pcmBuffer = ByteArray(chunkBytes)
                while (isRunning.get() && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readBytes = record.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readBytes > 0) {
                        enqueueAudio(pcmBuffer.copyOf(readBytes))
                        drainAudioQueue()
                    }
                }
            } catch (e: Exception) {
                onLog("USB-Audio", "Audio capture loop ended: ${e.message}", false)
            } finally {
                _isCapturing.value = false
                try {
                    record.stop()
                    record.release()
                } catch (_: Exception) {
                }
                audioRecord = null
                try {
                    track?.stop()
                    track?.release()
                } catch (_: Exception) {
                }
                localAudioTrack = null
                queuedBytes.set(0)
                audioQueue.clear()
                isRunning.set(false)
            }
        }
    }

    private fun enqueueAudio(chunk: ByteArray) {
        synchronized(audioQueue) {
            while (queuedBytes.get() + chunk.size > maxQueuedBytes && audioQueue.isNotEmpty()) {
                val oldest = audioQueue.removeFirst()
                queuedBytes.addAndGet(-oldest.size)
            }
            audioQueue.addLast(chunk)
            queuedBytes.addAndGet(chunk.size)
        }
    }

    private fun drainAudioQueue() {
        while (true) {
            val next = synchronized(audioQueue) {
                if (audioQueue.isEmpty()) {
                    null
                } else {
                    val chunk = audioQueue.removeFirst()
                    queuedBytes.addAndGet(-chunk.size)
                    chunk
                }
            } ?: return

            val track = localAudioTrack ?: return
            val written = track.write(next, 0, next.size)
            if (written < 0) {
                return
            }
            if (written < next.size) {
                val remainder = next.copyOfRange(written, next.size)
                synchronized(audioQueue) {
                    audioQueue.addFirst(remainder)
                    queuedBytes.addAndGet(remainder.size)
                }
                return
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
                    val record = AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AUDIO_FORMAT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(CHANNEL_CONFIG_IN)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        if (usbDevice != null) {
                            record.setPreferredDevice(usbDevice)
                        }
                        return record
                    }
                    record.release()
                } else {
                    @Suppress("DEPRECATION")
                    val record = AudioRecord(source, sampleRate, CHANNEL_CONFIG_IN, AUDIO_FORMAT, bufferSize)
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        return record
                    }
                    record.release()
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun initLocalAudioTrack(sampleRate: Int): AudioTrack? {
        return try {
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
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
                    .setBufferSizeInBytes(minBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
            } else {
                null
            }

            if (builder != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                builder.build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    CHANNEL_CONFIG_OUT,
                    AUDIO_FORMAT,
                    minBufferSize,
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
        } catch (_: Exception) {
        }
        audioRecord = null

        try {
            localAudioTrack?.stop()
            localAudioTrack?.release()
        } catch (_: Exception) {
        }
        localAudioTrack = null
        audioQueue.clear()
        queuedBytes.set(0)
    }

    fun release() {
        stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null && deviceCallback != null) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            deviceCallback = null
        }
    }
}

