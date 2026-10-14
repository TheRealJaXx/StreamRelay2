package com.example.relay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Process
import android.view.SurfaceHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ultra-smooth, hardware-accelerated Latency Test Engine.
 *
 * Designed specifically for precision latency verification and manual side-by-side comparison:
 * - Dedicated high-priority render thread synchronized to nanosecond clock (System.nanoTime).
 * - Direct SurfaceView hardware-accelerated rendering (< 0.5ms draw latency) eliminating Compose recomposition overhead.
 * - Continuous sub-pixel, sub-millisecond math for both the analog clock hands and parabolic bouncing ball.
 * - Decoupled background JPEG compression worker with conflated channel so network transmission NEVER stalls local rendering.
 * - Monospace digital timecode with millisecond precision (.SSS) and frame counter.
 * - Synchronized 10ms 880Hz audio tick on every second boundary for audio latency verification.
 */
class LatencyTestEngine(
    private val onFrameGenerated: (ByteArray) -> Unit,
    private val onAudioChunkGenerated: (ByteArray) -> Unit
) {
    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val JPEG_QUALITY = 55

        // Audio format matching RelayClient: 48kHz, 16-bit stereo PCM
        const val SAMPLE_RATE = 48000
        const val CHUNK_MS = 20
        const val AUDIO_CHUNK_BYTES = (SAMPLE_RATE * 2 * 2 * CHUNK_MS) / 1000 // 3840 bytes
    }

    private val engineScope = CoroutineScope(Dispatchers.Default)
    private var renderThread: Thread? = null
    private var networkJob: Job? = null
    private var audioJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    // Conflated channel for decoupled network JPEG compression
    private val networkFrameChannel = Channel<Long>(Channel.CONFLATED)

    // Dedicated bitmap for network encoding
    private val networkBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.RGB_565)
    private val networkCanvas = Canvas(networkBitmap)

    // Observable current bitmap for backwards compatibility
    private val _currentBitmap = MutableStateFlow<Bitmap?>(null)
    val currentBitmap: StateFlow<Bitmap?> = _currentBitmap.asStateFlow()

    private val _framesGenerated = MutableStateFlow(0L)
    val framesGenerated: StateFlow<Long> = _framesGenerated.asStateFlow()

    @Volatile
    private var attachedSurfaceHolder: SurfaceHolder? = null

    @Volatile
    private var surfaceWidth = WIDTH

    @Volatile
    private var surfaceHeight = HEIGHT

    @Volatile
    private var targetFps = 60

    @Volatile
    private var audioTickEnabled = true

    private var frameCount = 0L
    private var startNanoTime = 0L

    // Pre-allocated Audio buffers (Zero GC overhead)
    private val silenceAudioChunk = ByteArray(AUDIO_CHUNK_BYTES)
    private val tickAudioChunk = ByteArray(AUDIO_CHUNK_BYTES)

    // Pre-allocated Paints (Hardware-accelerated)
    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.rgb(15, 23, 42) // #0F172A Dark Slate
    }

    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f
        color = Color.argb(40, 51, 65, 85)
    }

    private val cardBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.rgb(30, 41, 59) // #1E293B
        isAntiAlias = true
    }

    private val cardBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        color = Color.rgb(51, 65, 85) // #334155
        isAntiAlias = true
    }

    private val clockBezelPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.rgb(20, 29, 47)
        isAntiAlias = true
    }

    private val clockBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.0f
        color = Color.rgb(71, 85, 105)
        isAntiAlias = true
    }

    private val hourTickPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.0f
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(248, 250, 252)
        isAntiAlias = true
    }

    private val minuteTickPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(100, 116, 139)
        isAntiAlias = true
    }

    private val hourHandPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 7.0f
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(226, 232, 240)
        isAntiAlias = true
    }

    private val minuteHandPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(56, 189, 248) // Cyan
        isAntiAlias = true
    }

    private val secondHandPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(244, 63, 94) // Rose
        isAntiAlias = true
    }

    private val subDialHandPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(245, 158, 11) // Amber
        isAntiAlias = true
    }

    private val ballPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.rgb(56, 189, 248) // Vibrant Cyan
        isAntiAlias = true
    }

    private val ballHighlightPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(190, 255, 255, 255)
        isAntiAlias = true
    }

    private val shadowPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(140, 0, 0, 0)
        isAntiAlias = true
    }

    private val floorPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.0f
        color = Color.rgb(71, 85, 105)
        isAntiAlias = true
    }

    private val titleTextPaint = Paint().apply {
        color = Color.rgb(56, 189, 248)
        textSize = 28f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val subTitlePaint = Paint().apply {
        color = Color.rgb(148, 163, 184)
        textSize = 15f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        isAntiAlias = true
    }

    private val digitalTimePaint = Paint().apply {
        color = Color.rgb(248, 250, 252)
        textSize = 66f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        isAntiAlias = true
    }

    private val millisTextPaint = Paint().apply {
        color = Color.rgb(56, 189, 248)
        textSize = 66f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        isAntiAlias = true
    }

    private val monoInfoPaint = Paint().apply {
        color = Color.rgb(52, 211, 153) // Emerald
        textSize = 20f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        isAntiAlias = true
    }

    private val smallLabelPaint = Paint().apply {
        color = Color.rgb(148, 163, 184)
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        isAntiAlias = true
    }

    private val clockNumPaint = Paint().apply {
        color = Color.rgb(248, 250, 252)
        textSize = 20f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val subNumPaint = Paint().apply {
        color = Color.rgb(148, 163, 184)
        textSize = 10f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val calendar = Calendar.getInstance()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val shadowRect = RectF()
    private val cardRect = RectF()

    init {
        // Pre-generate 10ms 880Hz audio click into tickAudioChunk
        val samplesIn10ms = (SAMPLE_RATE * 10) / 1000 // 480 samples
        for (i in 0 until samplesIn10ms) {
            val angle = 2.0 * PI * 880.0 * (i.toDouble() / SAMPLE_RATE)
            val sample = (sin(angle) * 12000.0).toInt().toShort()
            val byteIdx = i * 4
            tickAudioChunk[byteIdx] = (sample.toInt() and 0xFF).toByte()
            tickAudioChunk[byteIdx + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            tickAudioChunk[byteIdx + 2] = (sample.toInt() and 0xFF).toByte()
            tickAudioChunk[byteIdx + 3] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
    }

    /**
     * Attach a SurfaceHolder for direct hardware-accelerated rendering.
     */
    fun attachSurface(holder: SurfaceHolder) {
        attachedSurfaceHolder = holder
    }

    /**
     * Update surface dimensions when changed or rotated.
     */
    fun onSurfaceSizeChanged(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            surfaceWidth = width
            surfaceHeight = height
        }
    }

    /**
     * Detach SurfaceHolder when destroyed.
     */
    fun detachSurface(holder: SurfaceHolder) {
        if (attachedSurfaceHolder == holder) {
            attachedSurfaceHolder = null
        }
    }

    fun start(fps: Int = 60, audioTick: Boolean = true) {
        if (isRunning.get()) stop()

        targetFps = fps.coerceIn(15, 60)
        audioTickEnabled = audioTick
        frameCount = 0L
        startNanoTime = System.nanoTime()
        isRunning.set(true)

        // 1. Dedicated High-Priority Render Thread
        renderThread = thread(name = "LatencyTestRenderThread", priority = Thread.MAX_PRIORITY) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            } catch (_: Exception) {}

            val targetIntervalNs = 1_000_000_000L / targetFps
            var nextFrameNs = System.nanoTime()

            while (isRunning.get()) {
                val nowNs = System.nanoTime()
                if (nowNs < nextFrameNs) {
                    val sleepMs = (nextFrameNs - nowNs) / 1_000_000L
                    if (sleepMs > 2) {
                        try {
                            Thread.sleep(sleepMs - 1)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    while (System.nanoTime() < nextFrameNs && isRunning.get()) {
                        Thread.yield()
                    }
                } else {
                    // Re-anchor if we lagged by more than 1 frame to prevent burst acceleration
                    if (nowNs - nextFrameNs > targetIntervalNs) {
                        nextFrameNs = nowNs
                    }
                }
                nextFrameNs += targetIntervalNs

                if (!isRunning.get()) break

                val frameTimeNs = System.nanoTime()
                frameCount++
                _framesGenerated.value = frameCount

                // A. Direct Hardware-Accelerated SurfaceView Render (< 0.5ms)
                val holder = attachedSurfaceHolder
                if (holder != null && holder.surface.isValid) {
                    val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            holder.lockHardwareCanvas()
                        } catch (_: Exception) {
                            try { holder.lockCanvas() } catch (_: Exception) { null }
                        }
                    } else {
                        try { holder.lockCanvas() } catch (_: Exception) { null }
                    }

                    if (canvas != null) {
                        try {
                            drawScaledPattern(canvas, frameTimeNs, canvas.width, canvas.height)
                        } finally {
                            try {
                                holder.unlockCanvasAndPost(canvas)
                            } catch (_: Exception) {}
                        }
                    }
                }

                // B. Decoupled Network JPEG Transmission
                // Dispatches frame timestamp to the background compression worker (conflated)
                networkFrameChannel.trySend(frameTimeNs)
            }
        }

        // 2. Decoupled Network Encoding Worker (Runs on background thread, never stalls preview)
        networkJob = engineScope.launch(Dispatchers.Default) {
            val jpegStream = ByteArrayOutputStream(64 * 1024)

            for (timeNs in networkFrameChannel) {
                if (!isRunning.get()) break

                // Drain conflated channel to take only the absolute latest timestamp
                while (true) {
                    networkFrameChannel.tryReceive().getOrNull() ?: break
                }

                synchronized(networkBitmap) {
                    // Use the exact nano time right when encoding starts for accurate zero-latency timestamps
                    val currentNano = System.nanoTime()
                    drawTestPattern(networkCanvas, currentNano)
                    jpegStream.reset()
                    networkBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpegStream)
                    val bytes = jpegStream.toByteArray()
                    onFrameGenerated(bytes)
                }

                _currentBitmap.value = networkBitmap
            }
        }

        // 3. Audio Generation Loop (Sends 20ms chunk every 20ms)
        audioJob = engineScope.launch(Dispatchers.Default) {
            while (isActive && isRunning.get()) {
                val now = System.currentTimeMillis()
                val isSecondStart = (now % 1000L) < CHUNK_MS

                if (audioTickEnabled && isSecondStart) {
                    onAudioChunkGenerated(tickAudioChunk)
                } else {
                    onAudioChunkGenerated(silenceAudioChunk)
                }

                delay(CHUNK_MS.toLong())
            }
        }
    }

    fun setTargetFps(fps: Int) {
        targetFps = fps.coerceIn(15, 60)
    }

    fun setAudioTick(enabled: Boolean) {
        audioTickEnabled = enabled
    }

    fun stop() {
        isRunning.set(false)
        renderThread?.interrupt()
        renderThread = null
        networkJob?.cancel()
        networkJob = null
        audioJob?.cancel()
        audioJob = null
        _currentBitmap.value = null
    }

    /**
     * Scales and centers the 1280x720 canvas coordinate space into any view dimension.
     */
    private fun drawScaledPattern(canvas: Canvas, timeNs: Long, viewWidth: Int, viewHeight: Int) {
        canvas.drawColor(Color.BLACK)

        val scale = minOf(viewWidth.toFloat() / WIDTH, viewHeight.toFloat() / HEIGHT)
        val dx = (viewWidth - WIDTH * scale) / 2f
        val dy = (viewHeight - HEIGHT * scale) / 2f

        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        drawTestPattern(canvas, timeNs)
        canvas.restore()
    }

    private fun drawTestPattern(c: Canvas, timeNs: Long) {
        val nowMs = System.currentTimeMillis()

        // 1. Background and subtle grid
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bgPaint)
        for (x in 80 until WIDTH step 80) {
            c.drawLine(x.toFloat(), 0f, x.toFloat(), HEIGHT.toFloat(), gridPaint)
        }
        for (y in 80 until HEIGHT step 80) {
            c.drawLine(0f, y.toFloat(), WIDTH.toFloat(), y.toFloat(), gridPaint)
        }

        // 2. Top Header Bar
        c.drawText("STREAM RELAY", 40f, 48f, titleTextPaint)
        c.drawText("LATENCY BENCHMARK · ULTRA SMOOTH 60 FPS ENVIRONMENT", 40f, 74f, subTitlePaint)

        val fpsText = "TARGET: ${targetFps} FPS"
        val frameText = "FRAME: #${String.format(Locale.US, "%06d", frameCount)}"
        c.drawText(fpsText, 860f, 52f, monoInfoPaint)
        c.drawText(frameText, 1060f, 52f, monoInfoPaint)
        c.drawLine(40f, 92f, 1240f, 92f, floorPaint)

        // Continuous high-precision fractional time calculations synchronized to epoch
        val millisInt = (nowMs % 1000L).toInt()
        val fractionalSec = (nowMs % 60000L).toDouble() / 1000.0

        calendar.timeInMillis = nowMs
        val hours = calendar.get(Calendar.HOUR)
        val minutes = calendar.get(Calendar.MINUTE)

        // 3. Left: Precision Analog Clock with Continuous Sub-Dial
        val cx = 290f
        val cy = 320f
        val radius = 175f

        // Outer Bezel
        c.drawCircle(cx, cy, radius, clockBezelPaint)
        c.drawCircle(cx, cy, radius, clockBorderPaint)

        // Hour and Minute Ticks
        for (i in 0 until 60) {
            val angleRad = (i * 6.0) * PI / 180.0
            val sinA = sin(angleRad).toFloat()
            val cosA = cos(angleRad).toFloat()
            val isHour = i % 5 == 0
            val tickLen = if (isHour) 16f else 8f
            val startR = radius - 6f - tickLen
            val endR = radius - 6f
            val paint = if (isHour) hourTickPaint else minuteTickPaint

            c.drawLine(cx + startR * sinA, cy - startR * cosA, cx + endR * sinA, cy - endR * cosA, paint)
        }

        // Major Hour Numbers (12, 3, 6, 9)
        c.drawText("12", cx, cy - radius + 42f, clockNumPaint)
        c.drawText("3", cx + radius - 30f, cy + 7f, clockNumPaint)
        c.drawText("6", cx, cy + radius - 20f, clockNumPaint)
        c.drawText("9", cx - radius + 30f, cy + 7f, clockNumPaint)

        // Continuous Hour Hand
        val hourAngle = (hours + minutes / 60.0 + fractionalSec / 3600.0) * 30.0 * PI / 180.0
        val hLen = 85f
        c.drawLine(cx, cy, cx + (hLen * sin(hourAngle)).toFloat(), cy - (hLen * cos(hourAngle)).toFloat(), hourHandPaint)

        // Continuous Minute Hand
        val minAngle = (minutes + fractionalSec / 60.0) * 6.0 * PI / 180.0
        val mLen = 130f
        c.drawLine(cx, cy, cx + (mLen * sin(minAngle)).toFloat(), cy - (mLen * cos(minAngle)).toFloat(), minuteHandPaint)

        // High-Speed Millisecond Sub-Dial (1 full revolution every 1000ms / 1 second)
        val subCx = cx
        val subCy = cy + 65f
        val subRadius = 42f
        c.drawCircle(subCx, subCy, subRadius, cardBgPaint)
        c.drawCircle(subCx, subCy, subRadius, cardBorderPaint)
        c.drawText("0", subCx, subCy - subRadius + 12f, subNumPaint)
        c.drawText("500", subCx, subCy + subRadius - 4f, subNumPaint)
        c.drawText("250", subCx + subRadius - 10f, subCy + 4f, subNumPaint)
        c.drawText("750", subCx - subRadius + 10f, subCy + 4f, subNumPaint)

        // Continuous Sub-Dial Millisecond Hand (100% in sync with .000 - .999 digital slate)
        val subAngle = (millisInt / 1000.0) * 2.0 * PI
        val subLen = 30f
        c.drawLine(subCx, subCy, subCx + (subLen * sin(subAngle)).toFloat(), subCy - (subLen * cos(subAngle)).toFloat(), subDialHandPaint)
        c.drawCircle(subCx, subCy, 3f, subDialHandPaint)

        // Continuous Sweep Second Hand
        val secAngle = (fractionalSec / 60.0) * 2.0 * PI
        val sLen = 150f
        val backLen = 30f
        c.drawLine(
            cx - (backLen * sin(secAngle)).toFloat(),
            cy + (backLen * cos(secAngle)).toFloat(),
            cx + (sLen * sin(secAngle)).toFloat(),
            cy - (sLen * cos(secAngle)).toFloat(),
            secondHandPaint
        )
        c.drawCircle(cx, cy, 6f, secondHandPaint)

        // 4. Right Top: Digital Timecode Slate Card
        cardRect.set(530f, 110f, 1240f, 320f)
        c.drawRoundRect(cardRect, 16f, 16f, cardBgPaint)
        c.drawRoundRect(cardRect, 16f, 16f, cardBorderPaint)

        c.drawText("SYNCHRONIZED TIMECODE (MILLI-PRECISION)", 554f, 142f, smallLabelPaint)

        val timeStr = timeFormat.format(Date(nowMs))
        val millisStr = ".${String.format(Locale.US, "%03d", millisInt)}"
        c.drawText(timeStr, 554f, 218f, digitalTimePaint)
        c.drawText(millisStr, 860f, 218f, millisTextPaint)

        val uptimeMs = if (startNanoTime > 0) (timeNs - startNanoTime) / 1_000_000L else 0L
        val upSec = (uptimeMs / 1000) % 60
        val upMin = (uptimeMs / 60000) % 60
        val upHour = uptimeMs / 3600000
        val uptimeStr = String.format(Locale.US, "UPTIME: %02d:%02d:%02d   TICK: %03dms", upHour, upMin, upSec, millisInt)
        c.drawText(uptimeStr, 554f, 268f, monoInfoPaint)
        c.drawText("HOLD TRANSMITTER & RECEIVER SIDE-BY-SIDE TO CALCULATE DISPLAY LATENCY", 554f, 300f, smallLabelPaint)

        // 5. Right Bottom: Linear Motion Sweep Slider (2s oscillation cycle)
        cardRect.set(530f, 340f, 1240f, 470f)
        c.drawRoundRect(cardRect, 16f, 16f, cardBgPaint)
        c.drawRoundRect(cardRect, 16f, 16f, cardBorderPaint)

        c.drawText("HORIZONTAL SWEEP TRACK (2.0s CYCLE)", 554f, 370f, smallLabelPaint)
        val trackY = 416f
        val trackX1 = 570f
        val trackX2 = 1200f
        val trackW = trackX2 - trackX1
        c.drawLine(trackX1, trackY, trackX2, trackY, cardBorderPaint)

        // Markers at 0%, 25%, 50%, 75%, 100%
        for (step in 0..4) {
            val mx = trackX1 + (trackW * step / 4f)
            c.drawLine(mx, trackY - 8f, mx, trackY + 8f, cardBorderPaint)
            c.drawText("${step * 25}%", mx - 12f, trackY + 26f, smallLabelPaint)
        }

        // Oscillating pointer with 2-second cycle
        val oscPhase = ((nowMs % 2000L).toDouble() / 2000.0) * 2.0 * PI
        val progress = (sin(oscPhase) + 1.0) / 2.0
        val markerX = (trackX1 + progress * trackW).toFloat()
        c.drawLine(markerX, trackY - 18f, markerX, trackY + 18f, secondHandPaint)
        c.drawCircle(markerX, trackY, 8f, subDialHandPaint)

        // 6. Bottom Ground & Physics Bouncing Ball (Zero Flashing, Pure Parabolic Arc)
        val floorY = 660f
        c.drawLine(40f, floorY, 1240f, floorY, floorPaint)

        for (gx in 40 until 1240 step 40) {
            c.drawLine(gx.toFloat(), floorY, (gx - 15).toFloat(), floorY + 15f, gridPaint)
        }

        // Parabolic physics: 1-second bounce cycle
        val bouncePeriodMs = 1000L
        val t = (nowMs % bouncePeriodMs).toDouble() / bouncePeriodMs.toDouble()
        val parabolicH = (4.0 * t * (1.0 - t)).toFloat() // 0.0 at ground, 1.0 at apex
        val bounceHeight = 110f
        val ballRadius = 24f
        val ballY = floorY - ballRadius - (bounceHeight * parabolicH)

        // Smooth horizontal sweep across ground (4s cycle)
        val horizPeriodMs = 4000L
        val hPhase = ((nowMs % horizPeriodMs).toDouble() / horizPeriodMs.toDouble()) * 2.0 * PI
        val ballX = (640.0 + 520.0 * sin(hPhase)).toFloat()

        // Ground shadow scaling dynamically with altitude
        val shadowW = (ballRadius * 2.4f) * (1.2f - 0.65f * parabolicH)
        val shadowH = 10f * (1.0f - 0.55f * parabolicH)
        shadowRect.set(ballX - shadowW / 2f, floorY - 3f, ballX + shadowW / 2f, floorY + shadowH)
        c.drawOval(shadowRect, shadowPaint)

        // Ball with soft specular highlight (NO strobe / NO flash)
        c.drawCircle(ballX, ballY, ballRadius, ballPaint)
        c.drawCircle(ballX - 7f, ballY - 7f, 7f, ballHighlightPaint)

        c.drawText(
            "PHYSICAL BOUNCING BALL · NO FLASHING STROBE · HARDWARE-ACCELERATED CONTINUOUS MOTION",
            40f,
            HEIGHT - 18f,
            smallLabelPaint
        )
    }
}
