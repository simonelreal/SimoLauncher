package it.simone.launcher

import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Xml
import android.widget.ImageView
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.Executors

/** Icone caricate al volo e tenute in una cache piccola (max 6 MB). */
class Icons(private val ctx: Context, private val cfg: Cfg) {
    private val pm = ctx.packageManager
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var packRes: Resources? = null
    @Volatile private var packPkg: String? = null
    @Volatile private var map: HashMap<String, String> = HashMap()

    fun pxFor(percent: Int): Int =
        (46 * ctx.resources.displayMetrics.density * percent / 100f).toInt().coerceAtLeast(16)

    val px: Int
        get() = pxFor(cfg.iconScale)

    fun reload() {
        cache.evictAll()
        loadPack()
    }

    fun load(key: String, iv: ImageView, size: Int = px) {
        val ck = "$key@$size"
        iv.tag = ck
        val hit = cache.get(ck)
        if (hit != null) {
            iv.setImageBitmap(hit)
            return
        }
        iv.setImageDrawable(null)
        exec.execute {
            val b = render(key, size)
            cache.put(ck, b)
            ui.post { if (iv.tag == ck) iv.setImageBitmap(b) }
        }
    }

    private fun render(key: String, size: Int): Bitmap {
        var d: Drawable? = null
        try {
            d = packDrawable(key)
            if (d == null) {
                val i = key.indexOf('/')
                d = pm.getActivityIcon(ComponentName(key.substring(0, i), key.substring(i + 1)))
            }
        } catch (e: Exception) {
        }
        if (d == null) d = ctx.getDrawable(android.R.drawable.sym_def_app_icon)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d!!.setBounds(0, 0, size, size)
        d.draw(c)
        return b
    }

    private fun packDrawable(key: String): Drawable? {
        val res = packRes ?: return null
        val name = map[key] ?: return null
        val id = res.getIdentifier(name, "drawable", packPkg)
        if (id == 0) return null
        return res.getDrawable(id, null)
    }

    private fun loadPack() {
        val pkg = cfg.iconPack
        packRes = null; packPkg = null; map = HashMap()
        if (pkg == null) return
        try {
            val res = pm.getResourcesForApplication(pkg)
            val m = HashMap<String, String>()
            val xid = res.getIdentifier("appfilter", "xml", pkg)
            if (xid != 0) {
                parse(res.getXml(xid), m)
            } else {
                val ps = pm.getResourcesForApplication(pkg).assets.open("appfilter.xml")
                val p = Xml.newPullParser()
                p.setInput(ps, null)
                parse(p, m)
            }
            packRes = res; packPkg = pkg; map = m
        } catch (e: Exception) {
        }
    }

    private fun parse(p: XmlPullParser, out: HashMap<String, String>) {
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "item") {
                val comp = p.getAttributeValue(null, "component")
                val dr = p.getAttributeValue(null, "drawable")
                if (comp != null && dr != null && comp.startsWith("ComponentInfo{") && comp.endsWith("}")) {
                    out[comp.substring(14, comp.length - 1)] = dr
                }
            }
            ev = p.next()
        }
    }
}
