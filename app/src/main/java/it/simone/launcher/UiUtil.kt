package it.simone.launcher

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun makeCell(ctx: Context, iconPx: Int, showLabel: Boolean, textSp: Float = 11f): LinearLayout {
    val l = LinearLayout(ctx)
    l.orientation = LinearLayout.VERTICAL
    l.gravity = Gravity.CENTER
    l.setPadding(ctx.dp(2), ctx.dp(4), ctx.dp(2), ctx.dp(4))
    l.addView(ImageView(ctx), LinearLayout.LayoutParams(iconPx, iconPx))
    val tv = TextView(ctx)
    tv.setTextColor(Color.WHITE)
    tv.textSize = textSp
    tv.maxLines = 1
    tv.ellipsize = TextUtils.TruncateAt.END
    tv.gravity = Gravity.CENTER
    tv.setShadowLayer(3f, 1f, 1f, 0x99000000.toInt())
    tv.visibility = if (showLabel) View.VISIBLE else View.GONE
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.topMargin = ctx.dp(3)
    l.addView(tv, lp)
    return l
}

class AppGridAdapter(
    private val ctx: Context,
    private val keys: List<String>,
    private val labels: Map<String, String>,
    private val icons: Icons,
    private val iconPx: Int,
    private val showLabels: Boolean,
    private val textSp: Float
) : BaseAdapter() {
    override fun getCount() = keys.size
    override fun getItem(p: Int): Any = keys[p]
    override fun getItemId(p: Int) = p.toLong()
    override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
        val l = (v as? LinearLayout) ?: makeCell(ctx, iconPx, showLabels, textSp)
        val key = keys[p]
        icons.load(key, l.getChildAt(0) as ImageView, iconPx)
        (l.getChildAt(1) as TextView).text = labels[key] ?: ""
        return l
    }
}
