package it.simone.launcher

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Griglia con celle: ogni figlio occupa (x, y, larghezza sx, altezza sy). */
class CellLayout(ctx: Context, val cols: Int, val rows: Int) : ViewGroup(ctx) {

    class LP(val x: Int, val y: Int, val sx: Int, val sy: Int) :
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    var page = 0
    var cbSwipeUp: (() -> Unit)? = null
    var cbSwipeDown: (() -> Unit)? = null
    var cbDouble: (() -> Unit)? = null
    var cbPinch: (() -> Unit)? = null
    var cbLong: (() -> Unit)? = null

    private var startSpan = 0f
    private val density = ctx.resources.displayMetrics.density

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            cbDouble?.invoke()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            cbLong?.invoke()
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e1 == null) return false
            val dy = e2.y - e1.y
            if (abs(dy) > 70 * density && abs(vy) > 600 && abs(vy) > abs(vx) * 1.5f) {
                if (dy < 0) cbSwipeUp?.invoke() else cbSwipeDown?.invoke()
                return true
            }
            return false
        }
    })

    private val sd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            startSpan = d.currentSpan
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            if (startSpan > 0 && d.currentSpan < startSpan * 0.75f) cbPinch?.invoke()
        }
    })

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        sd.onTouchEvent(ev)
        if (!sd.isInProgress) gd.onTouchEvent(ev)
        return true
    }

    fun cellAt(px: Float, py: Float): Pair<Int, Int> {
        val cw = width.toFloat() / cols
        val ch = height.toFloat() / rows
        val cx = (px / cw).toInt().coerceIn(0, cols - 1)
        val cy = (py / ch).toInt().coerceIn(0, rows - 1)
        return Pair(cx, cy)
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = MeasureSpec.getSize(hSpec)
        setMeasuredDimension(w, h)
        val cw = w / cols
        val ch = h / rows
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as LP
            c.measure(
                MeasureSpec.makeMeasureSpec(cw * lp.sx, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch * lp.sy, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = (r - l) / cols
        val ch = (b - t) / rows
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as LP
            c.layout(lp.x * cw, lp.y * ch, (lp.x + lp.sx) * cw, (lp.y + lp.sy) * ch)
        }
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LP
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = LP(0, 0, 1, 1)
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LP(0, 0, 1, 1)
}

/** Scorrimento a pagine intere (senza librerie esterne). */
class PageScroller(ctx: Context) : HorizontalScrollView(ctx) {
    var pageW = 0
    var current = 0
    var onPage: ((Int) -> Unit)? = null
    private var startScroll = 0

    private fun maxPage(): Int = ((getChildAt(0) as? ViewGroup)?.childCount ?: 1) - 1

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) startScroll = scrollX
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val r = super.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) snap()
        return r
    }

    override fun fling(velocityX: Int) {}

    private fun snap() {
        if (pageW == 0) return
        val d = scrollX - startScroll
        var p = (startScroll / pageW.toFloat()).roundToInt()
        if (d > pageW * 0.12f) p++ else if (d < -pageW * 0.12f) p--
        goTo(p)
    }

    fun goTo(p: Int, smooth: Boolean = true) {
        val t = p.coerceIn(0, maxPage())
        current = t
        if (smooth) smoothScrollTo(t * pageW, 0) else scrollTo(t * pageW, 0)
        onPage?.invoke(t)
    }
}

/** Contenitore che rileva il tocco lungo senza bloccare il figlio (serve per i widget). */
class LongPressFrame(ctx: Context) : FrameLayout(ctx) {
    var onLong: (() -> Unit)? = null
    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            onLong?.invoke()
        }
    })

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        gd.onTouchEvent(ev)
        return false
    }
}
