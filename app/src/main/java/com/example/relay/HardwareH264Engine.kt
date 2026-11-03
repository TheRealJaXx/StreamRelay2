package com.example.relay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Android MediaCodec Hardware H.264 Encoder & Decoder.
 * Cuts network bandwidth by ~85% (from 30-40 Mbps MJPEG down to 3-5 Mbps H.264)
 * to drastically reduce mobile device heating and thermal throttling.
 */
class HardwareH264Encoder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val bitrateBps: Int = 4_000_000,
    private val targetFps: Int = 60,
    private val onEncodedFrame: (data: ByteArray, isKeyframe: Boolean) -> Unit
) {
    private var mediaCodec: MediaCodec? = null
    private var isRunning = false
    private var frameIndex = 0L

    private var cachedNv12 = ByteArray(width * height * 3 / 2)
    private var cachedPixels = IntArray(width * height)
    private val decodeOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
        inMutable = true
    }

    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    fun start(): Boolean {
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second keyframe interval

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }

            val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            mediaCodec = encoder
            isRunning = true
            Log.d(TAG, "Hardware H.264 Encoder initialized: ${width}x${height} @ ${targetFps}fps ($bitrateBps bps)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H.264 hardware encoder: ${e.message}", e)
            return false
        }
    }

    fun encodeJpeg(jpegBytes: ByteArray, size: Int = jpegBytes.size) {
        if (!isRunning || size <= 0) return
        try {
            val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, size, decodeOptions)
            if (bmp != null) {
                encodeBitmap(bmp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding JPEG for H.264 encode: ${e.message}")
        }
    }

    fun encodeBitmap(bitmap: Bitmap) {
        val encoder = mediaCodec ?: return
        if (!isRunning) return

        try {
            val w = minOf(width, bitmap.width)
            val h = minOf(height, bitmap.height)
            val totalPixels = w * h
            if (cachedPixels.size < totalPixels) {
                cachedPixels = IntArray(totalPixels)
            }
            bitmap.getPixels(cachedPixels, 0, w, 0, 0, w, h)

            val nv12Size = (w * h * 3) / 2
            if (cachedNv12.size < nv12Size) {
                cachedNv12 = ByteArray(nv12Size)
            }
            rgbToNv12(cachedPixels, cachedNv12, w, h)
            encodeYuv(cachedNv12, nv12Size)
        } catch (e: Exception) {
            Log.e(TAG, "Error in encodeBitmap: ${e.message}")
        }
    }

    fun encodeYuv(yuvData: ByteArray, size: Int = yuvData.size) {
        val encoder = mediaCodec ?: return
        if (!isRunning || size <= 0) return

        try {
            val inputIndex = encoder.dequeueInputBuffer(10_000L) // 10ms timeout
            if (inputIndex >= 0) {
                val inputBuffer: ByteBuffer? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    encoder.getInputBuffer(inputIndex)
                } else {
                    @Suppress("DEPRECATION")
                    encoder.inputBuffers[inputIndex]
                }

                if (inputBuffer != null) {
                    inputBuffer.clear()
                    val bytesToCopy = minOf(size, inputBuffer.remaining())
                    inputBuffer.put(yuvData, 0, bytesToCopy)

                    val ptsUs = (frameIndex++ * 1_000_000L / targetFps)
                    encoder.queueInputBuffer(inputIndex, 0, bytesToCopy, ptsUs, 0)
                }
            }

            // Drain output buffers
            drainEncoder()
        } catch (e: Exception) {
            Log.e(TAG, "Error during H.264 encode: ${e.message}")
        }
    }

    private fun rgbToNv12(pixels: IntArray, nv12: ByteArray, w: Int, h: Int) {
        val frameSize = w * h
        var yIndex = 0
        var uvIndex = frameSize

        for (j in 0 until h) {
            for (i in 0 until w) {
                val color = pixels[j * w + i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                nv12[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    nv12[uvIndex++] = u.coerceIn(0, 255).toByte()
                    nv12[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }

    private fun drainEncoder() {
        val encoder = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0L)
            if (outputIndex >= 0) {
                val outputBuffer: ByteBuffer? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    encoder.getOutputBuffer(outputIndex)
                } else {
                    @Suppress("DEPRECATION")
                    encoder.outputBuffers[outputIndex]
                }

                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    val outBytes = ByteArray(bufferInfo.size)
                    outputBuffer.get(outBytes)

                    val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ||
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                    onEncodedFrame(outBytes, isKeyframe)
                }
                encoder.releaseOutputBuffer(outputIndex, false)
            } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG, "Encoder output format changed: ${encoder.outputFormat}")
            }
        }
    }

    fun release() {
        isRunning = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
    }
}

class HardwareH264Decoder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val surface: Surface
) {
    private var mediaCodec: MediaCodec? = null
    private var isRunning = false
    private val bufferInfo = MediaCodec.BufferInfo()

    companion object {
        private const val TAG = "H264Decoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    fun start(): Boolean {
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }

            val decoder = MediaCodec.createDecoderByType(MIME_TYPE)
            decoder.configure(format, surface, null, 0)
            decoder.start()
            mediaCodec = decoder
            isRunning = true
            Log.d(TAG, "Hardware H.264 Decoder started: direct surface render ${width}x${height}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H.264 hardware decoder: ${e.message}", e)
            return false
        }
    }

    fun decodeNal(nalData: ByteArray) {
        val decoder = mediaCodec ?: return
        if (!isRunning || nalData.isEmpty()) return

        try {
            val inputIndex = decoder.dequeueInputBuffer(10_000L)
            if (inputIndex >= 0) {
                val inputBuffer: ByteBuffer? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    decoder.getInputBuffer(inputIndex)
                } else {
                    @Suppress("DEPRECATION")
                    decoder.inputBuffers[inputIndex]
                }

                if (inputBuffer != null) {
                    inputBuffer.clear()
                    val toCopy = minOf(nalData.size, inputBuffer.remaining())
                    inputBuffer.put(nalData, 0, toCopy)
                    decoder.queueInputBuffer(inputIndex, 0, toCopy, System.nanoTime() / 1000L, 0)
                }
            }

            // Release directly to Surface
            while (isRunning) {
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
                if (outputIndex >= 0) {
                    // render = true renders immediately to the attached SurfaceView
                    decoder.releaseOutputBuffer(outputIndex, true)
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Decoder output format changed: ${decoder.outputFormat}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding H.264 NAL: ${e.message}")
        }
    }

    fun release() {
        isRunning = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
    }
}
