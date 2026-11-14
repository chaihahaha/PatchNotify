package com.example.patchnotify

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 预览上方的叠加层：
 *  - 正常模式：画出所有 patch 的框、字段名、当前识别值和命中状态
 *  - 框选模式：手指拖动出新区域，松手回调归一化矩形
 */
class PatchOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var patches: List<Patch> = emptyList()
        set(value) { field = value; invalidate() }

    var reads: Map<String, PatchRead> = emptyMap()
        set(value) { field = value; invalidate() }

    var hits: Set<String> = emptySet()
        set(value) { field = value; invalidate() }

    var editMode = false
        set(value) {
            field = value
            invalidate()
        }

    var onRectDrawn: ((Float, Float, Float, Float) -> Unit)? = null

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        color = Color.WHITE
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(190, 0, 0, 0)
    }

    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false

    fun normToPx(p: Patch): RectF =
        RectF(p.x * width, p.y * height, (p.x + p.w) * width, (p.y + p.h) * height)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        for (p in patches) {
            val r = normToPx(p)
            val color = when {
                !p.monitor -> Color.GRAY
                hits.contains(p.id) -> Color.RED
                else -> Color.GREEN
            }
            boxPaint.color = color
            canvas.drawRect(r, boxPaint)

            fillPaint.color = (color and 0x00FFFFFF) or 0x33000000
            canvas.drawRect(r, fillPaint)

            val value = reads[p.id]?.raw?.replace("\n", " ")?.take(24) ?: ""
            val label = buildString {
                append(p.field.ifBlank { p.name.ifBlank { "?" } })
                if (!p.monitor) append(" (停)")
                if (value.isNotEmpty()) append(": ").append(value)
            }
            val tw = textPaint.measureText(label)
            val ty = if (r.top > 44f) r.top - 12f else r.bottom + 34f
            canvas.drawRect(r.left, ty - 30f, r.left + tw + 16f, ty + 8f, bgPaint)
            canvas.drawText(label, r.left + 8f, ty, textPaint)
        }

        if (editMode && dragging) {
            val r = RectF(minOf(downX, curX), minOf(downY, curY), maxOf(downX, curX), maxOf(downY, curY))
            boxPaint.color = Color.YELLOW
            canvas.drawRect(r, boxPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                curX = downX; curY = downY
                dragging = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                curX = event.x; curY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                val x1 = minOf(downX, curX) / width
                val y1 = minOf(downY, curY) / height
                val x2 = maxOf(downX, curX) / width
                val y2 = maxOf(downY, curY) / height
                invalidate()
                if (x2 - x1 > 0.02f && y2 - y1 > 0.01f) {
                    onRectDrawn?.invoke(x1, y1, x2 - x1, y2 - y1)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                return true
            }
        }
        return false
    }
}
