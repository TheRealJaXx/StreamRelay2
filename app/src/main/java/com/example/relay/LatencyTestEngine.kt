package com.example.relay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-precision, zero-hardware Latency Test Engine.
 *
 * Generates an animated broadcast-grade latency test pattern at 1280x720:
 * - Continuous sweep analog clock with high-speed millisecond rotation sub-dial.
 * - Monospace digital timecode with millisecond precision (.SSS) and frame counter.
 * - Smooth physical ground-bouncing ball and horizontal oscillating marker (NO strobe or black/white flashing).
 * - Optional synchronized 10ms audio tick at the start of each second for audio latency testing.
 *
 * Reuses all buffers to eliminate garbage-collection churn and preserve system performance.
 */
class LatencyTestEngine(
    private val onFrameGenerated: (ByteArray) -> Unit,
    private val onAudioChunkGenerated: (ByteArray) -> Unit
) {
    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val JPEG_QUALITY = 75

        // Audio format matching RelayClient: 48kHz, 16-bit stereo PCM
        const val SAMPLE_RATE = 48000
        const val CHUNK_MS = 20
        const val AUDIO_CHUNK_BYTES = (SAMPLE_RATE * 2 * 2 * CHUNK_MS) / 1000 // 3840 bytes
    }

    private val engineScope = CoroutineScope(Dispatchers.Default)
    private var videoJob: Job? = null
    private var audioJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    // Reusable single Bitmap & Canvas for frame rendering
    private val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val jpegStream = ByteArrayOutputStream(64 * 1024)

    // Observable current bitmap for lightweight local TX preview
    private val _currentBitmap = MutableStateFlow<Bitmap?>(null)
    val currentBitmap: StateFlow<Bitmap?> = _currentBitmap.asStateFlow()

    private val _framesGenerated = MutableStateFlow(0L)
    val framesGenerated: StateFlow<Long> = _framesGenerated.asStateFlow()

    @Volatile
    private var targetFps = 60

    @Volatile
    private var audioTickEnabled = true

    private var frameCount = 0L
    private var startTimeMs = 0L

    // Pre-allocated Audio buffers (Zero GC overhead)
    private val silenceAudioChunk = ByteArray(AUDIO_CHUNK_BYTES)
    private val tickAudioChunk = ByteArray(AUDIO_CHUNK_BYTES)

    // Pre-allocated Paints
    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.rgb(15, 23, 42) // #0F172A Dark Slate
    }

    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f
        color = Color.argb(40, 51, 65, 85) // faint grid
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
        color = Color.argb(180, 255, 255, 255)
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
        color = Color.rgb(56, 189, 248) // Cyan millis
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

    fun start(fps: Int = 60, audioTick: Boolean = true) {
        if (isRunning.get()) stop()

        targetFps = fps.coerceIn(15, 60)
        audioTickEnabled = audioTick
        frameCount = 0L
        startTimeMs = System.currentTimeMillis()
        isRunning.set(true)

        // 1. Video Frame Generation Loop
        videoJob = engineScope.launch {
            val targetIntervalNs = 1_000_000_000L / targetFps
            var nextFrameNs = System.nanoTime()

            while (isActive && isRunning.get()) {
                val nowNs = System.nanoTime()
                if (nowNs < nextFrameNs) {
                    val waitMs = (nextFrameNs - nowNs) / 1_000_000L
                    if (waitMs > 1) {
                        delay(waitMs - 1)
                    }
                    while (System.nanoTime() < nextFrameNs) {
                        Thread.yield()
                    }
                }
                nextFrameNs += targetIntervalNs

                renderAndDispatchFrame()
            }
        }

        // 2. Audio Generation Loop (Sends 20ms chunk every 20ms)
        audioJob = engineScope.launch {
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
        videoJob?.cancel()
        videoJob = null
        audioJob?.cancel()
        audioJob = null
        _currentBitmap.value = null
    }

    private fun renderAndDispatchFrame() {
        val nowMs = System.currentTimeMillis()
        frameCount++
        _framesGenerated.value = frameCount

        synchronized(bitmap) {
            drawTestPattern(canvas, nowMs)

            // Compress to JPEG for network transmission
            jpegStream.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpegStream)
            val jpegBytes = jpegStream.toByteArray()

            onFrameGenerated(jpegBytes)
        }

        // Expose bitmap for local Compose preview
        _currentBitmap.value = bitmap
    }

    private fun drawTestPattern(c: Canvas, nowMs: Long) {
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
        c.drawText("LATENCY BENCHMARK · ZERO FLASH TEST ENVIRONMENT", 40f, 74f, subTitlePaint)

        val fpsText = "TARGET: ${targetFps} FPS"
        val frameText = "FRAME: #${String.format(Locale.US, "%06d", frameCount)}"
        c.drawText(fpsText, 860f, 52f, monoInfoPaint)
        c.drawText(frameText, 1060f, 52f, monoInfoPaint)
        c.drawLine(40f, 92f, 1240f, 92f, floorPaint)

        calendar.timeInMillis = nowMs
        val hours = calendar.get(Calendar.HOUR)
        val minutes = calendar.get(Calendar.MINUTE)
        val seconds = calendar.get(Calendar.SECOND)
        val millis = calendar.get(Calendar.MILLISECOND)

        // 3. Left: Precision Analog Clock
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

        // Hour Hand
        val hourAngle = (hours + minutes / 60f + seconds / 3600f) * 30.0 * PI / 180.0
        val hLen = 85f
        c.drawLine(cx, cy, cx + (hLen * sin(hourAngle)).toFloat(), cy - (hLen * cos(hourAngle)).toFloat(), hourHandPaint)

        // Minute Hand
        val minAngle = (minutes + seconds / 60f + millis / 60000f) * 6.0 * PI / 180.0
        val mLen = 130f
        c.drawLine(cx, cy, cx + (mLen * sin(minAngle)).toFloat(), cy - (mLen * cos(minAngle)).toFloat(), minuteHandPaint)

        // High-Speed Millisecond Sub-Dial (1 full rotation every 1000ms / 1 second)
        val subCx = cx
        val subCy = cy + 65f
        val subRadius = 42f
        c.drawCircle(subCx, subCy, subRadius, cardBgPaint)
        c.drawCircle(subCx, subCy, subRadius, cardBorderPaint)
        c.drawText("0", subCx, subCy - subRadius + 12f, subNumPaint)
        c.drawText("500", subCx, subCy + subRadius - 4f, subNumPaint)
        c.drawText("250", subCx + subRadius - 10f, subCy + 4f, subNumPaint)
        c.drawText("750", subCx - subRadius + 10f, subCy + 4f, subNumPaint)

        val subAngle = (millis / 1000.0) * 2.0 * PI
        val subLen = 30f
        c.drawLine(subCx, subCy, subCx + (subLen * sin(subAngle)).toFloat(), subCy - (subLen * cos(subAngle)).toFloat(), subDialHandPaint)
        c.drawCircle(subCx, subCy, 3f, subDialHandPaint)

        // Continuous Sweep Second Hand
        val secAngle = (seconds + millis / 1000f) * 6.0 * PI / 180.0
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
        val millisStr = ".${String.format(Locale.US, "%03d", millis)}"
        c.drawText(timeStr, 554f, 218f, digitalTimePaint)
        c.drawText(millisStr, 860f, 218f, millisTextPaint)

        val uptimeMs = if (startTimeMs > 0) nowMs - startTimeMs else 0L
        val upSec = (uptimeMs / 1000) % 60
        val upMin = (uptimeMs / 60000) % 60
        val upHour = uptimeMs / 3600000
        val uptimeStr = String.format(Locale.US, "UPTIME: %02d:%02d:%02d   TICK: %03dms", upHour, upMin, upSec, millis)
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

        // Oscillating pointer
        val oscPhase = (nowMs % 2000L).toDouble() / 2000.0 * 2.0 * PI
        val progress = (sin(oscPhase) + 1.0) / 2.0
        val markerX = (trackX1 + progress * trackW).toFloat()
        c.drawLine(markerX, trackY - 18f, markerX, trackY + 18f, secondHandPaint)
        c.drawCircle(markerX, trackY, 8f, subDialHandPaint)

        // 6. Bottom Ground & Physics Bouncing Ball (Zero Flashing)
        val floorY = 660f
        c.drawLine(40f, floorY, 1240f, floorY, floorPaint)

        // Hash marks under ground line for solid visual grounding
        for (gx in 40 until 1240 step 40) {
            c.drawLine(gx.toFloat(), floorY, (gx - 15).toFloat(), floorY + 15f, gridPaint)
        }

        // Parabolic Bouncing Ball Physics
        val bouncePeriod = 1100L
        val bPhase = (nowMs % bouncePeriod).toFloat() / bouncePeriod.toFloat()
        val normalizedH = 2f * abs(bPhase - 0.5f) // 1.0 at apex, 0.0 at ground contact
        val bounceHeight = 110f
        val ballRadius = 24f
        val ballY = floorY - ballRadius - (bounceHeight * (normalizedH * normalizedH))

        // Horizontal sweep of the ball across ground
        val horizPeriod = 5000L
        val hPhase = (nowMs % horizPeriod).toDouble() / horizPeriod.toDouble() * 2.0 * PI
        val ballX = (640.0 + 520.0 * sin(hPhase)).toFloat()

        // Soft ground shadow scaling with altitude
        val shadowW = (ballRadius * 2.2f) * (1.2f - 0.7f * normalizedH)
        val shadowH = 10f * (1.0f - 0.5f * normalizedH)
        shadowRect.set(ballX - shadowW / 2f, floorY - 3f, ballX + shadowW / 2f, floorY + shadowH)
        c.drawOval(shadowRect, shadowPaint)

        // Ball with soft specular highlight (NO strobe / NO flash)
        c.drawCircle(ballX, ballY, ballRadius, ballPaint)
        c.drawCircle(ballX - 7f, ballY - 7f, 7f, ballHighlightPaint)

        c.drawText(
            "PHYSICAL BOUNCING BALL · NO FLASHING STROBE · CONTINUOUS MOTION",
            40f,
            HEIGHT - 18f,
            smallLabelPaint
        )
    }
}
