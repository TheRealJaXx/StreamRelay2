package com.example.ui

import android.app.Application
import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.CaptureTelemetry
import com.example.model.DiscoveredTransmitter
import com.example.model.LogEntry
import com.example.model.StreamPreset
import com.example.model.UsbDeviceInfo
import com.example.relay.AudioCaptureEngine
import com.example.relay.JpegUtils
import com.example.relay.AudioLatencyProfile
import com.example.relay.NetworkUtils
import com.example.relay.OffscreenDrainer
import com.example.relay.OmtDiscoveryManager
import com.example.relay.RelayClient
import com.example.relay.RelayServer
import com.example.relay.RelayServerService
import com.example.relay.RxConnectionState
import com.example.relay.RxStats
import com.example.relay.TxRelayStats
import com.example.relay.UdpRelayServer
import com.example.relay.UdpRelayClient
import com.example.relay.HardwareH264Encoder
import com.example.relay.HardwareH264Decoder
import com.example.service.UvcCaptureEngine
import com.example.service.UsbMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.graphics.Bitmap
import com.example.relay.LatencyTestEngine

enum class AppMode {
    UNSELECTED,
    TX,         // Transmitter (USB Capture)
    RX,         // Receiver
    TEST_TX,    // Latency Test Pattern (Hardware-free)
    ROUND_TRIP  // Side-by-side Outgoing vs Incoming Loopback
}

enum class RoundTripSource {
    TEST_PATTERN,
    HDMI_CAPTURE
}

enum class TransportProtocol(val label: String) {
    TCP("TCP (Dual Socket)"),
    UDP("UDP (Sequenced Datagrams)")
}

enum class VideoCodec(val label: String) {
    MJPEG("MJPEG (Raw Frames)"),
    H264("H.264 (Hardware Encoded)")
}

class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("stream_relay_prefs", Context.MODE_PRIVATE)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // Mode selection - asked fresh on every launch
    private val _currentMode = MutableStateFlow(AppMode.UNSELECTED)
    val currentMode: StateFlow<AppMode> = _currentMode.asStateFlow()

    // Round Trip Source toggle
    private val _roundTripSource = MutableStateFlow(RoundTripSource.TEST_PATTERN)
    val roundTripSource: StateFlow<RoundTripSource> = _roundTripSource.asStateFlow()

    // Selected USB device for capture
    private val _selectedUsbDeviceId = MutableStateFlow<String?>(null)
    val selectedUsbDeviceId: StateFlow<String?> = _selectedUsbDeviceId.asStateFlow()

    // TX network settings
    private val _localIp = MutableStateFlow(NetworkUtils.getLocalIpAddress())
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    private val _txPort = MutableStateFlow(prefs.getInt("pref_tx_port", 4120))
    val txPort: StateFlow<Int> = _txPort.asStateFlow()

    // RX network settings
    private val _rxTargetIp = MutableStateFlow(prefs.getString("pref_rx_ip", "192.168.1.100") ?: "192.168.1.100")
    val rxTargetIp: StateFlow<String> = _rxTargetIp.asStateFlow()

    private val _rxTargetPort = MutableStateFlow(prefs.getInt("pref_rx_port", 4120))
    val rxTargetPort: StateFlow<Int> = _rxTargetPort.asStateFlow()

    // Hardware capture services (TX) - 720p30 & 720p60 selectable
    val availablePresets: List<StreamPreset> = listOf(
        StreamPreset("mjpeg_720p_30", "720p (30 FPS)", 1280, 720, "Smooth 30 FPS · Lowest Bandwidth & Zero Lag", targetFps = 30),
        StreamPreset("mjpeg_720p_60", "720p (60 FPS)", 1280, 720, "High Motion 60 FPS · PS3 & Console Capture", targetFps = 60),
        StreamPreset("mjpeg_1080p_30", "1080p (30 FPS)", 1920, 1080, "Full HD · 30 FPS", targetFps = 30),
        StreamPreset("mjpeg_1080p_60", "1080p (60 FPS)", 1920, 1080, "Full HD · 60 FPS", targetFps = 60),
        StreamPreset("mjpeg_480p_30", "480p (30 FPS)", 640, 480, "Ultra Fast · Lowest Bandwidth", targetFps = 30)
    )

    private val _selectedPreset = MutableStateFlow(availablePresets[0])
    val selectedPreset: StateFlow<StreamPreset> = _selectedPreset.asStateFlow()

    private val usbMonitor = UsbMonitor(application) { tag, message, isError ->
        addLog(tag, message, isError)
    }

    private val uvcCaptureEngine = UvcCaptureEngine(application) { tag, message, isError ->
        addLog(tag, message, isError)
    }

    // Unified TCP Relay Server (TX) - Sends Video and Audio multiplexed over separate ports
    val relayServer = RelayServer(application) { tag, message, isError ->
        addLog(tag, message, isError)
    }

    // Unified TCP Relay Client (RX) - Receives Video and Audio simultaneously
    val relayClient = RelayClient { tag, message, isError ->
        addLog(tag, message, isError)
    }

    // Sequenced UDP Transport (TX & RX)
    val udpRelayServer = UdpRelayServer { tag, message, isError ->
        addLog(tag, message, isError)
    }

    val udpRelayClient = UdpRelayClient { tag, message, isError ->
        addLog(tag, message, isError)
    }

    // Hardware H.264 Codec instances
    private var h264Encoder: HardwareH264Encoder? = null
    private var h264Decoder: HardwareH264Decoder? = null

    // Transport & Codec Preferences
    private val _transportProtocol = MutableStateFlow(
        if (prefs.getString("pref_transport", "UDP") == "TCP") TransportProtocol.TCP else TransportProtocol.UDP
    )
    val transportProtocol: StateFlow<TransportProtocol> = _transportProtocol.asStateFlow()

    private val _videoCodec = MutableStateFlow(
        if (prefs.getString("pref_codec", "MJPEG") == "H264") VideoCodec.H264 else VideoCodec.MJPEG
    )
    val videoCodec: StateFlow<VideoCodec> = _videoCodec.asStateFlow()

    val isBackgroundServiceArmed: StateFlow<Boolean> = RelayServerService.isServiceActive

    fun setTransportProtocol(proto: TransportProtocol) {
        _transportProtocol.value = proto
        prefs.edit().putString("pref_transport", proto.name).apply()
        addLog("System", "Transport changed to: ${proto.label}", false)
    }

    fun setVideoCodec(codec: VideoCodec) {
        _videoCodec.value = codec
        prefs.edit().putString("pref_codec", codec.name).apply()
        addLog("System", "Video codec changed to: ${codec.label}", false)
    }

    // Capture Card Audio Engine (TX) - Captures USB Audio & plays through phone speaker
    val audioCaptureEngine = AudioCaptureEngine(application) { tag, message, isError ->
        addLog(tag, message, isError)
    }

    val isAudioStreaming: StateFlow<Boolean> = audioCaptureEngine.isCapturing
    val isLocalAudioEnabled: StateFlow<Boolean> = audioCaptureEngine.isLocalPlaybackEnabled
    val isUsbAudioConnected: StateFlow<Boolean> = audioCaptureEngine.isUsbAudioConnected
    val activeAudioDeviceName: StateFlow<String> = audioCaptureEngine.activeDeviceName
    val isAudioPlaying: StateFlow<Boolean> = relayClient.isAudioPlaying
    val isRxMuted: StateFlow<Boolean> = relayClient.isMuted
    val audioLatencyProfile: StateFlow<AudioLatencyProfile> = relayClient.audioLatencyProfile
    val audioDelayMs: StateFlow<Int> = relayClient.audioDelayMs

    fun setAudioLatencyProfile(profile: AudioLatencyProfile) {
        relayClient.setAudioLatencyProfile(profile)
    }

    fun setAudioDelayMs(delayMs: Int) {
        relayClient.setAudioDelayMs(delayMs)
    }

    val connectedUsbDevices: StateFlow<List<UsbDeviceInfo>> = usbMonitor.connectedDevices
    val telemetry: StateFlow<CaptureTelemetry> = uvcCaptureEngine.telemetry
    val txConnectedClients: StateFlow<Int> = relayServer.connectedClients
    val isTxServerActive: StateFlow<Boolean> = relayServer.isServerActive
    val txStats: StateFlow<TxRelayStats> = relayServer.txStats

    val rxConnectionState: StateFlow<RxConnectionState> = relayClient.connectionState
    val rxBitmap = relayClient.currentBitmap
    val rxStats: StateFlow<RxStats> = relayClient.stats

    // Open Media Transport (OMT) Zero-Config LAN Discovery Engine
    val omtDiscoveryManager = OmtDiscoveryManager(application) { tag, message, isError ->
        addLog(tag, message, isError)
    }

    val discoveredTransmitters: StateFlow<List<DiscoveredTransmitter>> = omtDiscoveryManager.discoveredTransmitters
    val isOmtDiscovering: StateFlow<Boolean> = omtDiscoveryManager.isDiscovering
    val publishedStreamName: String
        get() = omtDiscoveryManager.getPublishedStreamName()

    val txConnectedClientAddress: StateFlow<String?> = relayServer.connectedClientAddress

    fun connectToTransmitter(transmitter: DiscoveredTransmitter) {
        setRxTarget(transmitter.host, transmitter.port)
        connectRx()
    }

    // Hardware-Free Latency Test Pattern Engine (TX)
    val latencyTestEngine = LatencyTestEngine(
        onFrameGenerated = { jpegBytes ->
            relayServer.recordFrameCaptured()
            if (_transportProtocol.value == TransportProtocol.UDP) {
                udpRelayServer.submitVideoFrame(jpegBytes, jpegBytes.size, codecId = 0, isKeyframe = true)
            } else {
                relayServer.submitFrame(jpegBytes)
            }
        },
        onAudioChunkGenerated = { pcmBytes ->
            if (_transportProtocol.value == TransportProtocol.UDP) {
                udpRelayServer.submitAudio(pcmBytes)
            } else {
                relayServer.submitAudio(pcmBytes)
            }
        }
    )

    val testPatternBitmap: StateFlow<Bitmap?> = latencyTestEngine.currentBitmap
    val testFramesGenerated: StateFlow<Long> = latencyTestEngine.framesGenerated

    private val _testFps = MutableStateFlow(prefs.getInt("pref_test_fps", 60))
    val testFps: StateFlow<Int> = _testFps.asStateFlow()

    private val _testAudioTick = MutableStateFlow(prefs.getBoolean("pref_test_audio_tick", true))
    val testAudioTick: StateFlow<Boolean> = _testAudioTick.asStateFlow()

    // Local Video Preview toggle on Transmitter (TX)
    private val _isLocalPreviewEnabled = MutableStateFlow(false)
    val isLocalPreviewEnabled: StateFlow<Boolean> = _isLocalPreviewEnabled.asStateFlow()

    // Send 30 fps toggle on Transmitter (TX)
    private val _isSend30FpsEnabled = MutableStateFlow(prefs.getBoolean("pref_tx_send_30_fps", false))
    val isSend30FpsEnabled: StateFlow<Boolean> = _isSend30FpsEnabled.asStateFlow()

    fun setSend30FpsEnabled(enabled: Boolean) {
        _isSend30FpsEnabled.value = enabled
        prefs.edit().putBoolean("pref_tx_send_30_fps", enabled).apply()
        relayServer.send30FpsOnly = enabled
        udpRelayServer.send30FpsOnly = enabled
    }

    private var currentLocalHolder: SurfaceHolder? = null
    private var offscreenDrainer: OffscreenDrainer? = null

    init {
        relayServer.targetFps = _selectedPreset.value.targetFps
        relayServer.send30FpsOnly = _isSend30FpsEnabled.value
        udpRelayServer.targetFps = _selectedPreset.value.targetFps
        udpRelayServer.send30FpsOnly = _isSend30FpsEnabled.value
        addLog("System", "Stream Relay initialized (TCP & UDP Multi-Transport)", false)

        // Wire camera frames directly from UVC engine into TCP/UDP server using pooled buffers
        uvcCaptureEngine.onRawFrameCapturedWithRelease = { rawBytes, sizeBytes, releaseAction ->
            relayServer.recordFrameCaptured()

            val isJpeg = sizeBytes >= 2 &&
                (rawBytes[0].toInt() and 0xFF) == 0xFF && (rawBytes[1].toInt() and 0xFF) == 0xD8

            val isUdp = _transportProtocol.value == TransportProtocol.UDP
            val isH264 = _videoCodec.value == VideoCodec.H264

            if (isH264 && !isJpeg) {
                if (h264Encoder == null) {
                    val telem = telemetry.value
                    val w = if (telem.resolutionWidth > 0) telem.resolutionWidth else _selectedPreset.value.width
                    val h = if (telem.resolutionHeight > 0) telem.resolutionHeight else _selectedPreset.value.height
                    h264Encoder = HardwareH264Encoder(
                        width = w,
                        height = h,
                        targetFps = _selectedPreset.value.targetFps
                    ) { nalData, isKeyframe ->
                        if (isUdp) {
                            udpRelayServer.submitVideoFrame(nalData, nalData.size, codecId = 1, isKeyframe = isKeyframe)
                        } else {
                            relayServer.submitFrame(nalData, nalData.size)
                        }
                    }.also { it.start() }
                }
                h264Encoder?.encodeYuv(rawBytes.copyOf(sizeBytes))
                releaseAction()
            } else {
                if (isJpeg) {
                    if (isUdp) {
                        udpRelayServer.submitVideoFrame(rawBytes, sizeBytes, codecId = 0, isKeyframe = true, onWritten = releaseAction)
                    } else {
                        relayServer.submitFrame(rawBytes, sizeBytes, releaseAction)
                    }
                } else {
                    val telem = telemetry.value
                    val w = if (telem.resolutionWidth > 0) telem.resolutionWidth else 1280
                    val h = if (telem.resolutionHeight > 0) telem.resolutionHeight else 720
                    val compressed = JpegUtils.compressYuvToJpeg(rawBytes.copyOf(sizeBytes), w, h, quality = 80)
                    releaseAction()
                    if (compressed != null) {
                        if (isUdp) {
                            udpRelayServer.submitVideoFrame(compressed, compressed.size, codecId = 0, isKeyframe = true)
                        } else {
                            relayServer.submitFrame(compressed, compressed.size)
                        }
                    }
                }
            }
        }

        // Wire raw PCM audio chunks directly into Audio TCP/UDP server
        audioCaptureEngine.onAudioChunkCaptured = { pcmBytes ->
            if (_transportProtocol.value == TransportProtocol.UDP) {
                udpRelayServer.submitAudio(pcmBytes)
            } else {
                relayServer.submitAudio(pcmBytes)
            }
        }

        // Auto-start capture when device connects/permission granted in TX or ROUND_TRIP mode
        viewModelScope.launch {
            usbMonitor.connectedDevices.collect { devices ->
                val isTx = _currentMode.value == AppMode.TX
                val isRoundTripCapture = _currentMode.value == AppMode.ROUND_TRIP && _roundTripSource.value == RoundTripSource.HDMI_CAPTURE
                if ((isTx || isRoundTripCapture) && !telemetry.value.isStreaming) {
                    val active = getActiveUsbDeviceInfo()
                    if (active != null && active.hasPermission) {
                        val raw = usbMonitor.getRawDevice(active.vendorId, active.productId)
                        if (raw != null) {
                            startCapture(raw)
                        } else {
                            startCapture()
                        }
                    } else {
                        val readyDevice = devices.firstOrNull { it.hasPermission && it.isUvcVideo }
                            ?: devices.firstOrNull { it.hasPermission }
                        if (readyDevice != null) {
                            startCapture()
                        }
                    }
                }
            }
        }
    }

    private fun ensureOffscreenSurface(
        width: Int = _selectedPreset.value.width,
        height: Int = _selectedPreset.value.height
    ): Surface {
        var drainer = offscreenDrainer
        if (drainer == null || drainer.width != width || drainer.height != height) {
            try { drainer?.release() } catch (_: Exception) {}
            drainer = OffscreenDrainer(width, height)
            offscreenDrainer = drainer
        }
        return drainer.surface
    }

    private fun releaseOffscreenSurface() {
        try {
            offscreenDrainer?.release()
        } catch (_: Exception) {}
        offscreenDrainer = null
    }

    fun setLocalPreviewEnabled(enabled: Boolean) {
        _isLocalPreviewEnabled.value = enabled
        if (enabled) {
            val holder = currentLocalHolder
            if (holder != null) {
                releaseOffscreenSurface()
                uvcCaptureEngine.setPreviewSurface(holder)
            }
        } else {
            val offscreen = ensureOffscreenSurface()
            uvcCaptureEngine.setPreviewSurface(offscreen)
        }
    }

    fun setLocalPreviewHolder(holder: SurfaceHolder?) {
        currentLocalHolder = holder
        if (holder != null && _isLocalPreviewEnabled.value) {
            releaseOffscreenSurface()
            uvcCaptureEngine.setPreviewSurface(holder)
        } else if (holder == null && _isLocalPreviewEnabled.value) {
            val offscreen = ensureOffscreenSurface()
            uvcCaptureEngine.setPreviewSurface(offscreen)
        }
    }

    fun setLocalAudioEnabled(enabled: Boolean) {
        audioCaptureEngine.setLocalPlaybackEnabled(enabled)
    }

    fun setRxMuted(muted: Boolean) {
        relayClient.setMuted(muted)
    }

    fun attachRxSurface(holder: SurfaceHolder) {
        relayClient.attachSurface(holder)
        if (_videoCodec.value == VideoCodec.H264) {
            try {
                h264Decoder?.release()
                val telem = telemetry.value
                val w = if (telem.resolutionWidth > 0) telem.resolutionWidth else 1280
                val h = if (telem.resolutionHeight > 0) telem.resolutionHeight else 720
                h264Decoder = HardwareH264Decoder(w, h, holder.surface).also { it.start() }
            } catch (_: Exception) {}
        }
    }

    fun detachRxSurface(holder: SurfaceHolder) {
        relayClient.detachSurface(holder)
        try {
            h264Decoder?.release()
        } catch (_: Exception) {}
        h264Decoder = null
    }

    fun onAudioPermissionGranted() {
        if (_currentMode.value == AppMode.TX) {
            audioCaptureEngine.start()
        }
    }

    fun setTestFps(fps: Int) {
        _testFps.value = fps
        prefs.edit().putInt("pref_test_fps", fps).apply()
        latencyTestEngine.setTargetFps(fps)
    }

    fun setTestAudioTick(enabled: Boolean) {
        _testAudioTick.value = enabled
        prefs.edit().putBoolean("pref_test_audio_tick", enabled).apply()
        latencyTestEngine.setAudioTick(enabled)
    }

    fun selectMode(mode: AppMode) {
        _currentMode.value = mode

        when (mode) {
            AppMode.TX -> {
                latencyTestEngine.stop()
                relayClient.disconnect()
                refreshLocalIp()
                usbMonitor.start()
                val targetSurface = if (_isLocalPreviewEnabled.value && currentLocalHolder != null) {
                    currentLocalHolder!!
                } else {
                    ensureOffscreenSurface()
                }
                uvcCaptureEngine.setPreviewSurface(targetSurface)
                uvcCaptureEngine.setStreamPreset(_selectedPreset.value)
                startTxRelayServer(_txPort.value)
                audioCaptureEngine.start()
                startCapture()

                // OMT Discovery: Advertise this device on the local network
                omtDiscoveryManager.stopScanning()
                omtDiscoveryManager.startBroadcasting(_txPort.value, _selectedPreset.value.targetFps, hasAudio = true)
            }
            AppMode.TEST_TX -> {
                audioCaptureEngine.stop()
                _isLocalPreviewEnabled.value = false
                currentLocalHolder = null
                relayClient.disconnect()
                uvcCaptureEngine.stopCapture()
                uvcCaptureEngine.setPreviewSurface(null)
                releaseOffscreenSurface()
                usbMonitor.stop()

                refreshLocalIp()
                startTxRelayServer(_txPort.value)
                latencyTestEngine.start(_testFps.value, _testAudioTick.value)

                // OMT Discovery: Advertise latency test stream
                omtDiscoveryManager.stopScanning()
                omtDiscoveryManager.startBroadcasting(_txPort.value, _testFps.value, hasAudio = _testAudioTick.value)
            }
            AppMode.RX -> {
                latencyTestEngine.stop()
                audioCaptureEngine.stop()
                _isLocalPreviewEnabled.value = false
                currentLocalHolder = null
                stopTxRelayServer()
                uvcCaptureEngine.stopCapture()
                uvcCaptureEngine.setPreviewSurface(null)
                releaseOffscreenSurface()
                usbMonitor.stop()
                relayClient.setMuted(false)

                // OMT Discovery: Scan for active transmitters on the network
                omtDiscoveryManager.stopBroadcasting()
                omtDiscoveryManager.startScanning()
            }
            AppMode.ROUND_TRIP -> {
                omtDiscoveryManager.stopBroadcasting()
                omtDiscoveryManager.stopScanning()
                audioCaptureEngine.stop()
                _isLocalPreviewEnabled.value = true
                refreshLocalIp()
                startTxRelayServer(_txPort.value)
                if (_roundTripSource.value == RoundTripSource.TEST_PATTERN) {
                    uvcCaptureEngine.stopCapture()
                    releaseOffscreenSurface()
                    latencyTestEngine.start(_testFps.value, audioTick = false)
                } else {
                    latencyTestEngine.stop()
                    usbMonitor.start()
                    val targetSurface = if (currentLocalHolder != null) currentLocalHolder!! else ensureOffscreenSurface()
                    uvcCaptureEngine.setPreviewSurface(targetSurface)
                    uvcCaptureEngine.setStreamPreset(_selectedPreset.value)
                    startCapture()
                }
                // Connect RX to local loopback server with AUDIO STRICTLY MUTED to prevent howl round
                relayClient.setMuted(true)
                relayClient.connect("127.0.0.1", _txPort.value)
            }
            AppMode.UNSELECTED -> {
                omtDiscoveryManager.stopBroadcasting()
                omtDiscoveryManager.stopScanning()
                latencyTestEngine.stop()
                audioCaptureEngine.stop()
                _isLocalPreviewEnabled.value = false
                currentLocalHolder = null
                stopTxRelayServer()
                relayClient.disconnect()
                uvcCaptureEngine.stopCapture()
                uvcCaptureEngine.setPreviewSurface(null)
                releaseOffscreenSurface()
                usbMonitor.stop()
            }
        }
    }

    fun setRoundTripSource(source: RoundTripSource) {
        _roundTripSource.value = source
        if (_currentMode.value == AppMode.ROUND_TRIP) {
            if (source == RoundTripSource.TEST_PATTERN) {
                uvcCaptureEngine.stopCapture()
                releaseOffscreenSurface()
                latencyTestEngine.start(_testFps.value, audioTick = false)
            } else {
                latencyTestEngine.stop()
                usbMonitor.start()
                val targetSurface = if (currentLocalHolder != null) currentLocalHolder!! else ensureOffscreenSurface()
                uvcCaptureEngine.setPreviewSurface(targetSurface)
                uvcCaptureEngine.setStreamPreset(_selectedPreset.value)
                startCapture()
            }
        }
    }

    fun setFpsOption(fps: Int) {
        setTestFps(fps)
        val currentPreset = _selectedPreset.value
        val newPreset = availablePresets.firstOrNull { it.targetFps == fps && it.height == currentPreset.height }
            ?: availablePresets.firstOrNull { it.targetFps == fps }
        if (newPreset != null) {
            selectPreset(newPreset)
        }
    }

    fun refreshLocalIp() {
        _localIp.value = NetworkUtils.getLocalIpAddress()
    }

    fun setTxPort(port: Int) {
        _txPort.value = port
        prefs.edit().putInt("pref_tx_port", port).apply()
        if (_currentMode.value == AppMode.TX && isTxServerActive.value) {
            startTxRelayServer(port)
        }
    }

    fun setRxTarget(ip: String, port: Int) {
        _rxTargetIp.value = ip.trim()
        _rxTargetPort.value = port
        prefs.edit()
            .putString("pref_rx_ip", ip.trim())
            .putInt("pref_rx_port", port)
            .apply()
    }

    fun startTxRelayServer(port: Int = _txPort.value) {
        refreshLocalIp()
        if (_transportProtocol.value == TransportProtocol.UDP) {
            udpRelayServer.start(port)
        } else {
            relayServer.start(port)
        }
        if (_currentMode.value == AppMode.TX) {
            audioCaptureEngine.start()
        }
        RelayServerService.start(getApplication())
    }

    fun stopTxRelayServer() {
        relayServer.stop()
        udpRelayServer.stop()
        audioCaptureEngine.stop()
        try {
            h264Encoder?.release()
        } catch (_: Exception) {}
        h264Encoder = null
        RelayServerService.stop(getApplication())
    }

    fun connectRx() {
        relayClient.setMuted(false)
        if (_transportProtocol.value == TransportProtocol.UDP) {
            relayClient.startAudioPlaybackOnly()
            udpRelayClient.onAudioChunkReceived = { pcm ->
                relayClient.pushExternalAudioChunk(pcm)
            }
            udpRelayClient.onVideoFrameReceived = { frameData, codecId, _ ->
                if (codecId.toInt() == 1) {
                    h264Decoder?.decodeNal(frameData)
                } else {
                    relayClient.pushExternalVideoFrame(frameData)
                }
            }
            udpRelayClient.connect(_rxTargetIp.value, _rxTargetPort.value)
        } else {
            relayClient.connect(_rxTargetIp.value, _rxTargetPort.value)
        }
    }

    fun disconnectRx() {
        relayClient.disconnect()
        udpRelayClient.disconnect()
        try {
            h264Decoder?.release()
        } catch (_: Exception) {}
        h264Decoder = null
    }

    fun selectPreset(preset: StreamPreset) {
        _selectedPreset.value = preset
        relayServer.targetFps = preset.targetFps
        uvcCaptureEngine.setStreamPreset(preset)
    }

    fun getActiveUsbDeviceInfo(): UsbDeviceInfo? {
        val selectedId = _selectedUsbDeviceId.value
        val list = connectedUsbDevices.value
        return if (selectedId != null) {
            list.firstOrNull { it.deviceName == selectedId } ?: list.firstOrNull { it.isUvcVideo } ?: list.firstOrNull()
        } else {
            list.firstOrNull { it.isUvcVideo } ?: list.firstOrNull()
        }
    }

    fun selectUsbDevice(deviceId: String?) {
        _selectedUsbDeviceId.value = deviceId
        val isTx = _currentMode.value == AppMode.TX
        val isRoundTripCapture = _currentMode.value == AppMode.ROUND_TRIP && _roundTripSource.value == RoundTripSource.HDMI_CAPTURE
        if (isTx || isRoundTripCapture) {
            val active = getActiveUsbDeviceInfo()
            if (active != null && active.hasPermission) {
                val raw = usbMonitor.getRawDevice(active.vendorId, active.productId)
                if (raw != null) {
                    uvcCaptureEngine.stopCapture()
                    uvcCaptureEngine.startCapture(raw)
                }
            }
        }
    }

    fun startCapture(specificDevice: android.hardware.usb.UsbDevice? = null) {
        val activeInfo = getActiveUsbDeviceInfo()
        val uvcDevice = specificDevice ?: run {
            if (activeInfo != null) {
                usbMonitor.getRawDevice(activeInfo.vendorId, activeInfo.productId)
            } else {
                usbMonitor.getFirstUvcDevice()
            }
        }
        if (uvcDevice != null) {
            val matchingInfo = connectedUsbDevices.value.firstOrNull {
                it.vendorId == uvcDevice.vendorId && it.productId == uvcDevice.productId
            } ?: activeInfo
            if (matchingInfo?.hasPermission == true) {
                uvcCaptureEngine.startCapture(uvcDevice)
            } else {
                val name = matchingInfo?.displayName ?: uvcDevice.productName ?: "USB Video"
                addLog("TX", "Capture card detected ($name). Waiting for USB permission...", false)
            }
        } else {
            addLog("TX", "No USB capture card found. Waiting for UVC device connection...", false)
        }
    }

    fun stopCapture() {
        uvcCaptureEngine.stopCapture()
    }

    fun requestUsbPermission(vendorId: Int, productId: Int) {
        usbMonitor.requestUsbPermission(vendorId, productId)
    }

    fun refreshAll() {
        refreshLocalIp()
        usbMonitor.scanDevices()
    }

    fun setPreviewSurface(surface: Any?) {
        uvcCaptureEngine.setPreviewSurface(surface)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun addLog(tag: String, message: String, isError: Boolean = false) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            tag = tag,
            message = message,
            isError = isError
        )
        val current = _logs.value.toMutableList()
        current.add(0, entry)
        if (current.size > 200) {
            current.removeAt(current.lastIndex)
        }
        _logs.value = current
    }

    override fun onCleared() {
        super.onCleared()
        latencyTestEngine.stop()
        _isLocalPreviewEnabled.value = false
        currentLocalHolder = null
        stopTxRelayServer()
        relayClient.disconnect()
        usbMonitor.stop()
        uvcCaptureEngine.setPreviewSurface(null)
        releaseOffscreenSurface()
        uvcCaptureEngine.release()
        audioCaptureEngine.release()
        omtDiscoveryManager.release()
    }
}
