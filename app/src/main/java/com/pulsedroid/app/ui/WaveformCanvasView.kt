package com.pulsedroid.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * High-performance 60 FPS oscilloscope waveform canvas for real-time SCG and PPG signals.
 * Renders smooth medical-grade traces with zero GC allocations in the draw cycle.
 */
class WaveformCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxPoints = 250
    private val scgPoints = FloatArray(maxPoints)
    private val ppgPoints = FloatArray(maxPoints)
    private val scgBeatMarkers = BooleanArray(maxPoints)
    private val ppgBeatMarkers = BooleanArray(maxPoints)

    private var headIndex = 0

    // Paints
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#152033")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val scgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Vibrant Cyan
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val ppgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2D55") // Electric Crimson
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val scgBeatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8000E5FF")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val ppgBeatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FF2D55")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8892B0")
        textSize = 28f
    }

    private val scgPath = Path()
    private val ppgPath = Path()

    fun addSamples(scg: Float, ppg: Float, isScgBeat: Boolean, isPpgBeat: Boolean) {
        scgPoints[headIndex] = scg
        ppgPoints[headIndex] = ppg
        scgBeatMarkers[headIndex] = isScgBeat
        ppgBeatMarkers[headIndex] = isPpgBeat

        headIndex = (headIndex + 1) % maxPoints
        postInvalidateOnAnimation()
    }

    fun clear() {
        scgPoints.fill(0f)
        ppgPoints.fill(0f)
        scgBeatMarkers.fill(false)
        ppgBeatMarkers.fill(false)
        headIndex = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Draw background grid
        drawGrid(canvas, w, h)

        val halfH = h / 2f
        val scgCenterY = halfH * 0.5f
        val ppgCenterY = halfH + (halfH * 0.5f)

        // Labels
        canvas.drawText("CHEST SCG (Aortic Ejection)", 24f, 40f, textPaint)
        canvas.drawText("CAPILLARY PPG (Pulse Arrival)", 24f, halfH + 40f, textPaint)

        // Divider
        canvas.drawLine(0f, halfH, w, halfH, gridPaint)

        // Build Paths
        scgPath.reset()
        ppgPath.reset()

        val stepX = w / (maxPoints - 1)
        var scgStarted = false
        var ppgStarted = false

        for (i in 0 until maxPoints) {
            val bufIdx = (headIndex + i) % maxPoints
            val x = i * stepX

            // SCG normalized coordinate
            val rawScg = scgPoints[bufIdx]
            val scgY = (scgCenterY - (rawScg * (halfH * 0.35f))).coerceIn(10f, halfH - 10f)
            if (!scgStarted) {
                scgPath.moveTo(x, scgY)
                scgStarted = true
            } else {
                scgPath.lineTo(x, scgY)
            }

            // Draw SCG beat marker line
            if (scgBeatMarkers[bufIdx]) {
                canvas.drawLine(x, 10f, x, halfH - 10f, scgBeatPaint)
            }

            // PPG normalized coordinate
            val rawPpg = ppgPoints[bufIdx]
            val ppgY = (ppgCenterY - (rawPpg * (halfH * 0.35f))).coerceIn(halfH + 10f, h - 10f)
            if (!ppgStarted) {
                ppgPath.moveTo(x, ppgY)
                ppgStarted = true
            } else {
                ppgPath.lineTo(x, ppgY)
            }

            // Draw PPG beat marker line
            if (ppgBeatMarkers[bufIdx]) {
                canvas.drawLine(x, halfH + 10f, x, h - 10f, ppgBeatPaint)
            }
        }

        canvas.drawPath(scgPath, scgPaint)
        canvas.drawPath(ppgPath, ppgPaint)
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        val numCols = 8
        val colWidth = w / numCols
        for (i in 1 until numCols) {
            val x = i * colWidth
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }

        val numRows = 6
        val rowHeight = h / numRows
        for (i in 1 until numRows) {
            val y = i * rowHeight
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
    }
}
