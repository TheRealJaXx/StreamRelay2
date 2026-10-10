package com.example.relay

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * Provides an offscreen hardware Surface backed by ImageReader that immediately drains
 * and releases incoming frames on a background thread.
 * This guarantees the camera producer buffer queue never fills up or stalls when
 * the on-screen preview is disabled.
 */
class OffscreenDrainer(width: Int = 1280, height: Int = 720) {
    private val handlerThread = HandlerThread("UvcOffscreenDrainer").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private var imageReader: ImageReader? = null

    val surface: Surface

    init {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            try {
                val image = r.acquireLatestImage()
                image?.close()
            } catch (_: Exception) {}
        }, handler)
        imageReader = reader
        surface = reader.surface
    }

    fun release() {
        try {
            imageReader?.close()
            handlerThread.quitSafely()
        } catch (_: Exception) {}
        imageReader = null
    }
}
