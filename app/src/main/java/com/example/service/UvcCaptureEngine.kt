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
import java.util.concurrent.ArrayBlockingQueue
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
        id = "mjpeg_720p_60",
        label = "720p (60 FPS)",
        width = 1280,
        height = 720,
        description = "High Motion 60 FPS · PS3 & Console Capture",
        targetFps = 60
    )

    private var currentSurface: Any? = null
    private var activeDevice: UsbDevice? = null

    private var frameCountSinceLastMeasure = 0
    private var lastFpsMeasureTime = 0L
    private var sessionStartTime = 0L
    private var totalFramesCounter = 0L
    private var lastFrameDeliveredNs = 0L

    // Reusable pooled buffers for UVC frames (at least 4, using 6)
    private val uvcBufferPool = ArrayBlockingQueue<ByteArray>(6).apply {
        repeat(6) { add(ByteArray(512 * 1024)) }
    }

    private fun acquireUvcBuffer(needed: Int): ByteArray {
        val b = uvcBufferPool.poll() ?: ByteArray(maxOf(needed, 512 * 1024))
        return if (b.size < needed) ByteArray(maxOf(needed, b.size * 2)) else b
    }

    fun releaseUvcBuffer(buffer: ByteArray) {
        if (uvcBufferPool.remainingCapacity() > 0) {
            uvcBufferPool.offer(buffer)
        }
    }

    var onRawFrameCaptured: ((ByteArray) -> Unit)? = null
    var onRawFrameCapturedWithRelease: ((ByteArray, Int, () -> Unit) -> Unit)? = null

    init {
        UVCUtils.init(context)
        cameraHelper = CameraHelper()
        setupCallback()
    }

    private fun isMjpeg(size: Size): Boolean {
        // In UVC specifications and the UVCCamera engine:
        // Descriptor subType for MJPEG frame is 7 (DEFAULT_PREVIEW_FRAME_FORMAT) or 6 (VS_FORMAT_MJPEG)
        return size.type == UVCCamera.DEFAULT_PREVIEW_FRAME_FORMAT ||
               size.type == 7 ||
               size.type == 6 ||
               size.type == 1 ||
               (size.type != 4 && size.type != 5 && size.type != 0)
    }

    fun setStreamPreset(preset: StreamPreset) {
        selectedPreset = preset
        onLog("UVC", "Selected resolution: ${preset.width}x${preset.height} @ ${preset.targetFps}fps (Strictly MJPEG)", false)

        if (cameraHelper.isCameraOpened) {
            applyMjpegResolution(preset.width, preset.height, preset.targetFps)
        }
    }

    private fun findBestMjpegSize(targetW: Int, targetH: Int, targetFps: Int): Size {
        val supported = cameraHelper.supportedSizeList ?: emptyList()
        val mjpegSizes = supported.filter { isMjpeg(it) }

        // 1. Look for exact resolution matching targetFps in fpsList or fps
        val exact = mjpegSizes.firstOrNull { s ->
            s.width == targetW && s.height == targetH &&
            (s.fps == targetFps || (s.fpsList != null && s.fpsList.contains(targetFps)))
        }
        if (exact != null) {
            return Size(exact.type, exact.width, exact.height, targetFps, exact.fpsList)
        }

        // 2. Resolution match with closest or highest fps
        val resMatch = mjpegSizes.filter { it.width == targetW && it.height == targetH }
            .maxByOrNull { it.fps }
        if (resMatch != null) {
            val chosenFps = if (resMatch.fpsList?.contains(targetFps) == true) targetFps else resMatch.fps
            return Size(resMatch.type, resMatch.width, resMatch.height, chosenFps, resMatch.fpsList)
        }

        // 3. Fallbacks
        return mjpegSizes.firstOrNull { it.width == targetW }
            ?: mjpegSizes.firstOrNull()
            ?: supported.firstOrNull { it.width == targetW && it.height == targetH }
            ?: Size(7, targetW, targetH, targetFps, null)
    }

    private fun applyMjpegResolution(targetW: Int, targetH: Int, targetFps: Int = selectedPreset.targetFps) {
        val targetSize = findBestMjpegSize(targetW, targetH, targetFps)
        val supportsTarget = targetSize.fps >= targetFps || (targetSize.fpsList != null && targetSize.fpsList.contains(targetFps))

        val notice = if (targetFps == 60 && !supportsTarget) {
            val msg = "Card max ${targetSize.fps} fps (60 fps not supported by card)"
            onLog("UVC", "Notice: Card does not support 60 FPS at ${targetSize.width}x${targetSize.height} (max: ${targetSize.fps} fps). Negotiating ${targetSize.fps} fps.", true)
            msg
        } else {
            onLog("UVC", "Setting hardware MJPEG mode: ${targetSize.width}x${targetSize.height} (requested: ${targetFps}fps, negotiated: ${targetSize.fps}fps)...", false)
            null
        }

        try {
            cameraHelper.setPreviewSize(targetSize)
            val active = cameraHelper.previewSize ?: targetSize
            _telemetry.value = _telemetry.value.copy(
                resolutionWidth = active.width,
                resolutionHeight = active.height,
                requestedFps = targetFps,
                negotiatedFps = active.fps,
                fpsNotice = notice,
                bufferFormat = "MJPEG"
            )
            onLog("UVC", "Hardware stream negotiated: ${active.width}x${active.height} @ ${active.fps}fps MJPEG", false)
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

                val initialTarget = findBestMjpegSize(selectedPreset.width, selectedPreset.height, selectedPreset.targetFps)
                onLog("UVC", "Initiating UVC camera with MJPEG ${initialTarget.width}x${initialTarget.height} @ ${initialTarget.fps}fps...", false)

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

                val allSizes = cameraHelper.supportedSizeList ?: emptyList()
                val mjpegModes = allSizes.filter { isMjpeg(it) }

                onLog("UVC", "Card exposes ${mjpegModes.size} hardware MJPEG modes:", false)
                for (m in mjpegModes) {
                    val fpsOptions = m.fpsList?.joinToString("/") ?: "${m.fps}"
                    onLog("UVC", " • ${m.width}x${m.height} MJPEG (${fpsOptions} fps)", false)
                }

                // Force the chosen MJPEG resolution and requested frame interval
                applyMjpegResolution(selectedPreset.width, selectedPreset.height, selectedPreset.targetFps)

                currentSurface?.let {
                    try {
                        cameraHelper.addSurface(it, false)
                    } catch (_: Exception) {}
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

                        // Pool the per-frame ByteArray copy (released after socket write)
                        if (sizeBytes > 0) {
                            val targetFps = selectedPreset.targetFps
                            val minIntervalNs = if (targetFps > 0) (1_000_000_000L / targetFps) - 2_500_000L else 0L
                            val nowNs = System.nanoTime()
                            if (minIntervalNs <= 0L || (nowNs - lastFrameDeliveredNs) >= minIntervalNs) {
                                lastFrameDeliveredNs = nowNs
                                val poolBuffer = acquireUvcBuffer(sizeBytes)
                                val pos = byteBuffer.position()
                                byteBuffer.get(poolBuffer, 0, sizeBytes)
                                byteBuffer.position(pos)

                                var isReleased = false
                                val releaseAction = {
                                    if (!isReleased) {
                                        isReleased = true
                                        releaseUvcBuffer(poolBuffer)
                                    }
                                }

                                if (onRawFrameCapturedWithRelease != null) {
                                    onRawFrameCapturedWithRelease?.invoke(poolBuffer, sizeBytes, releaseAction)
                                } else if (onRawFrameCaptured != null) {
                                    val copy = ByteArray(sizeBytes)
                                    System.arraycopy(poolBuffer, 0, copy, 0, sizeBytes)
                                    releaseAction()
                                    onRawFrameCaptured?.invoke(copy)
                                } else {
                                    releaseAction()
                                }
                            }
                        }
                    }, UVCCamera.PIXEL_FORMAT_RAW)
                } catch (e: Exception) {
                    onLog("UVC", "Frame callback note: ${e.message}", false)
                }

                // If no surface attached, startPreview runs with frame callback alone (saves native RGBX decode)
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
                onLog("UVC", "USB device closed", false)
                _telemetry.value = _telemetry.value.copy(isStreaming = false)
            }

            override fun onDetach(device: UsbDevice) {
                onLog("UVC", "USB device detached", false)
                _isDeviceConnected.value = false
                _telemetry.value = _telemetry.value.copy(isStreaming = false)
            }

            override fun onCancel(device: UsbDevice) {
                onLog("UVC", "USB permission cancelled for ${device.productName ?: device.deviceName}", false)
            }

            override fun onError(device: UsbDevice, e: CameraException) {
                onLog("UVC", "CameraException: ${e.message}", true)
                _telemetry.value = _telemetry.value.copy(
                    errorMessage = "UVC error: ${e.message}"
                )
            }
        })
    }

    fun setPreviewSurface(surface: Any?) {
        val old = currentSurface
        if (old != null && old != surface) {
            try {
                cameraHelper.removeSurface(old)
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
        onLog("UVC", "Connecting to ${target.productName ?: target.deviceName} (${selectedPreset.width}x${selectedPreset.height} @ ${selectedPreset.targetFps}fps MJPEG)...", false)

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
        uvcBufferPool.clear()
    }
}
