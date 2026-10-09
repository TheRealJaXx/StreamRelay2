package com.example.model

data class UsbDeviceInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    val deviceClass: Int,
    val isUvcVideo: Boolean,
    val interfaceCount: Int,
    val interfacesSummary: List<String>,
    val hasPermission: Boolean
) {
    val formattedVidPid: String
        get() = String.format("%04X:%04X", vendorId, productId)

    val displayName: String
        get() = productName ?: (manufacturerName?.let { "$it (Device)" } ?: "USB Device ($formattedVidPid)")
}

data class StreamPreset(
    val id: String,
    val label: String,
    val width: Int,
    val height: Int,
    val isMjpeg: Boolean,
    val description: String
)

data class CaptureTelemetry(
    val isStreaming: Boolean = false,
    val cameraName: String = "",
    val resolutionWidth: Int = 0,
    val resolutionHeight: Int = 0,
    val fps: Float = 0f,
    val totalFrames: Long = 0L,
    val bufferFormat: String = "None",
    val lastFrameTimeMs: Long = 0L,
    val lastFrameSizeBytes: Int = 0,
    val streamDurationSeconds: Long = 0L,
    val errorMessage: String? = null
)

data class LogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: String,
    val tag: String,
    val message: String,
    val isError: Boolean = false
)
