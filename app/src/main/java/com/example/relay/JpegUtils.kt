package com.example.relay

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

object JpegUtils {
    /**
     * Extracts clean JPEG bytes starting at SOI (0xFF, 0xD8) and ending at EOI (0xFF, 0xD9).
     * Eliminates trailing uninitialized buffer bytes (such as 1.7MB padding from camera buffer).
     */
    fun extractCleanJpeg(data: ByteArray): ByteArray {
        val size = data.size
        if (size < 4) return data

        // Check if data is already a clean JPEG (starts with SOI and ends with EOI)
        val isCleanStart = (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xD8
        val isCleanEnd = (data[size - 2].toInt() and 0xFF) == 0xFF && (data[size - 1].toInt() and 0xFF) == 0xD9
        if (isCleanStart && isCleanEnd) {
            return data
        }

        // 1. Locate Start Of Image (SOI): 0xFF, 0xD8
        var startIndex = -1
        for (i in 0 until minOf(size - 1, 512)) {
            if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD8) {
                startIndex = i
                break
            }
        }

        if (startIndex == -1) {
            return data
        }

        // 2. Walk JPEG markers to find Start Of Scan (SOS: 0xFF, 0xDA)
        var pos = startIndex + 2
        var sosFound = false

        while (pos < size - 4) {
            if ((data[pos].toInt() and 0xFF) != 0xFF) {
                pos++
                continue
            }
            val marker = data[pos + 1].toInt() and 0xFF
            if (marker == 0xDA) { // SOS marker
                sosFound = true
                val sosLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                pos += 2 + sosLen
                break
            } else if (marker == 0xD9) { // Immediate EOI
                return data.copyOfRange(startIndex, pos + 2)
            } else if (marker in 0xD0..0xD7 || marker == 0x00 || marker == 0xFF) {
                pos += 2
            } else {
                if (pos + 3 < size) {
                    val len = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                    pos += 2 + len
                } else {
                    break
                }
            }
        }

        // 3. Scan forward through entropy data for the FIRST EOI (0xFF, 0xD9)
        val scanStart = if (sosFound) pos else startIndex + 64
        var endIndex = -1
        var i = scanStart

        while (i < size - 1) {
            if ((data[i].toInt() and 0xFF) == 0xFF) {
                val next = data[i + 1].toInt() and 0xFF
                if (next == 0xD9) {
                    endIndex = i + 2
                    break
                } else if (next == 0x00) {
                    i += 2
                    continue
                }
            }
            i++
        }

        // Fallback: If marker walk didn't catch it, scan forward from startIndex + 128
        if (endIndex == -1) {
            for (j in (startIndex + 128) until (size - 1)) {
                if ((data[j].toInt() and 0xFF) == 0xFF && (data[j + 1].toInt() and 0xFF) == 0xD9) {
                    endIndex = j + 2
                    break
                }
            }
        }

        return if (endIndex > startIndex) {
            data.copyOfRange(startIndex, endIndex)
        } else {
            data
        }
    }

    /**
     * Determines whether byte array starts with JPEG SOI marker (0xFF, 0xD8).
     */
    fun isJpeg(data: ByteArray): Boolean {
        if (data.size < 2) return false
        return (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xD8
    }

    /**
     * Compresses raw uncompressed YUV / YUY2 camera buffer to standard JPEG.
     */
    fun compressYuvToJpeg(data: ByteArray, width: Int, height: Int, quality: Int = 80): ByteArray? {
        val (w, h) = resolveDimensions(data.size, width, height)
        if (w <= 0 || h <= 0) return null

        // 1. Try ImageFormat.YUY2 (standard for UVC raw frames where size = w * h * 2, e.g. 1,843,200 for 720p)
        if (data.size >= w * h * 2) {
            try {
                val yuv = YuvImage(data, ImageFormat.YUY2, w, h, null)
                val out = ByteArrayOutputStream(128 * 1024)
                if (yuv.compressToJpeg(Rect(0, 0, w, h), quality, out)) {
                    val result = out.toByteArray()
                    if (isJpeg(result)) return result
                }
            } catch (_: Exception) {}
        }

        // 2. Try ImageFormat.NV21 (size = w * h * 3 / 2)
        if (data.size >= (w * h * 3) / 2) {
            try {
                val yuv = YuvImage(data, ImageFormat.NV21, w, h, null)
                val out = ByteArrayOutputStream(128 * 1024)
                if (yuv.compressToJpeg(Rect(0, 0, w, h), quality, out)) {
                    val result = out.toByteArray()
                    if (isJpeg(result)) return result
                }
            } catch (_: Exception) {}
        }

        return null
    }

    /**
     * Direct software YUY2 to Bitmap conversion as an emergency fallback on RX.
     */
    fun yuy2ToBitmap(yuy2: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val totalPixels = width * height
            val pixels = IntArray(totalPixels)
            var yIdx = 0
            var pIdx = 0
            val maxLen = minOf(yuy2.size - 3, totalPixels * 2)

            while (yIdx < maxLen && pIdx < totalPixels - 1) {
                val y0 = yuy2[yIdx].toInt() and 0xFF
                val u = yuy2[yIdx + 1].toInt() and 0xFF
                val y1 = yuy2[yIdx + 2].toInt() and 0xFF
                val v = yuy2[yIdx + 3].toInt() and 0xFF
                yIdx += 4

                val c0 = y0 - 16
                val c1 = y1 - 16
                val d = u - 128
                val e = v - 128

                val r0 = ((298 * c0 + 409 * e + 128) shr 8).coerceIn(0, 255)
                val g0 = ((298 * c0 - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val b0 = ((298 * c0 + 516 * d + 128) shr 8).coerceIn(0, 255)
                pixels[pIdx++] = (0xFF shl 24) or (r0 shl 16) or (g0 shl 8) or b0

                val r1 = ((298 * c1 + 409 * e + 128) shr 8).coerceIn(0, 255)
                val g1 = ((298 * c1 - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val b1 = ((298 * c1 + 516 * d + 128) shr 8).coerceIn(0, 255)
                pixels[pIdx++] = (0xFF shl 24) or (r1 shl 16) or (g1 shl 8) or b1
            }

            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves width and height matching the raw buffer size if specified width/height don't match.
     */
    fun resolveDimensions(dataSize: Int, hintW: Int, hintH: Int): Pair<Int, Int> {
        if (hintW > 0 && hintH > 0 && (dataSize == hintW * hintH * 2 || dataSize >= hintW * hintH * 2)) {
            return Pair(hintW, hintH)
        }
        return when (dataSize) {
            1843200 -> Pair(1280, 720)   // 1280*720*2 (YUY2 720p)
            4147200 -> Pair(1920, 1080)  // 1920*1080*2 (YUY2 1080p)
            614400 -> Pair(640, 480)     // 640*480*2 (YUY2 480p)
            1382400 -> Pair(1280, 720)   // 1280*720*1.5 (NV21 720p)
            3110400 -> Pair(1920, 1080)  // 1920*1080*1.5 (NV21 1080p)
            460800 -> Pair(640, 480)     // 640*480*1.5 (NV21 480p)
            else -> if (hintW > 0 && hintH > 0) Pair(hintW, hintH) else Pair(1280, 720)
        }
    }
}
