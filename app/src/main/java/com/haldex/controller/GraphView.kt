package com.haldex.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class GraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxPoints = 100
    private val targetHistory = FloatArray(maxPoints)
    private val actualHistory = FloatArray(maxPoints)
    private var writeIndex = 0

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#252836")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A8D9F")
        textSize = 28f
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Бирюзовый - целевой ток
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    private val actualPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676") // Зеленый - фактический ток
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    private val targetPath = Path()
    private val actualPath = Path()

    fun addPoints(target: Float, actual: Float) {
        if (writeIndex < maxPoints) {
            targetHistory[writeIndex] = target
            actualHistory[writeIndex] = actual
            writeIndex++
        } else {
            System.arraycopy(targetHistory, 1, targetHistory, 0, maxPoints - 1)
            System.arraycopy(actualHistory, 1, actualHistory, 0, maxPoints - 1)
            targetHistory[maxPoints - 1] = target
            actualHistory[maxPoints - 1] = actual
        }
        invalidate() // Перерисовка экрана
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#1B1E2E"))

        val graphWidth = width.toFloat()
        val graphHeight = height.toFloat()
        val paddingLeft = 100f
        val paddingTop = 40f
        val paddingBottom = 40f

        val drawWidth = graphWidth - paddingLeft
        val drawHeight = graphHeight - paddingTop - paddingBottom

        // Сетка
        val gridLines = 4
        for (i in 0..gridLines) {
            val y = paddingTop + (drawHeight / gridLines) * i
            canvas.drawLine(paddingLeft, y, graphWidth, y, gridPaint)
            
            // Шкала токов (0.0А ... 1.6А)
            val amps = 1.6f - (1.6f / gridLines) * i
            canvas.drawText(String.format("%.1fA", amps), 10f, y + 10f, textPaint)
        }

        if (writeIndex < 2) return

        targetPath.reset()
        actualPath.reset()

        val stepX = drawWidth / (maxPoints - 1)
        val maxAmps = 1.6f

        for (i in 0 until writeIndex) {
            val x = paddingLeft + i * stepX
            
            // Заданное значение
            val targetY = paddingTop + drawHeight - (targetHistory[i] / maxAmps) * drawHeight
            if (i == 0) targetPath.moveTo(x, targetY) else targetPath.lineTo(x, targetY)

            // Фактическое значение
            val actualY = paddingTop + drawHeight - (actualHistory[i] / maxAmps) * drawHeight
            if (i == 0) actualPath.moveTo(x, actualY) else actualPath.lineTo(x, actualY)
        }

        canvas.drawPath(targetPath, targetPaint)
        canvas.drawPath(actualPath, actualPaint)

        // Легенда
        canvas.drawRect(paddingLeft + 20f, paddingTop - 25f, paddingLeft + 50f, paddingTop - 15f, Paint().apply { color = Color.parseColor("#00E5FF") })
        canvas.drawText("ЦЕЛЬ (Tgt_A)", paddingLeft + 65f, paddingTop - 12f, textPaint)

        canvas.drawRect(paddingLeft + 320f, paddingTop - 25f, paddingLeft + 350f, paddingTop - 15f, Paint().apply { color = Color.parseColor("#00E676") })
        canvas.drawText("ФАКТ (Act_A)", paddingLeft + 365f, paddingTop - 12f, textPaint)
    }
}
