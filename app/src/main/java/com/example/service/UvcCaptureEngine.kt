package com.example.service

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.SystemClock
import com.example.model.CaptureTelemetry
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.UVCCamera
import com.serenegiant.utils.UVCUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

class UvcCaptureEngine(
    private val context: Context,
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    private val cameraHelper: CameraHelper

    private val _telemetry = MutableStateFlow(CaptureTelemetry())
    val telemetry: StateFlow<CaptureTelemetry> = _telemetry.asStateFlow()

    private val _isDeviceConnected = MutableStateFlow(false)
    val isDeviceConnected: StateFlow<Boolean> = _isDeviceConnected.asStateFlow()

    private var currentSurface: Any? = null
    private var activeDevice: UsbDevice? = null

    private var frameCountSinceLastMeasure = 0
    private var lastFpsMeasureTime = 0L
    private var totalFramesCounter = 0L
    private var sessionStartTime = 0L

    init {
        UVCUtils.init(context)
        cameraHelper = CameraHelper()
        setupCallback()
    }

    private fun setupCallback() {
        cameraHelper.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                onLog("UVC", "USB device attached: ${device.productName ?: device.deviceName}", false)
                _isDeviceConnected.value = true
            }

            override fun onDeviceOpen(device: UsbDevice, isUsingCache: Boolean) {
                onLog("UVC", "USB connection opened. Opening UVC video pipeline...", false)
                try {
                    cameraHelper.openCamera()
                } catch (e: Exception) {
                    onLog("UVC", "Failed to open UVC camera: ${e.message}", true)
                }
            }

            override fun onCameraOpen(device: UsbDevice) {
                onLog("UVC", "UVC capture card opened! Activating preview and frame capture...", false)
                activeDevice = device

                currentSurface?.let {
                    cameraHelper.addSurface(it, false)
                }

                val previewSize = cameraHelper.previewSize
                val width = previewSize?.width ?: 1920
                val height = previewSize?.height ?: 1080

                _telemetry.value = _telemetry.value.copy(
                    isStreaming = true,
                    cameraName = device.productName ?: "USB Video (${String.format("%04X:%04X", device.vendorId, device.productId)})",
                    resolutionWidth = width,
                    resolutionHeight = height,
                    bufferFormat = "UVC Video",
                    errorMessage = null
                )

                sessionStartTime = SystemClock.elapsedRealtime()
                lastFpsMeasureTime = sessionStartTime
                totalFramesCounter = 0L
                frameCountSinceLastMeasure = 0

                try {
                    cameraHelper.setFrameCallback({ byteBuffer ->
                        val now = SystemClock.elapsedRealtime()
                        totalFramesCounter++
                        frameCountSinceLastMeasure++
                        val sizeBytes = byteBuffer.remaining()

                        if (now - lastFpsMeasureTime >= 1000L) {
                            val elapsedSec = (now - lastFpsMeasureTime) / 1000f
                            val currentFps = frameCountSinceLastMeasure / elapsedSec
                            lastFpsMeasureTime = now
                            frameCountSinceLastMeasure = 0

                            val durationSec = (now - sessionStartTime) / 1000L

                            _telemetry.value = _telemetry.value.copy(
                                isStreaming = true,
                                fps = (currentFps * 10).roundToInt() / 10f,
                                totalFrames = totalFramesCounter,
                                lastFrameTimeMs = now,
                                lastFrameSizeBytes = sizeBytes,
                                streamDurationSeconds = durationSec
                            )

                            if (totalFramesCounter % 120L == 0L) {
                                onLog("UVC", "Frames: $totalFramesCounter | FPS: ${"%.1f".format(currentFps)} | Frame size: ${sizeBytes / 1024} KB", false)
                            }
                        } else {
                            _telemetry.value = _telemetry.value.copy(
                                isStreaming = true,
                                totalFrames = totalFramesCounter,
                                lastFrameTimeMs = now,
                                lastFrameSizeBytes = sizeBytes
                            )
                        }

                        if (totalFramesCounter == 1L) {
                            onLog("UVC", "SUCCESS: First video frame received from capture card! ($sizeBytes bytes)", false)
                        }
                    }, UVCCamera.PIXEL_FORMAT_YUV)
                } catch (e: Exception) {
                    onLog("UVC", "Warning setting frame callback: ${e.message}", false)
                }

                try {
                    cameraHelper.startPreview()
                    onLog("UVC", "UVC stream active! Waiting for incoming frames...", false)
                } catch (e: Exception) {
                    onLog("UVC", "Failed to start preview: ${e.message}", true)
                }
            }

            override fun onCameraClose(device: UsbDevice) {
                onLog("UVC", "UVC Camera closed", false)
                _telemetry.value = _telemetry.value.copy(isStreaming = false)
            }

            override fun onDeviceClose(device: UsbDevice) {
                onLog("UVC", "USB Device connection closed", false)
            }

            override fun onDetach(device: UsbDevice) {
                onLog("UVC", "USB Device detached", false)
                _isDeviceConnected.value = false
                _telemetry.value = _telemetry.value.copy(isStreaming = false)
            }

            override fun onCancel(device: UsbDevice) {
                onLog("UVC", "USB Permission or connection canceled by user", false)
            }

            override fun onError(device: UsbDevice, e: CameraException) {
                onLog("UVC", "UVC Hardware Error: ${e.message}", true)
                _telemetry.value = _telemetry.value.copy(
                    isStreaming = false,
                    errorMessage = e.message
                )
            }
        })
    }

    fun setPreviewSurface(surface: Any?) {
        if (currentSurface != null && surface == null) {
            try {
                cameraHelper.removeSurface(currentSurface)
            } catch (_: Exception) {}
        }
        this.currentSurface = surface
        if (surface != null && cameraHelper.isCameraOpened) {
            try {
                cameraHelper.addSurface(surface, false)
            } catch (e: Exception) {
                onLog("UVC", "Error attaching surface: ${e.message}", true)
            }
        }
    }

    fun startCapture(usbDevice: UsbDevice? = null) {
        val target = usbDevice ?: cameraHelper.deviceList?.firstOrNull()
        if (target == null) {
            onLog("UVC", "No USB UVC device detected to start capture", true)
            return
        }

        onLog("UVC", "Connecting to ${target.productName ?: target.deviceName} (${String.format("%04X:%04X", target.vendorId, target.productId)})...", false)
        try {
            cameraHelper.selectDevice(target)
        } catch (e: Exception) {
            onLog("UVC", "Failed to select USB device: ${e.message}", true)
        }
    }

    fun stopCapture() {
        try {
            cameraHelper.stopPreview()
        } catch (_: Exception) {}
        try {
            cameraHelper.closeCamera()
        } catch (_: Exception) {}
        _telemetry.value = _telemetry.value.copy(isStreaming = false)
        onLog("UVC", "UVC capture stopped by user", false)
    }

    fun release() {
        try {
            cameraHelper.release()
        } catch (_: Exception) {}
    }
}
