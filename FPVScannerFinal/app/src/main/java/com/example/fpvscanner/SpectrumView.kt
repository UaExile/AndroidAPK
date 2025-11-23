package com.example.fpvscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val barPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0xFFBB86FC.toInt()
        isAntiAlias = true
    }

    @Volatile
    private var spectrumData: FloatArray? = null

    fun updateSpectrum(values: FloatArray) {
        spectrumData = values.clone()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = spectrumData ?: return
        if (data.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val n = data.size
        val barWidth = w / n

        val maxVal = data.maxOrNull() ?: 1f
        val norm = if (maxVal <= 0f) 1f else maxVal

        for (i in 0 until n) {
            val v = data[i] / norm
            val clamped = min(1f, v)
            val barHeight = clamped * h
            val left = i * barWidth
            val top = h - barHeight
            val right = left + barWidth * 0.8f
            canvas.drawRect(left, top, right, h, barPaint)
        }
    }
}
