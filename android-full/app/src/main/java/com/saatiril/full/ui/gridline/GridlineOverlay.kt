package com.saatiril.full.ui.gridline

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.saatiril.full.data.GridlineColor
import com.saatiril.full.data.GridlineSettings
import com.saatiril.full.data.GridlineThickness
import com.saatiril.full.data.GridlineType

/**
 * Custom View that draws gridline overlay on top of camera preview.
 * Matches the web version's SVG gridlines exactly:
 * - Rule of Thirds: 2 vertical + 2 horizontal at 33.3%/66.6%
 * - Quarters: 3 vertical + 3 horizontal at 25%/50%/75%
 * - Crosshair: Center cross + concentric circles + tick marks
 * - Diagonal: 2 diagonal lines + thirds grid at 50% opacity
 */
class GridlineOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private var settings = GridlineSettings()
    
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    
    private val dashedCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    
    fun updateSettings(newSettings: GridlineSettings) {
        settings = newSettings
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (!settings.enabled) return
        
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        
        val strokeWidth = settings.thickness.value * 1.5f
        val alpha = (settings.color.opacity * 255).toInt()
        val color = Color.parseColor(settings.color.hex)
        
        linePaint.strokeWidth = strokeWidth
        linePaint.color = color
        linePaint.alpha = alpha
        
        circlePaint.strokeWidth = strokeWidth
        circlePaint.color = color
        circlePaint.alpha = alpha
        
        dashedCirclePaint.strokeWidth = strokeWidth
        dashedCirclePaint.color = color
        dashedCirclePaint.alpha = alpha
        
        when (settings.type) {
            GridlineType.THIRDS -> drawThirds(canvas, w, h)
            GridlineType.QUARTERS -> drawQuarters(canvas, w, h)
            GridlineType.CROSSHAIR -> drawCrosshair(canvas, w, h)
            GridlineType.DIAGONAL -> drawDiagonal(canvas, w, h)
        }
    }
    
    // ─── Rule of Thirds ─────────────────────────────────────────
    
    private fun drawThirds(canvas: Canvas, w: Float, h: Float) {
        // Vertical lines at 1/3 and 2/3
        canvas.drawLine(w / 3, 0f, w / 3, h, linePaint)
        canvas.drawLine(2 * w / 3, 0f, 2 * w / 3, h, linePaint)
        
        // Horizontal lines at 1/3 and 2/3
        canvas.drawLine(0f, h / 3, w, h / 3, linePaint)
        canvas.drawLine(0f, 2 * h / 3, w, 2 * h / 3, linePaint)
    }
    
    // ─── Quarters ───────────────────────────────────────────────
    
    private fun drawQuarters(canvas: Canvas, w: Float, h: Float) {
        // Vertical lines at 1/4, 2/4, 3/4
        canvas.drawLine(w / 4, 0f, w / 4, h, linePaint)
        canvas.drawLine(w / 2, 0f, w / 2, h, linePaint)
        canvas.drawLine(3 * w / 4, 0f, 3 * w / 4, h, linePaint)
        
        // Horizontal lines at 1/4, 2/4, 3/4
        canvas.drawLine(0f, h / 4, w, h / 4, linePaint)
        canvas.drawLine(0f, h / 2, w, h / 2, linePaint)
        canvas.drawLine(0f, 3 * h / 4, w, 3 * h / 4, linePaint)
    }
    
    // ─── Crosshair ──────────────────────────────────────────────
    
    private fun drawCrosshair(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2
        val cy = h / 2
        val tickLength = 20f
        
        // Center cross (short lines)
        val crossSize = 40f
        canvas.drawLine(cx - crossSize, cy, cx + crossSize, cy, linePaint)
        canvas.drawLine(cx, cy - crossSize, cx, cy + crossSize, linePaint)
        
        // Inner circle
        val innerRadius = Math.min(w, h) * 0.08f
        canvas.drawCircle(cx, cy, innerRadius, circlePaint)
        
        // Outer circle (dashed)
        val outerRadius = Math.min(w, h) * 0.2f
        canvas.drawCircle(cx, cy, outerRadius, dashedCirclePaint)
        
        // Tick marks at edges
        // Top
        canvas.drawLine(cx, 0f, cx, tickLength, linePaint)
        // Bottom
        canvas.drawLine(cx, h, cx, h - tickLength, linePaint)
        // Left
        canvas.drawLine(0f, cy, tickLength, cy, linePaint)
        // Right
        canvas.drawLine(w, cy, w - tickLength, cy, linePaint)
        
        // Thirds tick marks (small marks)
        val smallTick = 10f
        canvas.drawLine(w / 3, 0f, w / 3, smallTick, linePaint)
        canvas.drawLine(2 * w / 3, 0f, 2 * w / 3, smallTick, linePaint)
        canvas.drawLine(w / 3, h, w / 3, h - smallTick, linePaint)
        canvas.drawLine(2 * w / 3, h, 2 * w / 3, h - smallTick, linePaint)
        
        canvas.drawLine(0f, h / 3, smallTick, h / 3, linePaint)
        canvas.drawLine(0f, 2 * h / 3, smallTick, 2 * h / 3, linePaint)
        canvas.drawLine(w, h / 3, w - smallTick, h / 3, linePaint)
        canvas.drawLine(w, 2 * h / 3, w - smallTick, 2 * h / 3, linePaint)
    }
    
    // ─── Diagonal ───────────────────────────────────────────────
    
    private fun drawDiagonal(canvas: Canvas, w: Float, h: Float) {
        // Save the current alpha and reduce to 50% for the thirds grid
        val halfAlpha = (settings.color.opacity * 0.5f * 255).toInt()
        
        // Draw thirds grid at 50% opacity
        linePaint.alpha = halfAlpha
        canvas.drawLine(w / 3, 0f, w / 3, h, linePaint)
        canvas.drawLine(2 * w / 3, 0f, 2 * w / 3, h, linePaint)
        canvas.drawLine(0f, h / 3, w, h / 3, linePaint)
        canvas.drawLine(0f, 2 * h / 3, w, 2 * h / 3, linePaint)
        
        // Draw diagonals at full opacity
        linePaint.alpha = (settings.color.opacity * 255).toInt()
        canvas.drawLine(0f, 0f, w, h, linePaint)
        canvas.drawLine(w, 0f, 0f, h, linePaint)
    }
}
