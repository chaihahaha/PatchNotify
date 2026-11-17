package com.example.patchnotify

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 预览上方的叠加层：
 *  - 正常模式：画出所有 patch 的框、字段名、当前识别值和命中状态
 *  - 框选模式：手指拖动出新区域，松手回调归一化矩形
 *  - 选中模式：长按下方条件标题后显示四个角，框内拖动移动、拖角缩放
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

    /** 当前正在移动/缩放的方框；由 Activity 的长按标题设置 */
    var selectedPatch: Patch? = null
        set(value) {
            field = value
            if (value != null) editMode = false
            dragKind = DRAG_NONE
            invalidate()
        }

    var onRectDrawn: ((Float, Float, Float, Float) -> Unit)? = null

    /** 手指松开后回调，Activity 负责持久化并清掉旧推理缓存 */
    var onPatchGeometryChanged: ((Patch) -> Unit)? = null

    fun selectPatch(p: Patch) { selectedPatch = p }
    fun clearSelection() { selectedPatch = null }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val boxOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.BLACK
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    // 工厂现场：标签用白底黑字黑框，别再叠一层半透明黑底
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16f * resources.displayMetrics.scaledDensity
        color = Color.BLACK
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(245, 255, 255, 255)
    }
    private val labelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.BLACK
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.BLACK
    }

    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false

    // 选中方框后的手势状态
    private var dragKind = DRAG_NONE
    private var resizeCorner = -1
    private var grabDx = 0f
    private var grabDy = 0f
    private var startX = 0f
    private var startY = 0f
    private var startW = 0f
    private var startH = 0f

    private val density = resources.displayMetrics.density
    private val handleSize = 14f * density
    private val handleTouch = 26f * density

    /**
     * 分析帧转正后的尺寸。PreviewView 默认 FILL_CENTER：画面按 max(缩放) 铺满视图，
     * 多出来的部分被裁掉。叠加层必须用同一个变换，否则竖屏下框选位置会整体偏移
     * （宽高比和视图不一致时最多能偏 10% 以上，裁出来的根本不是用户框的区域）。
     */
    private var imageW = 0
    private var imageH = 0

    fun setImageSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == imageW && h == imageH)) return
        imageW = w
        imageH = h
        invalidate()
    }

    /** 还没拿到第一帧时不要框选：否则归一化坐标会按视图比例算，和实际画面差一截 */
    fun hasImage(): Boolean = imageW > 0 && imageH > 0

    /** 画面在视图里的实际显示矩形（FILL_CENTER） */
    private fun contentRect(): RectF {
        if (width <= 0 || height <= 0) return RectF()
        if (imageW <= 0 || imageH <= 0) return RectF(0f, 0f, width.toFloat(), height.toFloat())
        val scale = maxOf(width.toFloat() / imageW, height.toFloat() / imageH)
        val dw = imageW * scale
        val dh = imageH * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        return RectF(left, top, left + dw, top + dh)
    }

    fun normToPx(p: Patch): RectF {
        val c = contentRect()
        return RectF(
            c.left + p.x * c.width(), c.top + p.y * c.height(),
            c.left + (p.x + p.w) * c.width(), c.top + (p.y + p.h) * c.height()
        )
    }

    private fun viewToNorm(x: Float, y: Float): Pair<Float, Float> {
        val c = contentRect()
        if (c.width() <= 0f || c.height() <= 0f) return 0f to 0f
        return ((x - c.left) / c.width()).coerceIn(0f, 1f) to
                ((y - c.top) / c.height()).coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        for (p in patches) {
            val r = normToPx(p)
            val color = when {
                !p.monitor -> Color.GRAY
                hits.contains(p.id) -> Color.RED
                else -> Color.WHITE
            }
            // 黑外框 + 白/红内框：在亮金属和暗背景上都看得清，且不用绿色/黄色
            canvas.drawRect(r, boxOuterPaint)
            boxPaint.color = color
            canvas.drawRect(r, boxPaint)

            fillPaint.color = (color and 0x00FFFFFF) or 0x22000000
            canvas.drawRect(r, fillPaint)

            val value = reads[p.id]?.raw?.replace("\n", " ")?.take(24) ?: ""
            val label = buildString {
                append(p.title())
                if (!p.monitor) append(" (停)")
                if (value.isNotEmpty()) append(": ").append(value)
            }
            val tw = textPaint.measureText(label)
            val th = textPaint.textSize
            val ty = if (r.top > th + 20f) r.top - 8f else r.bottom + th + 10f
            val box = RectF(r.left, ty - th, r.left + tw + 16f, ty + 8f)
            canvas.drawRect(box, bgPaint)
            canvas.drawRect(box, labelBorderPaint)
            canvas.drawText(label, r.left + 8f, ty, textPaint)
        }

        selectedPatch?.let { p ->
            if (patches.none { it.id == p.id }) return@let
            val r = normToPx(p)
            // 四个角：白底黑框小方块，和 PowerPoint 的缩放手柄一致
            val corners = arrayOf(
                r.left to r.top, r.right to r.top,
                r.left to r.bottom, r.right to r.bottom
            )
            for ((cx, cy) in corners) {
                val h = RectF(cx - handleSize / 2, cy - handleSize / 2,
                    cx + handleSize / 2, cy + handleSize / 2)
                canvas.drawRect(h, handleFillPaint)
                canvas.drawRect(h, handleBorderPaint)
            }
        }

        if (editMode && dragging) {
            val r = RectF(minOf(downX, curX), minOf(downY, curY), maxOf(downX, curX), maxOf(downY, curY))
            canvas.drawRect(r, boxOuterPaint)
            boxPaint.color = Color.WHITE
            canvas.drawRect(r, boxPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return handleNewRect(event)
        val p = selectedPatch ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = normToPx(p)
                val (nx, ny) = viewToNorm(event.x, event.y)
                resizeCorner = cornerAt(r, event.x, event.y)
                if (resizeCorner >= 0) {
                    dragKind = DRAG_RESIZE
                    startX = p.x; startY = p.y; startW = p.w; startH = p.h
                } else if (r.contains(event.x, event.y)) {
                    dragKind = DRAG_MOVE
                    grabDx = p.x - nx
                    grabDy = p.y - ny
                } else {
                    // 点框外退出编辑，恢复普通预览
                    selectedPatch = null
                    return true
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragKind == DRAG_NONE) return true
                val (nx, ny) = viewToNorm(event.x, event.y)
                when (dragKind) {
                    DRAG_MOVE -> {
                        p.x = (nx + grabDx).coerceIn(0f, 1f - p.w)
                        p.y = (ny + grabDy).coerceIn(0f, 1f - p.h)
                    }
                    DRAG_RESIZE -> resize(p, nx, ny)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragKind != DRAG_NONE) {
                    dragKind = DRAG_NONE
                    onPatchGeometryChanged?.invoke(p)
                }
                invalidate()
                return true
            }
        }
        return false
    }

    private fun handleNewRect(event: MotionEvent): Boolean {
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
                val (x1, y1) = viewToNorm(minOf(downX, curX), minOf(downY, curY))
                val (x2, y2) = viewToNorm(maxOf(downX, curX), maxOf(downY, curY))
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

    private fun cornerAt(r: RectF, x: Float, y: Float): Int {
        val xs = floatArrayOf(r.left, r.right, r.left, r.right)
        val ys = floatArrayOf(r.top, r.top, r.bottom, r.bottom)
        for (i in 0 until 4) {
            if (abs(x - xs[i]) <= handleTouch && abs(y - ys[i]) <= handleTouch) return i
        }
        return -1
    }

    private fun resize(p: Patch, nx: Float, ny: Float) {
        val minW = minOf(0.02f, startW)
        val minH = minOf(0.02f, startH)
        var left = startX
        var top = startY
        var right = startX + startW
        var bottom = startY + startH
        when (resizeCorner) {
            0 -> { left = nx.coerceIn(0f, right - minW); top = ny.coerceIn(0f, bottom - minH) }
            1 -> { right = nx.coerceIn(left + minW, 1f); top = ny.coerceIn(0f, bottom - minH) }
            2 -> { left = nx.coerceIn(0f, right - minW); bottom = ny.coerceIn(top + minH, 1f) }
            3 -> { right = nx.coerceIn(left + minW, 1f); bottom = ny.coerceIn(top + minH, 1f) }
        }
        p.x = left
        p.y = top
        p.w = right - left
        p.h = bottom - top
    }

    companion object {
        private const val DRAG_NONE = 0
        private const val DRAG_MOVE = 1
        private const val DRAG_RESIZE = 2
    }
}
