package com.example.ui

import android.app.Application
import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.CaptureTelemetry
import com.example.model.LogEntry
import com.example.model.StreamPreset
import com.example.model.UsbDeviceInfo
import com.example.relay.AudioCaptureEngine
import com.example.relay.JpegUtils
import com.example.relay.NetworkUtils
import com.example.relay.OffscreenDrainer
import com.example.relay.RelayClient
import com.example.relay.RelayServer
import com.example.relay.RelayServerService
import com.example.relay.RxConnectionState
import com.example.relay.RxStats
import com.example.service.UvcCaptureEngine
import com.example.service.UsbMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppMode {
    UNSELECTED,
    TX, // Transmitter
    RX  // Receiver
}

class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("stream_relay_prefs", Context.MODE_PRIVATE)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // Mode selection - asked fresh on every launch
    private val _currentMode = MutableStateFlow(AppMode.UNSELECTED)
    val currentMode: StateFlow<AppMode> = _currentMode.asStateFlow()

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

    // Hardware capture services (TX)
    val availablePresets: List<StreamPreset> = listOf(
        StreamPreset("mjpeg_720p", "720p (60 FPS)", 1280, 720, "Recommended for PS3 · 60 FPS · Lowest Latency"),
        StreamPreset("mjpeg_1080p", "1080p (30-60 FPS)", 1920, 1080, "Full HD · 30-60 FPS"),
        StreamPreset("mjpeg_480p", "480p (60 FPS)", 640, 480, "Ultra Fast · Lowest Bandwidth")
    )

    private val _selectedPreset = MutableStateFlow(availablePresets[0])
    val selectedPreset: StateFlow<StreamPreset> = _selectedPreset.asStateFlow()

    private val usbMonitor = UsbMonitor(application) { tag, message, isError ->
        addLog(tag, message, isError)
    }

    private val uvcCaptureEngine = UvcCaptureEngine(application) { tag, message, isError ->
        addLog(tag, message, isError)
    }

    // Unified TCP Relay Server (TX) - Sends Video and Audio multiplexed over single port
    val relayServer = RelayServer { tag, message, isError ->
        addLog(tag, message, isError)
    }

    // Unified TCP Relay Client (RX) - Receives Video and Audio simultaneously
    val relayClient = RelayClient { tag, message, isError ->
        addLog(tag, message, isError)
    }

    // Capture Card Audio Engine (TX) - Captures USB Audio & plays through phone speaker
    val audioCaptureEngine = AudioCaptureEngine(application) { tag, message, isError ->
        addLog(tag, message, isError)
    }

    val isAudioStreaming: StateFlow<Boolean> = audioCaptureEngine.isCapturing
    val isLocalAudioEnabled: StateFlow<Boolean> = audioCaptureEngine.isLocalPlaybackEnabled
    val isAudioPlaying: StateFlow<Boolean> = relayClient.isAudioPlaying
    val isRxMuted: StateFlow<Boolean> = relayClient.isMuted

    val connectedUsbDevices: StateFlow<List<UsbDeviceInfo>> = usbMonitor.connectedDevices
    val telemetry: StateFlow<CaptureTelemetry> = uvcCaptureEngine.telemetry
    val txConnectedClients: StateFlow<Int> = relayServer.connectedClients
    val isTxServerActive: StateFlow<Boolean> = relayServer.isServerActive

    val rxConnectionState: StateFlow<RxConnectionState> = relayClient.connectionState
    val rxBitmap = relayClient.currentBitmap
    val rxStats: StateFlow<RxStats> = relayClient.stats

    // Local Video Preview toggle on Transmitter (TX)
    private val _isLocalPreviewEnabled = MutableStateFlow(false)
    val isLocalPreviewEnabled: StateFlow<Boolean> = _isLocalPreviewEnabled.asStateFlow()

    private var currentLocalHolder: SurfaceHolder? = null
    private var offscreenDrainer: OffscreenDrainer? = null

    init {
        addLog("System", "Stream Relay initialized (Unified Video + Audio)", false)

        // Wire raw JPEG frames directly from UVC engine into unified TCP server
        uvcCaptureEngine.onRawFrameCaptured = { rawBytes ->
            val cleanJpeg = JpegUtils.extractCleanJpeg(rawBytes)
            relayServer.submitFrame(cleanJpeg)
        }

        // Wire raw PCM audio chunks directly from Audio engine into unified TCP server
        audioCaptureEngine.onAudioChunkCaptured = { pcmBytes ->
            relayServer.submitAudio(pcmBytes)
        }

        // Auto-start capture when device connects/permission granted in TX mode
        viewModelScope.launch {
            usbMonitor.connectedDevices.collect { devices ->
                if (_currentMode.value == AppMode.TX && !telemetry.value.isStreaming) {
                    val readyDevice = devices.firstOrNull { it.hasPermission && it.isUvcVideo }
                        ?: devices.firstOrNull { it.hasPermission }
                    if (readyDevice != null) {
                        startCapture()
                    }
                }
            }
        }
    }

    private fun ensureOffscreenSurface(): Surface {
        var drainer = offscreenDrainer
        if (drainer == null) {
            drainer = OffscreenDrainer(1280, 720)
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

    fun onAudioPermissionGranted() {
        if (_currentMode.value == AppMode.TX) {
            audioCaptureEngine.start()
        }
    }

    fun selectMode(mode: AppMode) {
        _currentMode.value = mode

        when (mode) {
            AppMode.TX -> {
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
                startCapture()
            }
            AppMode.RX -> {
                _isLocalPreviewEnabled.value = false
                currentLocalHolder = null
                stopTxRelayServer()
                uvcCaptureEngine.stopCapture()
                uvcCaptureEngine.setPreviewSurface(null)
                releaseOffscreenSurface()
                usbMonitor.stop()
            }
            AppMode.UNSELECTED -> {
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
        relayServer.start(port)
        audioCaptureEngine.start()
        RelayServerService.start(getApplication())
    }

    fun stopTxRelayServer() {
        relayServer.stop()
        audioCaptureEngine.stop()
        RelayServerService.stop(getApplication())
    }

    fun connectRx() {
        relayClient.connect(_rxTargetIp.value, _rxTargetPort.value)
    }

    fun disconnectRx() {
        relayClient.disconnect()
    }

    fun selectPreset(preset: StreamPreset) {
        _selectedPreset.value = preset
        uvcCaptureEngine.setStreamPreset(preset)
    }

    fun startCapture() {
        val uvcInfo = connectedUsbDevices.value.firstOrNull { it.isUvcVideo } ?: connectedUsbDevices.value.firstOrNull()
        val uvcDevice = usbMonitor.getFirstUvcDevice()
        if (uvcDevice != null) {
            if (uvcInfo?.hasPermission == true) {
                uvcCaptureEngine.startCapture(uvcDevice)
            } else {
                val name = uvcInfo?.displayName ?: uvcDevice.productName ?: "USB Video"
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
        _isLocalPreviewEnabled.value = false
        currentLocalHolder = null
        stopTxRelayServer()
        relayClient.disconnect()
        usbMonitor.stop()
        uvcCaptureEngine.setPreviewSurface(null)
        releaseOffscreenSurface()
        uvcCaptureEngine.release()
        audioCaptureEngine.release()
    }
}
