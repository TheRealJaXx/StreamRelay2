package com.example.service

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.SystemClock
import com.example.model.CaptureTelemetry
import com.example.model.StreamPreset
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
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

    // Default to 720p MJPEG for high framerate (60fps) and lowest latency
    private var targetWidth = 1280
    private var targetHeight = 720
    private var targetIsMjpeg = true

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

    fun setStreamPreset(preset: StreamPreset) {
        val changed = targetWidth != preset.width || targetHeight != preset.height || targetIsMjpeg != preset.isMjpeg
        targetWidth = preset.width
        targetHeight = preset.height
        targetIsMjpeg = preset.isMjpeg

        val fmtName = if (preset.isMjpeg) "MJPEG (High FPS)" else "YUV (Uncompressed)"
        onLog("UVC", "Selected stream preset: ${preset.width}x${preset.height} $fmtName", false)

        if (changed && cameraHelper.isCameraOpened) {
            onLog("UVC", "Re-opening camera with new stream settings...", false)
            stopCapture()
            activeDevice?.let { dev ->
                startCapture(dev)
            }
        }
    }

    private fun setupCallback() {
        cameraHelper.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                onLog("UVC", "USB device attached: ${device.productName ?: device.deviceName}", false)
                _isDeviceConnected.value = true
            }

            override fun onDeviceOpen(device: UsbDevice, isUsingCache: Boolean) {
                onLog("UVC", "USB connection established. Configuring capture mode...", false)
                activeDevice = device

                val targetType = if (targetIsMjpeg) UVCCamera.FRAME_FORMAT_MJPEG else UVCCamera.FRAME_FORMAT_YUYV
                val formatLabel = if (targetIsMjpeg) "MJPEG" else "YUV"

                val supportedSizes = cameraHelper.supportedSizeList ?: emptyList()
                if (supportedSizes.isNotEmpty()) {
                    val mjpegCount = supportedSizes.count { it.type == UVCCamera.FRAME_FORMAT_MJPEG }
                    val yuvCount = supportedSizes.count { it.type == UVCCamera.FRAME_FORMAT_YUYV }
                    onLog("UVC", "Card capabilities: $mjpegCount MJPEG mode(s), $yuvCount YUV mode(s)", false)
                }

                // Find matching format and resolution
                val chosenSize: Size = supportedSizes.firstOrNull {
                    it.type == targetType && it.width == targetWidth && it.height == targetHeight
                } ?: supportedSizes.firstOrNull {
                    it.type == targetType && it.width == targetWidth
                } ?: supportedSizes.firstOrNull {
                    it.type == targetType
                } ?: supportedSizes.firstOrNull()
                ?: Size(targetType, targetWidth, targetHeight, 30, null)

                val chosenFormatLabel = if (chosenSize.type == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUV"
                onLog("UVC", "Opening UVC camera at ${chosenSize.width}x${chosenSize.height} ($chosenFormatLabel)...", false)

                try {
                    cameraHelper.openCamera(chosenSize)
                } catch (e: Exception) {
                    onLog("UVC", "Failed to open UVC camera with preset, falling back: ${e.message}", true)
                    try {
                        cameraHelper.openCamera()
                    } catch (fallbackEx: Exception) {
                        onLog("UVC", "Fatal open error: ${fallbackEx.message}", true)
                    }
                }
            }

            override fun onCameraOpen(device: UsbDevice) {
                activeDevice = device

                currentSurface?.let {
                    cameraHelper.addSurface(it, false)
                }

                val previewSize = cameraHelper.previewSize
                val width = previewSize?.width ?: targetWidth
                val height = previewSize?.height ?: targetHeight
                val isActuallyMjpeg = previewSize?.type == UVCCamera.FRAME_FORMAT_MJPEG || (previewSize == null && targetIsMjpeg)
                val formatString = if (isActuallyMjpeg) "MJPEG" else "YUV"

                onLog("UVC", "UVC Camera opened! Active format: ${width}x${height} $formatString", false)

                _telemetry.value = _telemetry.value.copy(
                    isStreaming = true,
                    cameraName = device.productName ?: "USB Video",
                    resolutionWidth = width,
                    resolutionHeight = height,
                    bufferFormat = formatString,
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
                                onLog("UVC", "Frames: $totalFramesCounter | FPS: ${"%.1f".format(currentFps)} | Format: $formatString", false)
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
                            onLog("UVC", "SUCCESS: Receiving frames in $formatString mode at ${width}x${height}!", false)
                        }
                    }, UVCCamera.PIXEL_FORMAT_YUV)
                } catch (e: Exception) {
                    onLog("UVC", "Frame callback note: ${e.message}", false)
                }

                try {
                    cameraHelper.startPreview()
                    onLog("UVC", "Live preview active!", false)
                } catch (e: Exception) {
                    onLog("UVC", "Failed to start preview: ${e.message}", true)
                }
            }

            override fun onCameraClose(device: UsbDevice) {
                onLog("UVC", "UVC Camera closed", false)
                _telemetry.value = _telemetry.value.copy(isStreaming = false)
            }

            override fun onDeviceClose(device: UsbDevice) {
                onLog("UVC", "USB Device closed", false)
            }

            override fun onDetach(device: UsbDevice) {
                onLog("UVC", "USB Device detached", false)
                _isDeviceConnected.value = false
                _telemetry.value = _telemetry.value.copy(isStreaming = false)
            }

            override fun onCancel(device: UsbDevice) {
                onLog("UVC", "USB connection canceled", false)
            }

            override fun onError(device: UsbDevice, e: CameraException) {
                onLog("UVC", "Hardware Error: ${e.message}", true)
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

        activeDevice = target
        val formatLabel = if (targetIsMjpeg) "MJPEG" else "YUV"
        onLog("UVC", "Connecting to ${target.productName ?: target.deviceName} (Target: ${targetWidth}x${targetHeight} $formatLabel)...", false)

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
        onLog("UVC", "UVC capture stopped", false)
    }

    fun release() {
        try {
            cameraHelper.release()
        } catch (_: Exception) {}
    }
}
