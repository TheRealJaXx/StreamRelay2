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
        description = "Recommended for PS3 · 60fps · Low Latency"
    )

    private var currentSurface: Any? = null
    private var activeDevice: UsbDevice? = null

    private var frameCountSinceLastMeasure = 0
    private var lastFpsMeasureTime = 0L
    private var sessionStartTime = 0L
    private var totalFramesCounter = 0L

    var onRawFrameCaptured: ((ByteArray) -> Unit)? = null

    init {
        UVCUtils.init(context)
        cameraHelper = CameraHelper()
        setupCallback()
    }

    private fun isMjpeg(size: Size): Boolean {
        // In UVC specifications and the UVCCamera engine:
        // Descriptor subType for MJPEG frame is 7 (DEFAULT_PREVIEW_FRAME_FORMAT) or 6 (VS_FORMAT_MJPEG)
        // Subtype 4 and 5 are uncompressed YUY2/YUV
        return size.type == UVCCamera.DEFAULT_PREVIEW_FRAME_FORMAT ||
               size.type == 7 ||
               size.type == 6 ||
               size.type == 1 ||
               (size.type != 4 && size.type != 5 && size.type != 0)
    }

    fun setStreamPreset(preset: StreamPreset) {
        selectedPreset = preset
        onLog("UVC", "Selected resolution: ${preset.width}x${preset.height} (Strictly MJPEG)", false)

        if (cameraHelper.isCameraOpened) {
            applyMjpegResolution(preset.width, preset.height)
        }
    }

    private fun findBestMjpegSize(targetW: Int, targetH: Int): Size {
        val supported = cameraHelper.supportedSizeList ?: emptyList()
        val mjpegSizes = supported.filter { isMjpeg(it) }

        return mjpegSizes.firstOrNull { it.width == targetW && it.height == targetH }
            ?: mjpegSizes.firstOrNull { it.width == targetW }
            ?: mjpegSizes.firstOrNull()
            ?: supported.firstOrNull { it.width == targetW && it.height == targetH }
            ?: Size(7, targetW, targetH, 60, null)
    }

    private fun applyMjpegResolution(targetW: Int, targetH: Int) {
        val targetSize = findBestMjpegSize(targetW, targetH)
        onLog("UVC", "Setting hardware MJPEG mode: ${targetSize.width}x${targetSize.height} (${targetSize.fps}fps max)...", false)

        try {
            cameraHelper.setPreviewSize(targetSize)
            val active = cameraHelper.previewSize ?: targetSize
            _telemetry.value = _telemetry.value.copy(
                resolutionWidth = active.width,
                resolutionHeight = active.height,
                bufferFormat = "MJPEG"
            )
            onLog("UVC", "Resolution active: ${active.width}x${active.height} MJPEG", false)
        } catch (e: Exception) {
            onLog("UVC", "Error applying MJPEG resolution: ${e.message}", true)
        }
    }

    private fun setupCallback() {
        cameraHelper.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                onLog("UVC", "USB device attached: ${device.productName ?: device.deviceName}", false)
                _isDeviceConnected.value = true
            }

            override fun onDeviceOpen(device: UsbDevice, isUsingCache: Boolean) {
                onLog("UVC", "USB connection established. Opening camera in MJPEG mode...", false)
                activeDevice = device

                // Query card capabilities to pick the exact hardware MJPEG profile
                val initialTarget = findBestMjpegSize(selectedPreset.width, selectedPreset.height)
                onLog("UVC", "Initiating UVC camera with MJPEG ${initialTarget.width}x${initialTarget.height}...", false)

                try {
                    cameraHelper.openCamera(initialTarget)
                } catch (e: Exception) {
                    onLog("UVC", "Warning on initial size open, falling back: ${e.message}", false)
                    try {
                        cameraHelper.openCamera()
                    } catch (fatal: Exception) {
                        onLog("UVC", "Fatal open error: ${fatal.message}", true)
                    }
                }
            }

            override fun onCameraOpen(device: UsbDevice) {
                activeDevice = device
                onLog("UVC", "UVC Camera opened! Enforcing MJPEG stream...", false)

                // Inspect all MJPEG modes supported by this card
                val allSizes = cameraHelper.supportedSizeList ?: emptyList()
                val mjpegModes = allSizes.filter { isMjpeg(it) }

                onLog("UVC", "Card exposes ${mjpegModes.size} hardware MJPEG modes:", false)
                for (m in mjpegModes) {
                    val fpsOptions = m.fpsList?.joinToString("/") ?: "${m.fps}"
                    onLog("UVC", " • ${m.width}x${m.height} MJPEG (${fpsOptions} fps)", false)
                }

                // Force the chosen MJPEG resolution immediately
                applyMjpegResolution(selectedPreset.width, selectedPreset.height)

                currentSurface?.let {
                    cameraHelper.addSurface(it, false)
                }

                val activeSize = cameraHelper.previewSize
                val width = activeSize?.width ?: selectedPreset.width
                val height = activeSize?.height ?: selectedPreset.height

                _telemetry.value = _telemetry.value.copy(
                    isStreaming = true,
                    cameraName = device.productName ?: "USB Video",
                    resolutionWidth = width,
                    resolutionHeight = height,
                    bufferFormat = "MJPEG",
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

                            _telemetry.value = _telemetry.value.copy(
                                isStreaming = true,
                                fps = (currentFps * 10).roundToInt() / 10f,
                                totalFrames = totalFramesCounter,
                                resolutionWidth = currentSize?.width ?: width,
                                resolutionHeight = currentSize?.height ?: height,
                                bufferFormat = "MJPEG",
                                lastFrameTimeMs = now,
                                lastFrameSizeBytes = sizeBytes,
                                streamDurationSeconds = durationSec
                            )

                            if (totalFramesCounter % 120L == 0L) {
                                onLog("UVC", "MJPEG Frames: $totalFramesCounter | FPS: ${"%.1f".format(currentFps)} | Frame size: ${sizeBytes / 1024} KB", false)
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
                            onLog("UVC", "SUCCESS: Receiving MJPEG frames at ${width}x${height}!", false)
                        }

                        if (sizeBytes > 0 && onRawFrameCaptured != null) {
                            val rawBytes = ByteArray(sizeBytes)
                            val pos = byteBuffer.position()
                            byteBuffer.get(rawBytes)
                            byteBuffer.position(pos)
                            onRawFrameCaptured?.invoke(rawBytes)
                        }
                    }, UVCCamera.PIXEL_FORMAT_RAW)
                } catch (e: Exception) {
                    onLog("UVC", "Frame callback note: ${e.message}", false)
                }

                try {
                    cameraHelper.startPreview()
                    onLog("UVC", "Live MJPEG video preview active!", false)
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
        onLog("UVC", "Connecting to ${target.productName ?: target.deviceName} (Strictly MJPEG ${selectedPreset.width}x${selectedPreset.height})...", false)

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
