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

    private var selectedPreset: StreamPreset = StreamPreset(
        id = "mjpeg_720p",
        label = "720p MJPEG (60 FPS)",
        width = 1280,
        height = 720,
        isMjpeg = true,
        description = "Recommended for PS3 · 60fps · Low Latency"
    )

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
        selectedPreset = preset
        val isMjpeg = preset.isMjpeg
        val formatName = if (isMjpeg) "MJPEG" else "YUV"

        onLog("UVC", "Selecting preset: ${preset.width}x${preset.height} $formatName...", false)

        if (cameraHelper.isCameraOpened) {
            applySizeToCamera(preset)
        }
    }

    private fun applySizeToCamera(preset: StreamPreset) {
        val supported = cameraHelper.supportedSizeList ?: emptyList()
        val targetType = if (preset.isMjpeg) UVCCamera.FRAME_FORMAT_MJPEG else UVCCamera.FRAME_FORMAT_YUYV
        val formatLabel = if (preset.isMjpeg) "MJPEG" else "YUV"

        val matched: Size? = supported.firstOrNull {
            it.type == targetType && it.width == preset.width && it.height == preset.height
        } ?: supported.firstOrNull {
            it.type == targetType && it.width == preset.width
        } ?: supported.firstOrNull {
            it.type == targetType
        } ?: supported.firstOrNull()

        if (matched != null) {
            val actualFmt = if (matched.type == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUV"
            onLog("UVC", "Switching resolution to: ${matched.width}x${matched.height} $actualFmt (${matched.fps}fps)...", false)
            try {
                cameraHelper.setPreviewSize(matched)
                val curSize = cameraHelper.previewSize ?: matched
                val curFmt = if (curSize.type == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUV"

                _telemetry.value = _telemetry.value.copy(
                    resolutionWidth = curSize.width,
                    resolutionHeight = curSize.height,
                    bufferFormat = curFmt
                )
                onLog("UVC", "Resolution successfully applied: ${curSize.width}x${curSize.height} $curFmt", false)
            } catch (e: Exception) {
                onLog("UVC", "Failed to switch resolution: ${e.message}", true)
            }
        } else {
            onLog("UVC", "No matching hardware size found for ${preset.width}x${preset.height} $formatLabel", true)
        }
    }

    private fun setupCallback() {
        cameraHelper.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                onLog("UVC", "USB device attached: ${device.productName ?: device.deviceName}", false)
                _isDeviceConnected.value = true
            }

            override fun onDeviceOpen(device: UsbDevice, isUsingCache: Boolean) {
                onLog("UVC", "USB device connected. Opening hardware camera...", false)
                activeDevice = device

                try {
                    cameraHelper.openCamera()
                } catch (e: Exception) {
                    onLog("UVC", "Error opening UVC camera: ${e.message}", true)
                }
            }

            override fun onCameraOpen(device: UsbDevice) {
                activeDevice = device
                onLog("UVC", "Camera opened. Reading hardware capabilities...", false)

                // Log all capabilities supported by this capture card
                val supported = cameraHelper.supportedSizeList ?: emptyList()
                onLog("UVC", "Capture card supports ${supported.size} video modes:", false)
                for (s in supported) {
                    val f = if (s.type == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUV"
                    onLog("UVC", " • ${s.width}x${s.height} $f (${s.fps}fps)", false)
                }

                // Immediately apply user-selected preset (e.g. 720p or 480p MJPEG)
                applySizeToCamera(selectedPreset)

                currentSurface?.let {
                    cameraHelper.addSurface(it, false)
                }

                val activeSize = cameraHelper.previewSize
                val width = activeSize?.width ?: selectedPreset.width
                val height = activeSize?.height ?: selectedPreset.height
                val formatString = if (activeSize?.type == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUV"

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
                            val currentSize = cameraHelper.previewSize
                            val currentFmt = if (currentSize?.type == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUV"

                            _telemetry.value = _telemetry.value.copy(
                                isStreaming = true,
                                fps = (currentFps * 10).roundToInt() / 10f,
                                totalFrames = totalFramesCounter,
                                resolutionWidth = currentSize?.width ?: width,
                                resolutionHeight = currentSize?.height ?: height,
                                bufferFormat = currentFmt,
                                lastFrameTimeMs = now,
                                lastFrameSizeBytes = sizeBytes,
                                streamDurationSeconds = durationSec
                            )

                            if (totalFramesCounter % 120L == 0L) {
                                onLog("UVC", "Frames: $totalFramesCounter | FPS: ${"%.1f".format(currentFps)} | Mode: ${currentSize?.width}x${currentSize?.height} $currentFmt", false)
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
                            val currentSize = cameraHelper.previewSize
                            val currentFmt = if (currentSize?.type == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUV"
                            onLog("UVC", "SUCCESS: Ingesting frames at ${currentSize?.width}x${currentSize?.height} $currentFmt!", false)
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
        val formatLabel = if (selectedPreset.isMjpeg) "MJPEG" else "YUV"
        onLog("UVC", "Opening ${target.productName ?: target.deviceName} for ${selectedPreset.width}x${selectedPreset.height} $formatLabel...", false)

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
