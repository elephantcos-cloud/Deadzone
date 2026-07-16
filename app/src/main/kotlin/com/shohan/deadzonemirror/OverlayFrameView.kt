package com.shohan.deadzonemirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * The floating overlay's visual content and touch handling, built with
 * plain Views/Canvas rather than Compose. Hosting a ComposeView inside a
 * raw WindowManager-added window (outside any Activity) needs manual
 * lifecycle/ViewModelStore/SavedState owners wired up correctly, which is
 * a well-known source of crashes if any piece is missed. A plain View
 * sidesteps that entire risk while the WindowManager overlay is running
 * as a foreground service, independent of any Activity.
 */
class OverlayFrameView(context: Context) : View(context) {

    /** Latest mirrored screen frame. A fresh Bitmap each time (never mutated
     *  in place), so there is no race between the background capture thread
     *  writing pixels and the main thread drawing them. */
    var mirrorBitmap: Bitmap? = null
        set(value) {
            field = value
            postInvalidate()
        }

    var isEditMode: Boolean = false
        set(value) {
            field = value
            postInvalidate()
        }

    // Callbacks wired up by OverlayService.
    var onMoveRequested: ((dx: Int, dy: Int) -> Unit)? = null
    var onMoveFinished: (() -> Unit)? = null
    var onResizeRequested: ((dw: Int, dh: Int) -> Unit)? = null
    var onResizeFinished: (() -> Unit)? = null
    var onLockToggle: (() -> Unit)? = null
    var onForwardTouch: ((MotionEvent) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val handleSizePx = 26 * density
    private val lockButtonRadiusPx = 16 * density
    private val lockButtonMarginPx = 10 * density

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 40, 40, 40)
        style = Paint.Style.FILL
    }

    private val lockButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val lockGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private enum class Zone { NONE, DRAG, RESIZE, LOCK_BUTTON, CONTENT }

    private var activeZone = Zone.NONE
    private var lastRawX = 0f
    private var lastRawY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        mirrorBitmap?.let { bmp ->
            val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(bmp, null, dst, null)
        }

        canvas.drawRect(1.5f, 1.5f, width - 1.5f, height - 1.5f, borderPaint)

        if (isEditMode) {
            // Top strip: drag to move.
            canvas.drawRect(0f, 0f, width.toFloat(), handleSizePx, handlePaint)
            // Bottom-right triangle: drag to resize.
            val resizePath = Path().apply {
                moveTo(width - handleSizePx, height.toFloat())
                lineTo(width.toFloat(), height.toFloat())
                lineTo(width.toFloat(), height - handleSizePx)
                close()
            }
            canvas.drawPath(resizePath, handlePaint)
        }

        drawLockButton(canvas)
    }

    private fun lockButtonCenterX() = width - lockButtonRadiusPx - lockButtonMarginPx
    private fun lockButtonCenterY() = lockButtonRadiusPx + lockButtonMarginPx

    private fun drawLockButton(canvas: Canvas) {
        val cx = lockButtonCenterX()
        val cy = lockButtonCenterY()
        canvas.drawCircle(cx, cy, lockButtonRadiusPx, lockButtonPaint)

        // Simple padlock glyph: a shackle arc plus a body rectangle.
        val bodyHalf = lockButtonRadiusPx * 0.42f
        val bodyTop = cy - bodyHalf * 0.2f
        canvas.drawRect(
            cx - bodyHalf, bodyTop,
            cx + bodyHalf, bodyTop + bodyHalf * 1.6f,
            lockGlyphPaint
        )
        val shackleRadius = bodyHalf * 0.8f
        canvas.drawArc(
            cx - shackleRadius, bodyTop - shackleRadius * 1.6f,
            cx + shackleRadius, bodyTop + shackleRadius * 0.4f,
            180f, 180f, false, lockGlyphPaint
        )
    }

    private fun detectZone(x: Float, y: Float): Zone {
        val distToLock = hypot((x - lockButtonCenterX()).toDouble(), (y - lockButtonCenterY()).toDouble())
        if (distToLock <= lockButtonRadiusPx + 12) return Zone.LOCK_BUTTON

        if (isEditMode) {
            val inResizeCorner = x >= width - handleSizePx && y >= height - handleSizePx
            return if (inResizeCorner) Zone.RESIZE else Zone.DRAG
        }
        return Zone.CONTENT
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeZone = detectZone(event.x, event.y)
                lastRawX = event.rawX
                lastRawY = event.rawY
                if (activeZone == Zone.CONTENT) onForwardTouch?.invoke(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (activeZone) {
                    Zone.DRAG -> {
                        val dx = (event.rawX - lastRawX).toInt()
                        val dy = (event.rawY - lastRawY).toInt()
                        if (dx != 0 || dy != 0) {
                            onMoveRequested?.invoke(dx, dy)
                            lastRawX = event.rawX
                            lastRawY = event.rawY
                        }
                    }
                    Zone.RESIZE -> {
                        val dw = (event.rawX - lastRawX).toInt()
                        val dh = (event.rawY - lastRawY).toInt()
                        if (dw != 0 || dh != 0) {
                            onResizeRequested?.invoke(dw, dh)
                            lastRawX = event.rawX
                            lastRawY = event.rawY
                        }
                    }
                    Zone.CONTENT -> onForwardTouch?.invoke(event)
                    else -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (activeZone) {
                    Zone.DRAG -> onMoveFinished?.invoke()
                    Zone.RESIZE -> onResizeFinished?.invoke()
                    Zone.LOCK_BUTTON -> onLockToggle?.invoke()
                    Zone.CONTENT -> onForwardTouch?.invoke(event)
                    else -> {}
                }
                activeZone = Zone.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
