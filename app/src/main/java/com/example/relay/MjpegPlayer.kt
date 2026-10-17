package com.example.relay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.SurfaceHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reusable Player Statistics exported to UI and telemetry collectors.
 */
data class MjpegPlayerStats(
    val fps: Float = 0f,
    val decodeTimeMsAvg: Float = 0f,
    val decodeTimeMsMax: Long = 0L,
    val frameAgeMs: Float = 0f,
    val arrivalIntervalP95Ms: Float = 0f,
    val droppedFrames: Long = 0L,
    val decodeSuccessCount: Long = 0L,
    val decodeFailureCount: Long = 0L,
    val lastFrameSizeBytes: Int = 0,
    val resolution: String = "",
    val lastHex4: String = "",
    val lastEndsEoi: Boolean = false,
    val lastError: String? = null
)

/**
 * High-performance, zero-allocation MJPEG Player and hardware-accelerated renderer.
 *
 * Requirements fulfilled:
 * - Dedicated thread named "RxDecode" running at Process.THREAD_PRIORITY_URGENT_DISPLAY.
 * - Latest-frame-wins queueing (drops stale frames immediately).
 * - Pool of reusable payload buffers (reader fills, decoder returns).
 * - Reusable mutable inBitmap allocated from real frame dimensions; resized on dimension change.
 * - Hardware canvas direct rendering (lockHardwareCanvas) with canvas cleared ONLY on resize/attach.
 * - Compose StateFlow copy published ONLY when no surface is attached.
 * - Full telemetry export: decode avg/max, frame age, arrival interval p95, dropped seq gaps, fps.
 */
class MjpegPlayer(
    private val onLog: (String, String, Boolean) -> Unit = { _, _, _ -> }
) {
    private val isRunning = AtomicBoolean(true)

    // Reusable payload buffer pool (prevents GC allocation spikes on high-bitrate MJPEG)
    private val initialBufferCapacity = 512 * 1024 // 512 KB
    private val payloadPool = ArrayBlockingQueue<ByteArray>(8).apply {
        repeat(8) { add(ByteArray(initialBufferCapacity)) }
    }

    fun acquireBuffer(neededCapacity: Int): ByteArray {
        val b = payloadPool.poll() ?: ByteArray(maxOf(neededCapacity, initialBufferCapacity))
        return if (b.size < neededCapacity) {
            ByteArray(maxOf(neededCapacity, b.size * 2))
        } else {
            b
        }
    }

    fun releaseBuffer(buffer: ByteArray) {
        if (payloadPool.remainingCapacity() > 0) {
            payloadPool.offer(buffer)
        }
    }

    // Frame submission & latest-frame-wins container
    class PendingFrame(
        val buffer: ByteArray,
        val length: Int,
        val sequenceNumber: Int,
        val arrivalNs: Long
    )

    private val frameLock = Object()
    private var pendingFrame: PendingFrame? = null
    private var newestSeenSeq = -1
    private var totalDroppedFrames = 0L

    // Surface Rendering
    @Volatile
    private var attachedSurfaceHolder: SurfaceHolder? = null
    private var surfaceNeedsClear = true
    private var lastCanvasWidth = 0
    private var lastCanvasHeight = 0
    private val surfacePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val surfaceSrcRect = Rect()
    private val surfaceDstRect = Rect()

    // Bitmap Recycling & Decoder State
    private var reusableBitmap: Bitmap? = null
    private var lastDecodedWidth = 0
    private var lastDecodedHeight = 0
    private val bitmapOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
        inMutable = true
    }

    // Compose StateFlow fallback (updated ONLY when no surface is attached)
    private val _currentBitmap = MutableStateFlow<Bitmap?>(null)
    val currentBitmap: StateFlow<Bitmap?> = _currentBitmap.asStateFlow()

    // Statistics & Telemetry
    private val _stats = MutableStateFlow(MjpegPlayerStats())
    val stats: StateFlow<MjpegPlayerStats> = _stats.asStateFlow()

    private var decodeSuccessCount = 0L
    private var decodeFailureCount = 0L
    private var lastErrorMsg: String? = null

    // Rolling metrics (1-second window)
    private var framesRenderedInSecond = 0
    private var lastStatsTickMs = SystemClock.elapsedRealtime()
    private var decodeTimeSumMs = 0f
    private var decodeTimeMaxMs = 0L
    private var lastFrameAgeMs = 0f
    private var lastArrivalNs = 0L
    private val arrivalIntervalsMs = ArrayList<Float>(120)

    private var decodeThread: Thread? = null

    init {
        decodeThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            decodeLoop()
        }, "RxDecode").apply {
            isDaemon = true
            start()
        }
    }

    fun attachSurface(holder: SurfaceHolder) {
        attachedSurfaceHolder = holder
        surfaceNeedsClear = true
        _currentBitmap.value = null // clear Compose fallback to save memory
    }

    fun detachSurface() {
        attachedSurfaceHolder = null
        surfaceNeedsClear = true
    }

    /**
     * Submits a newly read frame from network reader thread.
     * Takes ownership of [buffer]. Automatically releases older or replaced buffers back to pool.
     */
    fun submit(buffer: ByteArray, length: Int, seq: Int, arrivalNs: Long = System.nanoTime()) {
        synchronized(frameLock) {
            // Drop any frame whose sequence number is older than newest seen
            if (newestSeenSeq >= 0 && seq <= newestSeenSeq) {
                totalDroppedFrames++
                releaseBuffer(buffer)
                return
            }

            // Count sequence gaps as dropped frames
            if (newestSeenSeq >= 0 && seq > newestSeenSeq + 1) {
                val gap = (seq - newestSeenSeq - 1).toLong()
                totalDroppedFrames += gap
            }
            newestSeenSeq = seq

            // Latest-frame-wins: drop any pending frame waiting for decode
            val old = pendingFrame
            if (old != null) {
                totalDroppedFrames++
                releaseBuffer(old.buffer)
            }

            pendingFrame = PendingFrame(buffer, length, seq, arrivalNs)
            frameLock.notifyAll()
        }
    }

    private fun decodeLoop() {
        while (isRunning.get()) {
            val frame: PendingFrame
            synchronized(frameLock) {
                while (pendingFrame == null && isRunning.get()) {
                    try {
                        frameLock.wait()
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                if (!isRunning.get()) return
                frame = pendingFrame!!
                pendingFrame = null
            }

            try {
                processSingleFrame(frame)
            } finally {
                releaseBuffer(frame.buffer)
            }
        }
    }

    private fun processSingleFrame(frame: PendingFrame) {
        val decodeStartNs = System.nanoTime()

        // Arrival interval calculation
        if (lastArrivalNs > 0L) {
            val intervalMs = (frame.arrivalNs - lastArrivalNs) / 1_000_000f
            if (intervalMs in 0.1f..500f) {
                arrivalIntervalsMs.add(intervalMs)
            }
        }
        lastArrivalNs = frame.arrivalNs

        // Ensure inBitmap matches real frame dimensions (supports dynamic 720p/1080p switching)
        var targetBitmap = reusableBitmap
        if (targetBitmap == null || targetBitmap.isRecycled || lastDecodedWidth <= 0 || lastDecodedHeight <= 0) {
            val probeOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(frame.buffer, 0, frame.length, probeOpts)
            val w = probeOpts.outWidth
            val h = probeOpts.outHeight
            if (w > 0 && h > 0) {
                targetBitmap?.recycle()
                targetBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                reusableBitmap = targetBitmap
                lastDecodedWidth = w
                lastDecodedHeight = h
                surfaceNeedsClear = true
            }
        }

        bitmapOptions.inBitmap = targetBitmap

        var decoded: Bitmap? = try {
            BitmapFactory.decodeByteArray(frame.buffer, 0, frame.length, bitmapOptions)
        } catch (e: IllegalArgumentException) {
            // Dimension mismatch: clear inBitmap and reallocate to real frame size
            bitmapOptions.inBitmap = null
            try {
                val newBmp = BitmapFactory.decodeByteArray(frame.buffer, 0, frame.length, bitmapOptions)
                if (newBmp != null) {
                    reusableBitmap?.recycle()
                    reusableBitmap = newBmp
                    lastDecodedWidth = newBmp.width
                    lastDecodedHeight = newBmp.height
                    surfaceNeedsClear = true
                }
                newBmp
            } catch (ex: Exception) {
                lastErrorMsg = ex.message
                null
            }
        } catch (e: Exception) {
            lastErrorMsg = e.message
            null
        }

        // Fallback without inBitmap if needed
        if (decoded == null) {
            val fallbackOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            decoded = try {
                BitmapFactory.decodeByteArray(frame.buffer, 0, frame.length, fallbackOpts)
            } catch (e: Exception) {
                lastErrorMsg = e.message
                null
            }
        }

        val decodeEndNs = System.nanoTime()
        val decodeMs = (decodeEndNs - decodeStartNs) / 1_000_000f
        decodeTimeSumMs += decodeMs
        if (decodeMs.toLong() > decodeTimeMaxMs) {
            decodeTimeMaxMs = decodeMs.toLong()
        }

        val holder = attachedSurfaceHolder
        val hasSurface = holder != null && holder.surface.isValid

        if (decoded != null) {
            decodeSuccessCount++
            lastDecodedWidth = decoded.width
            lastDecodedHeight = decoded.height

            if (hasSurface) {
                // Direct hardware canvas draw
                val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        holder!!.lockHardwareCanvas()
                    } catch (_: Exception) {
                        try { holder!!.lockCanvas() } catch (_: Exception) { null }
                    }
                } else {
                    try { holder!!.lockCanvas() } catch (_: Exception) { null }
                }

                if (canvas != null) {
                    try {
                        val cW = canvas.width
                        val cH = canvas.height
                        val w = decoded.width
                        val h = decoded.height

                        // Clear canvas ONLY when surface or frame size changes (eliminates fullscreen GPU fills)
                        if (surfaceNeedsClear || cW != lastCanvasWidth || cH != lastCanvasHeight) {
                            canvas.drawColor(Color.BLACK)
                            lastCanvasWidth = cW
                            lastCanvasHeight = cH
                            surfaceNeedsClear = false
                        }

                        val scale = minOf(cW.toFloat() / w, cH.toFloat() / h)
                        val fitW = w * scale
                        val fitH = h * scale
                        val dx = (cW - fitW) / 2f
                        val dy = (cH - fitH) / 2f

                        surfaceSrcRect.set(0, 0, w, h)
                        surfaceDstRect.set(dx.toInt(), dy.toInt(), (dx + fitW).toInt(), (dy + fitH).toInt())
                        canvas.drawBitmap(decoded, surfaceSrcRect, surfaceDstRect, surfacePaint)
                    } finally {
                        try {
                            holder!!.unlockCanvasAndPost(canvas)
                        } catch (_: Exception) {}
                    }
                }
            } else {
                // Publish a safe immutable copy to Compose StateFlow ONLY when no surface is attached
                try {
                    _currentBitmap.value = decoded.copy(decoded.config ?: Bitmap.Config.RGB_565, false)
                } catch (_: Exception) {}
            }

            val presentedNs = System.nanoTime()
            lastFrameAgeMs = (presentedNs - frame.arrivalNs) / 1_000_000f
            framesRenderedInSecond++
        } else {
            decodeFailureCount++
        }

        // Once-per-second stats tick
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastStatsTickMs >= 1000L) {
            val elapsedSec = (nowMs - lastStatsTickMs) / 1000f
            val fps = (framesRenderedInSecond / elapsedSec * 10f).let { Math.round(it) / 10f }
            val avgDecode = if (framesRenderedInSecond > 0) decodeTimeSumMs / framesRenderedInSecond else 0f

            // Compute p95 of arrival interval
            val p95 = if (arrivalIntervalsMs.isNotEmpty()) {
                val sorted = arrivalIntervalsMs.sorted()
                val idx = ((sorted.size - 1) * 0.95f).toInt()
                sorted[idx]
            } else 0f

            // Hex head and EOI computed ONLY once per second (no per-frame overhead)
            val headHex = if (frame.length >= 4) {
                buildString {
                    for (i in 0 until minOf(4, frame.length)) {
                        append("%02X ".format(frame.buffer[i]))
                    }
                }.trim()
            } else ""

            val endsEoi = frame.length >= 2 &&
                (frame.buffer[frame.length - 2].toInt() and 0xFF) == 0xFF &&
                (frame.buffer[frame.length - 1].toInt() and 0xFF) == 0xD9

            _stats.value = MjpegPlayerStats(
                fps = fps,
                decodeTimeMsAvg = (avgDecode * 10f).let { Math.round(it) / 10f },
                decodeTimeMsMax = decodeTimeMaxMs,
                frameAgeMs = (lastFrameAgeMs * 10f).let { Math.round(it) / 10f },
                arrivalIntervalP95Ms = (p95 * 10f).let { Math.round(it) / 10f },
                droppedFrames = totalDroppedFrames,
                decodeSuccessCount = decodeSuccessCount,
                decodeFailureCount = decodeFailureCount,
                lastFrameSizeBytes = frame.length,
                resolution = if (lastDecodedWidth > 0) "${lastDecodedWidth}x${lastDecodedHeight}" else "",
                lastHex4 = headHex,
                lastEndsEoi = endsEoi,
                lastError = lastErrorMsg
            )

            framesRenderedInSecond = 0
            decodeTimeSumMs = 0f
            decodeTimeMaxMs = 0L
            arrivalIntervalsMs.clear()
            lastStatsTickMs = nowMs
        }
    }

    fun release() {
        isRunning.set(false)
        synchronized(frameLock) {
            frameLock.notifyAll()
        }
        try {
            decodeThread?.interrupt()
        } catch (_: Exception) {}
        decodeThread = null
        reusableBitmap?.recycle()
        reusableBitmap = null
        payloadPool.clear()
    }
}
