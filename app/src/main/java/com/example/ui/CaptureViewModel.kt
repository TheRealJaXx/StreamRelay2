package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.model.CaptureTelemetry
import com.example.model.LogEntry
import com.example.model.StreamPreset
import com.example.model.UsbDeviceInfo
import com.example.service.UvcCaptureEngine
import com.example.service.UsbMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    val availablePresets: List<StreamPreset> = listOf(
        StreamPreset("mjpeg_720p", "720p MJPEG (60 FPS)", 1280, 720, true, "Recommended for PS3 · 60fps · Low Latency"),
        StreamPreset("mjpeg_1080p", "1080p MJPEG (30-60 FPS)", 1920, 1080, true, "Full HD · 30-60fps"),
        StreamPreset("mjpeg_480p", "480p MJPEG (Ultra Fast)", 640, 480, true, "Maximum throughput · Low bandwidth"),
        StreamPreset("yuv_1080p", "1080p YUV (Slow 6 FPS)", 1920, 1080, false, "Raw Uncompressed · USB 2.0 bottlenecked (~6fps)")
    )

    private val _selectedPreset = MutableStateFlow(availablePresets[0])
    val selectedPreset: StateFlow<StreamPreset> = _selectedPreset.asStateFlow()

    private var activePreviewSurface: Any? = null

    private fun addLog(tag: String, message: String, isError: Boolean = false) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            tag = tag,
            message = message,
            isError = isError
        )
        val current = _logs.value.toMutableList()
        current.add(0, entry) // Newest on top
        if (current.size > 200) {
            current.removeAt(current.lastIndex)
        }
        _logs.value = current
    }

    private val usbMonitor = UsbMonitor(application) { tag, message, isError ->
        addLog(tag, message, isError)
    }

    private val uvcCaptureEngine = UvcCaptureEngine(application) { tag, message, isError ->
        addLog(tag, message, isError)
    }

    val connectedUsbDevices: StateFlow<List<UsbDeviceInfo>> = usbMonitor.connectedDevices
    val telemetry: StateFlow<CaptureTelemetry> = uvcCaptureEngine.telemetry

    init {
        addLog("System", "Stream Relay initialized (Default: 720p MJPEG 60fps)", false)
        uvcCaptureEngine.setStreamPreset(_selectedPreset.value)
        usbMonitor.start()
    }

    fun selectPreset(preset: StreamPreset) {
        _selectedPreset.value = preset
        uvcCaptureEngine.setStreamPreset(preset)
    }

    fun setPreviewSurface(surface: Any?) {
        activePreviewSurface = surface
        uvcCaptureEngine.setPreviewSurface(surface)
    }

    fun startCapture() {
        val uvcDevice = usbMonitor.getFirstUvcDevice()
        if (uvcDevice == null) {
            addLog("Capture", "No USB capture card found. Please plug in your UVC device.", true)
            return
        }

        uvcCaptureEngine.setPreviewSurface(activePreviewSurface)
        uvcCaptureEngine.startCapture(uvcDevice)
    }

    fun stopCapture() {
        uvcCaptureEngine.stopCapture()
    }

    fun refreshAll() {
        addLog("System", "Refreshing USB devices...", false)
        usbMonitor.scanDevices()
    }

    fun requestUsbPermission(vendorId: Int, productId: Int) {
        usbMonitor.requestUsbPermission(vendorId, productId)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        usbMonitor.stop()
        uvcCaptureEngine.release()
    }
}
