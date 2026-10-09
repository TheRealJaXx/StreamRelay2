package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.example.model.CameraDeviceInfo
import com.example.model.CaptureTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

class CaptureEngine(
    private val context: Context,
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val _availableCameras = MutableStateFlow<List<CameraDeviceInfo>>(emptyList())
    val availableCameras: StateFlow<List<CameraDeviceInfo>> = _availableCameras.asStateFlow()

    private val _telemetry = MutableStateFlow(CaptureTelemetry())
    val telemetry: StateFlow<CaptureTelemetry> = _telemetry.asStateFlow()

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    // Frame rate measurement
    private var frameCountSinceLastMeasure = 0
    private var lastFpsMeasureTime = 0L
    private var totalFramesCounter = 0L
    private var sessionStartTime = 0L

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            onLog("Camera", "Camera available: ID $cameraId", false)
            refreshCameraList()
        }

        override fun onCameraUnavailable(cameraId: String) {
            onLog("Camera", "Camera unavailable / unplugged: ID $cameraId", false)
            refreshCameraList()
        }
    }

    fun start() {
        startBackgroundThread()
        cameraManager.registerAvailabilityCallback(availabilityCallback, backgroundHandler)
        refreshCameraList()
    }

    fun stop() {
        closeCamera()
        try {
            cameraManager.unregisterAvailabilityCallback(availabilityCallback)
        } catch (_: Exception) {}
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("StreamRelayCaptureBg").apply {
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
        } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
    }

    fun refreshCameraList() {
        try {
            val cameraIds = cameraManager.cameraIdList
            val list = mutableListOf<CameraDeviceInfo>()

            for (id in cameraIds) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val isExternal = facing == CameraCharacteristics.LENS_FACING_EXTERNAL
                val facingName = when (facing) {
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "External (UVC)"
                    CameraCharacteristics.LENS_FACING_BACK -> "Back"
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    else -> "Unknown ($facing)"
                }

                val level = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "External"
                    else -> "Unknown"
                }

                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = mutableListOf<Pair<Int, Int>>()
                if (map != null) {
                    val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
                    for (size in yuvSizes) {
                        sizes.add(Pair(size.width, size.height))
                    }
                    if (sizes.isEmpty()) {
                        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                        for (size in jpegSizes) {
                            sizes.add(Pair(size.width, size.height))
                        }
                    }
                }

                list.add(
                    CameraDeviceInfo(
                        id = id,
                        isExternal = isExternal,
                        facingName = facingName,
                        supportedSizes = sizes.sortedByDescending { it.first * it.second },
                        hardwareLevel = level
                    )
                )
            }

            _availableCameras.value = list
            val externalCount = list.count { it.isExternal }
            onLog("Camera", "Found ${list.size} camera(s) total ($externalCount external UVC)", false)
        } catch (e: Exception) {
            onLog("Camera", "Error scanning cameras: ${e.message}", true)
        }
    }

    fun setPreviewSurface(surface: Surface?) {
        this.previewSurface = surface
    }

    @SuppressLint("MissingPermission")
    fun openCamera(cameraId: String, previewSurface: Surface? = null) {
        closeCamera()
        this.previewSurface = previewSurface

        val handler = backgroundHandler ?: run {
            startBackgroundThread()
            backgroundHandler!!
        }

        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val isExternal = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // Choose best resolution: 1080p if available, else 720p, else largest available
            val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
            var chosenFormat = ImageFormat.YUV_420_888
            var chosenFormatName = "YUV_420_888"

            val targetSize = yuvSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
                ?: yuvSizes.firstOrNull { it.width == 1280 && it.height == 720 }
                ?: yuvSizes.maxByOrNull { it.width * it.height }
                ?: run {
                    val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                    chosenFormat = ImageFormat.JPEG
                    chosenFormatName = "JPEG"
                    jpegSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
                        ?: jpegSizes.maxByOrNull { it.width * it.height }
                }

            val width = targetSize?.width ?: 1280
            val height = targetSize?.height ?: 720

            val cameraDesc = if (isExternal) "External UVC Capture Card ($cameraId)" else "Camera $cameraId"
            onLog("Capture", "Opening $cameraDesc at ${width}x${height} ($chosenFormatName)...", false)

            _telemetry.value = CaptureTelemetry(
                isStreaming = false,
                cameraName = cameraDesc,
                resolutionWidth = width,
                resolutionHeight = height,
                bufferFormat = chosenFormatName,
                errorMessage = null
            )

            // Prepare ImageReader for receiving direct video frames
            val reader = ImageReader.newInstance(width, height, chosenFormat, 3)
            reader.setOnImageAvailableListener({ readerInstance ->
                try {
                    val image = readerInstance.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val now = SystemClock.elapsedRealtime()
                        totalFramesCounter++
                        frameCountSinceLastMeasure++

                        var totalBytes = 0
                        for (plane in image.planes) {
                            totalBytes += plane.buffer.remaining()
                        }

                        // Calculate FPS every 1000ms
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
                                lastFrameSizeBytes = totalBytes,
                                streamDurationSeconds = durationSec
                            )

                            if (totalFramesCounter % 120L == 0L) {
                                onLog(
                                    "Capture",
                                    "Frames: $totalFramesCounter | FPS: ${"%.1f".format(currentFps)} | Frame size: ${totalBytes / 1024} KB",
                                    false
                                )
                            }
                        } else {
                            _telemetry.value = _telemetry.value.copy(
                                isStreaming = true,
                                totalFrames = totalFramesCounter,
                                lastFrameTimeMs = now,
                                lastFrameSizeBytes = totalBytes
                            )
                        }

                        if (totalFramesCounter == 1L) {
                            onLog(
                                "Capture",
                                "SUCCESS: First video frame received! (${image.width}x${image.height}, $chosenFormatName, $totalBytes bytes)",
                                false
                            )
                        }
                    } finally {
                        image.close()
                    }
                } catch (e: Exception) {
                    onLog("Capture", "Error reading frame: ${e.message}", true)
                }
            }, handler)

            this.imageReader = reader

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    onLog("Capture", "Camera device opened: $cameraId", false)
                    createCaptureSession(camera, reader, width, height)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    onLog("Capture", "Camera disconnected: $cameraId", true)
                    closeCamera()
                    _telemetry.value = _telemetry.value.copy(
                        isStreaming = false,
                        errorMessage = "Device disconnected"
                    )
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val msg = when (error) {
                        ERROR_CAMERA_IN_USE -> "Camera already in use by another app"
                        ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                        ERROR_CAMERA_DISABLED -> "Camera disabled by device policy"
                        ERROR_CAMERA_DEVICE -> "Camera device fatal error"
                        ERROR_CAMERA_SERVICE -> "Camera service fatal error"
                        else -> "Camera error code: $error"
                    }
                    onLog("Capture", "Camera error: $msg", true)
                    closeCamera()
                    _telemetry.value = _telemetry.value.copy(
                        isStreaming = false,
                        errorMessage = msg
                    )
                }
            }, handler)

        } catch (e: Exception) {
            onLog("Capture", "Failed to open camera: ${e.message}", true)
            _telemetry.value = _telemetry.value.copy(
                isStreaming = false,
                errorMessage = e.message
            )
        }
    }

    private fun createCaptureSession(
        camera: CameraDevice,
        reader: ImageReader,
        width: Int,
        height: Int
    ) {
        val targets = mutableListOf<Surface>()
        targets.add(reader.surface)
        previewSurface?.let { targets.add(it) }

        onLog("Capture", "Creating capture session with ${targets.size} output surface(s)...", false)

        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        sessionStartTime = SystemClock.elapsedRealtime()
                        lastFpsMeasureTime = sessionStartTime
                        totalFramesCounter = 0L
                        frameCountSinceLastMeasure = 0

                        try {
                            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            for (target in targets) {
                                requestBuilder.addTarget(target)
                            }
                            requestBuilder.set(
                                CaptureRequest.CONTROL_MODE,
                                CaptureRequest.CONTROL_MODE_AUTO
                            )

                            session.setRepeatingRequest(
                                requestBuilder.build(),
                                null,
                                backgroundHandler
                            )

                            onLog("Capture", "Repeating capture request started! Waiting for incoming frames...", false)
                            _telemetry.value = _telemetry.value.copy(
                                isStreaming = true,
                                errorMessage = null
                            )
                        } catch (e: Exception) {
                            onLog("Capture", "Failed to start capture request: ${e.message}", true)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onLog("Capture", "Capture session configuration failed!", true)
                        _telemetry.value = _telemetry.value.copy(
                            isStreaming = false,
                            errorMessage = "Session configuration failed"
                        )
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            onLog("Capture", "Failed to create session: ${e.message}", true)
        }
    }

    fun closeCamera() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (_: Exception) {}
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {}
        cameraDevice = null

        try {
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null

        _telemetry.value = _telemetry.value.copy(
            isStreaming = false
        )
    }
}
