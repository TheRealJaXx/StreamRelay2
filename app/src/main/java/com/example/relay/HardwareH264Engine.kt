package com.example.relay

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Android MediaCodec Hardware H.264 Surface-Direct Encoder & Decoder.
 *
 * Uses MediaCodec with COLOR_FormatSurface (inputSurface):
 * - Zero CPU copying: UVCAndroid's native OpenGL engine renders decoded capture card
 *   frames directly to inputSurface.
 * - Hardware GPU/VPU compresses at full 60/30 FPS at 4 Mbps target bitrate.
 * - Pre-caches SPS and PPS headers (BUFFER_FLAG_CODEC_CONFIG) and prepends them
 *   to every IDR keyframe so the receiving phone can instantly initialize and decode.
 */
class HardwareH264Encoder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val bitrateBps: Int = 4_000_000,
    private val targetFps: Int = 60,
    private val onEncodedFrame: (data: ByteArray, isKeyframe: Boolean) -> Unit
) {
    private var mediaCodec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    @Volatile
    private var isRunning = false
    private var drainThread: Thread? = null

    // Cached SPS & PPS codec configuration to prepend to IDR keyframes
    @Volatile
    private var spsPpsConfig: ByteArray? = null

    companion object {
        private const val TAG = "H264SurfaceEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    fun start(): Boolean {
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
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
            inputSurface = encoder.createInputSurface()
            encoder.start()
            mediaCodec = encoder
            isRunning = true

            // Dedicated background thread to drain encoded H.264 NAL units
            drainThread = Thread({
                drainLoop()
            }, "H264EncoderDrain").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            Log.d(TAG, "Hardware H.264 Surface Encoder initialized: ${width}x${height} @ ${targetFps}fps ($bitrateBps bps)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H.264 hardware surface encoder: ${e.message}", e)
            return false
        }
    }

    /**
     * Fallback for synthetic Bitmaps (e.g. LatencyTestEngine):
     * Draws bitmap directly to hardware inputSurface via Canvas without CPU pixel loops.
     */
    fun renderBitmap(bitmap: Bitmap) {
        val surface = inputSurface ?: return
        if (!isRunning) return

        try {
            val canvas = surface.lockCanvas(null)
            if (canvas != null) {
                try {
                    val destRect = Rect(0, 0, width, height)
                    canvas.drawBitmap(bitmap, null, destRect, null)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering bitmap to H.264 input surface: ${e.message}")
        }
    }

    private fun drainLoop() {
        val encoder = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning) {
            try {
                val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
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

                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)

                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        if (isConfig) {
                            // Save SPS/PPS headers
                            spsPpsConfig = chunk.clone()
                            Log.d(TAG, "Captured H.264 SPS/PPS Config (${chunk.size} bytes)")
                        } else if (isKeyframe) {
                            val config = spsPpsConfig
                            val packetToSend = if (config != null && !startsWithSps(chunk)) {
                                val combined = ByteArray(config.size + chunk.size)
                                System.arraycopy(config, 0, combined, 0, config.size)
                                System.arraycopy(chunk, 0, combined, config.size, chunk.size)
                                combined
                            } else {
                                chunk
                            }
                            onEncodedFrame(packetToSend, true)
                        } else {
                            onEncodedFrame(chunk, false)
                        }
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = encoder.outputFormat
                    Log.d(TAG, "Encoder format updated: $newFormat")
                    val csd0 = newFormat.getByteBuffer("csd-0")
                    val csd1 = newFormat.getByteBuffer("csd-1")
                    if (csd0 != null && csd1 != null) {
                        val sps = ByteArray(csd0.remaining())
                        csd0.get(sps)
                        csd0.rewind()
                        val pps = ByteArray(csd1.remaining())
                        csd1.get(pps)
                        csd1.rewind()
                        val combined = ByteArray(sps.size + pps.size)
                        System.arraycopy(sps, 0, combined, 0, sps.size)
                        System.arraycopy(pps, 0, combined, sps.size, pps.size)
                        spsPpsConfig = combined
                        Log.d(TAG, "Captured SPS/PPS from outputFormat csd-0/csd-1 (${combined.size} bytes)")
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Error in drainLoop: ${e.message}")
                }
                break
            }
        }
    }

    private fun startsWithSps(data: ByteArray): Boolean {
        if (data.size < 5) return false
        val nalType = if (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            data[4].toInt() and 0x1F
        } else if (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            data[3].toInt() and 0x1F
        } else {
            -1
        }
        return nalType == 7 // 7 = SPS NAL unit
    }

    fun release() {
        isRunning = false
        drainThread?.interrupt()
        try {
            drainThread?.join(500)
        } catch (_: Exception) {}
        drainThread = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null
    }
}

/**
 * Android MediaCodec Hardware H.264 Decoder.
 * Direct surface renderer for the receiver.
 */
class HardwareH264Decoder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val surface: Surface
) {
    private var mediaCodec: MediaCodec? = null
    @Volatile
    private var isRunning = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var hasConfigured = false

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
                setInteger(MediaFormat.KEY_PUSH_BLANK_BUFFERS_ON_STOP, 1)
            }

            val decoder = MediaCodec.createDecoderByType(MIME_TYPE)
            decoder.configure(format, surface, null, 0)
            decoder.start()
            mediaCodec = decoder
            isRunning = true
            hasConfigured = true
            Log.d(TAG, "Hardware H.264 Decoder started for direct surface render (${width}x${height})")
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

                    val flags = if (isConfigNal(nalData)) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                    val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000L
                    decoder.queueInputBuffer(inputIndex, 0, toCopy, ptsUs, flags)
                }
            }

            // Immediately drain decoded frame directly to attached SurfaceView
            while (isRunning) {
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
                if (outputIndex >= 0) {
                    // render = true renders instantly onto the hardware Surface
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

    private fun isConfigNal(data: ByteArray): Boolean {
        if (data.size < 5) return false
        val nalType = if (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            data[4].toInt() and 0x1F
        } else if (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            data[3].toInt() and 0x1F
        } else {
            -1
        }
        return nalType == 7 || nalType == 8 // SPS or PPS
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
