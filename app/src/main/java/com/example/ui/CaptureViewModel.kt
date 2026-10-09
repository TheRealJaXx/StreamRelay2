package com.example.ui

import android.app.Application
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import com.example.model.CameraDeviceInfo
import com.example.model.CaptureTelemetry
import com.example.model.LogEntry
import com.example.model.UsbDeviceInfo
import com.example.service.CaptureEngine
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

    private val _hasCameraPermission = MutableStateFlow(false)
    val hasCameraPermission: StateFlow<Boolean> = _hasCameraPermission.asStateFlow()

    private val _selectedCameraId = MutableStateFlow<String?>(null)
    val selectedCameraId: StateFlow<String?> = _selectedCameraId.asStateFlow()

    private var activePreviewSurface: Surface? = null

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

    private val captureEngine = CaptureEngine(application) { tag, message, isError ->
        addLog(tag, message, isError)
    }

    val connectedUsbDevices: StateFlow<List<UsbDeviceInfo>> = usbMonitor.connectedDevices
    val availableCameras: StateFlow<List<CameraDeviceInfo>> = captureEngine.availableCameras
    val telemetry: StateFlow<CaptureTelemetry> = captureEngine.telemetry

    init {
        addLog("System", "Stream Relay initialized for Milestone 1", false)
        usbMonitor.start()
        captureEngine.start()
    }

    fun updateCameraPermission(granted: Boolean) {
        _hasCameraPermission.value = granted
        if (granted) {
            addLog("Auth", "Camera permission granted", false)
            captureEngine.refreshCameraList()
            autoSelectBestCamera()
        } else {
            addLog("Auth", "Camera permission not granted", true)
        }
    }

    fun autoSelectBestCamera() {
        val cameras = availableCameras.value
        if (cameras.isEmpty()) return

        // Prefer external UVC capture card if present
        val external = cameras.firstOrNull { it.isExternal }
        if (external != null) {
            _selectedCameraId.value = external.id
            addLog("Select", "Auto-selected External UVC Capture Card (ID ${external.id})", false)
        } else if (_selectedCameraId.value == null) {
            _selectedCameraId.value = cameras.first().id
        }
    }

    fun selectCamera(id: String) {
        if (_selectedCameraId.value != id) {
            _selectedCameraId.value = id
            val cam = availableCameras.value.firstOrNull { it.id == id }
            addLog("Select", "Selected ${cam?.displayName ?: "Camera $id"}", false)
            if (telemetry.value.isStreaming) {
                // Restart capture on new camera
                startCapture()
            }
        }
    }

    fun setPreviewSurface(surface: Surface?) {
        activePreviewSurface = surface
        captureEngine.setPreviewSurface(surface)
    }

    fun startCapture() {
        val id = _selectedCameraId.value ?: availableCameras.value.firstOrNull { it.isExternal }?.id
            ?: availableCameras.value.firstOrNull()?.id
        if (id == null) {
            addLog("Capture", "Cannot start capture: No camera selected or available", true)
            return
        }

        if (!_hasCameraPermission.value) {
            addLog("Capture", "Cannot start capture: Camera permission required", true)
            return
        }

        captureEngine.openCamera(id, activePreviewSurface)
    }

    fun stopCapture() {
        captureEngine.closeCamera()
        addLog("Capture", "Capture stopped by user", false)
    }

    fun refreshAll() {
        addLog("System", "Refreshing USB and Camera devices...", false)
        usbMonitor.scanDevices()
        captureEngine.refreshCameraList()
        autoSelectBestCamera()
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
        captureEngine.stop()
    }
}
