package com.example.ui

import android.app.Application
import android.content.Context
import android.view.SurfaceHolder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.CaptureTelemetry
import com.example.model.LogEntry
import com.example.model.StreamPreset
import com.example.model.UsbDeviceInfo
import com.example.relay.AudioCaptureEngine
import com.example.service.UvcCaptureEngine
import com.example.service.UsbMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CaptureViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("stream_relay_prefs", Context.MODE_PRIVATE)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _selectedPreset = MutableStateFlow(
        StreamPreset(
            id = "mjpeg_720p",
            label = "720p",
            width = 1280,
            height = 720,
            description = "Default 720p MJPEG monitor preset"
        )
    )
    val selectedPreset: StateFlow<StreamPreset> = _selectedPreset.asStateFlow()

    val availablePresets: List<StreamPreset> = listOf(
        StreamPreset("mjpeg_480p", "480p", 640, 480, "Lower resolution for maximum smoothness"),
        StreamPreset("mjpeg_720p", "720p", 1280, 720, "Default lowest-latency monitor preset"),
        StreamPreset("mjpeg_1080p", "1080p", 1920, 1080, "Full HD if the capture card supports it")
    )

    private val usbMonitor = UsbMonitor(application) { tag, message, isError ->
        addLog(tag, message, isError)
    }

    private val uvcCaptureEngine = UvcCaptureEngine(application) { tag, message, isError ->
        addLog(tag, message, isError)
    }

    val audioCaptureEngine = AudioCaptureEngine(application) { tag, message, isError ->
        addLog(tag, message, isError)
    }

    val connectedUsbDevices: StateFlow<List<UsbDeviceInfo>> = usbMonitor.connectedDevices
    val telemetry: StateFlow<CaptureTelemetry> = uvcCaptureEngine.telemetry
    val audioOutputDeviceType: StateFlow<String> = audioCaptureEngine.outputDeviceType
    val audioVolume: StateFlow<Float> = audioCaptureEngine.volume
    val audioMuted: StateFlow<Boolean> = audioCaptureEngine.isMuted

    init {
        addLog("System", "USB capture monitor initialized", false)
        usbMonitor.start()
        usbMonitor.scanDevices()

        viewModelScope.launch {
            usbMonitor.connectedDevices.collect { devices ->
                val ready = devices.firstOrNull { it.hasPermission && it.isUvcVideo }
                    ?: devices.firstOrNull { it.hasPermission }
                if (ready != null && !telemetry.value.isStreaming) {
                    startCapture()
                } else if (ready == null && telemetry.value.isStreaming) {
                    stopCapture()
                }
            }
        }
    }

    fun setPreviewSurface(surface: Any?) {
        if (surface is SurfaceHolder) {
            uvcCaptureEngine.setPreviewSurface(surface)
        } else {
            uvcCaptureEngine.setPreviewSurface(surface)
        }
    }

    fun selectPreset(width: Int) {
        val preset = availablePresets.firstOrNull { it.width == width } ?: availablePresets[1]
        _selectedPreset.value = preset
        uvcCaptureEngine.setStreamPreset(preset)
    }

    fun setVolume(value: Float) {
        audioCaptureEngine.setVolume(value)
    }

    fun setMuted(muted: Boolean) {
        audioCaptureEngine.setMuted(muted)
    }

    fun requestUsbPermission(vendorId: Int, productId: Int) {
        usbMonitor.requestUsbPermission(vendorId, productId)
    }

    fun refreshDevices() {
        usbMonitor.scanDevices()
    }

    fun startCapture() {
        val device = usbMonitor.getFirstUvcDevice()
        if (device == null) {
            addLog("USB", "No capture card detected. Connect a USB UVC device.", false)
            return
        }

        val info = connectedUsbDevices.value.firstOrNull { it.deviceName == device.deviceName }
            ?: connectedUsbDevices.value.firstOrNull { it.isUvcVideo }
            ?: connectedUsbDevices.value.firstOrNull()

        if (info?.hasPermission != true) {
            addLog("USB", "Capture card detected, waiting for USB permission.", false)
            return
        }

        uvcCaptureEngine.setStreamPreset(_selectedPreset.value)
        uvcCaptureEngine.startCapture(device)
        audioCaptureEngine.start()
    }

    fun stopCapture() {
        uvcCaptureEngine.stopCapture()
        audioCaptureEngine.stop()
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
        stopCapture()
        usbMonitor.stop()
        uvcCaptureEngine.setPreviewSurface(null)
        uvcCaptureEngine.release()
        audioCaptureEngine.release()
    }
}

