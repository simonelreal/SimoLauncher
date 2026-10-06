package it.simone.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import kotlin.math.ceil
import kotlin.math.min

class MainActivity : Activity() {

    companion object {
        const val REQ_BIND = 11
        const val REQ_CONFIG = 12
        const val DOCK_COLS = 5
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var cfg: Cfg
    private lateinit var icons: Icons
    private lateinit var host: AppWidgetHost
    private lateinit var mgr: AppWidgetManager

    private lateinit var root: FrameLayout
    private lateinit var scroller: PageScroller
    private lateinit var pagesRow: LinearLayout
    private lateinit var dots: LinearLayout
    private lateinit var dockLayout: CellLayout
    private lateinit var removeZone: TextView
    private lateinit var drawer: FrameLayout
    private lateinit var drawerGrid: GridView
    private lateinit var search: EditText

    private var apps: List<AppInfo> = emptyList()
    private var labelOf: Map<String, String> = emptyMap()
    private var shown: List<String> = emptyList()
    private var items: MutableList<Item> = mutableListOf()
    private val pageLayouts = mutableListOf<CellLayout>()
    private val viewOf = HashMap<Long, View>()

    private var lastRev = -1
    private var lastEdgeSwitch = 0L
    private var dragMenuItem: Item? = null
    private var pendingWidget = -1
    private var dragActive = false
    private var gx = 0f
    private var gy = 0f
    private var gT = 0L
    private var gValid = false

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            loadApps()
            if (i.action == Intent.ACTION_PACKAGE_REMOVED && !i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) pruneMissing()
            renderAll()
            if (drawer.visibility == View.VISIBLE) refreshDrawer()
        }
    }

    // ------------------------------------------------------------ ciclo di vita
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Cfg(this)
        icons = Icons(this, cfg)
        mgr = AppWidgetManager.getInstance(this)
        host = AppWidgetHost(this, 1024)
        buildUi()
        loadApps()
        items = Store.load(cfg)
        if (!cfg.sp.getBoolean("seeded", false)) {
            seedDock()
            cfg.sp.edit().putBoolean("seeded", true).apply()
        }
        icons.reload()
        lastRev = cfg.rev

        val f = IntentFilter()
        f.addAction(Intent.ACTION_PACKAGE_ADDED)
        f.addAction(Intent.ACTION_PACKAGE_REMOVED)
        f.addDataScheme("package")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(pkgReceiver, f)

        renderAll()
    }

    override fun onStart() {
        super.onStart()
        try { host.startListening() } catch (e: Exception) { }
    }

    override fun onStop() {
        try { host.stopListening() } catch (e: Exception) { }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (cfg.rev != lastRev) {
            lastRev = cfg.rev
            items = Store.load(cfg)
            if (!cfg.sp.getBoolean("seeded", false)) {
                seedDock()
                cfg.sp.edit().putBoolean("seeded", true).apply()
            }
            icons.reload()
            renderAll()
            if (drawer.visibility == View.VISIBLE) refreshDrawer()
        }
    }

    // Gesti di scorrimento: funzionano OVUNQUE nella schermata, anche partendo da un'icona
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gx = ev.rawX; gy = ev.rawY; gT = ev.eventTime
                gValid = !dragActive && !onWidget(ev.rawX, ev.rawY)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> gValid = false
            MotionEvent.ACTION_UP -> {
                if (gValid) handleSwipe(ev.rawX - gx, ev.rawY - gy, ev.eventTime - gT)
                gValid = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun onWidget(x: Float, y: Float): Boolean {
        val r = Rect()
        for (i in items) {
            if (i.type != Item.WIDGET) continue
            val v = viewOf[i.id] ?: continue
            if (v.getGlobalVisibleRect(r) && r.contains(x.toInt(), y.toInt())) return true
        }
        return false
    }

    private fun handleSwipe(dx: Float, dy: Float, dt: Long) {
        val d = resources.displayMetrics.density
        if (Math.abs(dy) < cfg.swipeDist * d || Math.abs(dy) < Math.abs(dx) * 1.5f || dt > 800) return
        if (drawer.visibility == View.VISIBLE) {
            if (dy > 0 && !drawerGrid.canScrollVertically(-1)) closeDrawer()
            return
        }
        runAction(if (dy < 0) cfg.gSwipeUp else cfg.gSwipeDown)
    }

    private fun runAction(code: String) {
        when (code) {
            "drawer" -> openDrawer()
            "notif" -> expandPanel()
            "lock" -> lockScreen()
            "settings" -> openSettings()
            "menu" -> emptyMenu()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(pkgReceiver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) {
            closeDrawer()
            scroller.goTo(0)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) closeDrawer()
    }

    // ------------------------------------------------------------ interfaccia
    private fun round(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(26).toFloat()
        return g
    }

    private fun buildUi() {
        root = FrameLayout(this)
        root.fitsSystemWindows = true

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL

        scroller = PageScroller(this)
        scroller.isHorizontalScrollBarEnabled = false
        scroller.overScrollMode = View.OVER_SCROLL_NEVER
        pagesRow = LinearLayout(this)
        pagesRow.orientation = LinearLayout.HORIZONTAL
        scroller.addView(pagesRow, FrameLayout.LayoutParams(WRAP, MATCH))
        scroller.onPage = { updateDots(it) }
        main.addView(scroller, LinearLayout.LayoutParams(MATCH, 0, 1f))

        dots = LinearLayout(this)
        dots.gravity = Gravity.CENTER
        main.addView(dots, LinearLayout.LayoutParams(MATCH, dp(18)))

        dockLayout = CellLayout(this, DOCK_COLS, 1)
        dockLayout.page = -1
        dockLayout.background = round(0x33FFFFFF)
        wire(dockLayout)
        val dlp = LinearLayout.LayoutParams(MATCH, dp(90))
        dlp.setMargins(dp(10), dp(4), dp(10), dp(10))
        main.addView(dockLayout, dlp)

        root.addView(main, FrameLayout.LayoutParams(MATCH, MATCH))

        // cassetto app
        drawer = FrameLayout(this)
        drawer.visibility = View.GONE
        drawer.setBackgroundColor(0xF0101014.toInt())
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        search = EditText(this)
        search.hint = "Cerca app"
        search.setHintTextColor(0xB3FFFFFF.toInt())
        search.setTextColor(Color.WHITE)
        search.isSingleLine = true
        search.background = round(0x33FFFFFF)
        search.setPadding(dp(18), 0, dp(18), 0)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshDrawer() }
        })
        search.setOnEditorActionListener { _, _, _ ->
            shown.firstOrNull()?.let { launch(it) }
            true
        }
        val slp = LinearLayout.LayoutParams(MATCH, dp(44))
        slp.setMargins(dp(12), dp(12), dp(12), dp(6))
        col.addView(search, slp)

        drawerGrid = GridView(this)
        drawerGrid.setSelector(ColorDrawable(Color.TRANSPARENT))
        drawerGrid.verticalSpacing = dp(6)
        drawerGrid.setPadding(dp(8), dp(8), dp(8), dp(8))
        drawerGrid.clipToPadding = false
        drawerGrid.setOnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { launch(it) } }
        drawerGrid.setOnItemLongClickListener { _, v, pos, _ ->
            shown.getOrNull(pos)?.let { drawerMenu(v, it) }
            true
        }
        col.addView(drawerGrid, LinearLayout.LayoutParams(MATCH, 0, 1f))
        drawer.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))

        // zona "rimuovi" durante il trascinamento
        removeZone = TextView(this)
        removeZone.text = "✕  Rimuovi"
        removeZone.setTextColor(Color.WHITE)
        removeZone.textSize = 16f
        removeZone.gravity = Gravity.CENTER
        removeZone.setBackgroundColor(0xCCB00020.toInt())
        removeZone.visibility = View.GONE
        removeZone.setOnDragListener { _, e ->
            when (e.action) {
                DragEvent.ACTION_DROP -> {
                    (e.localState as? Item)?.let { removeItem(it) }
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    dragActive = false
                    removeZone.visibility = View.GONE
                    renderAll()
                    val m = dragMenuItem
                    dragMenuItem = null
                    if (m != null) root.post { showItemMenu(m) }
                }
            }
            true
        }
        root.addView(removeZone, FrameLayout.LayoutParams(MATCH, dp(56), Gravity.TOP))

        setContentView(root)
    }

    private fun wire(cl: CellLayout) {
        cl.cbDouble = { runAction(cfg.gDouble) }
        cl.cbPinch = { runAction(cfg.gPinch) }
        cl.cbLong = { emptyMenu() }
        cl.setOnDragListener { v, e -> onDrag(v as CellLayout, e) }
    }

    // Applica le impostazioni visive (sfondo scuro, dock, indicatore pagine, cassetto)
    private fun applyCfg() {
        root.setBackgroundColor((cfg.dim * 255 / 100) shl 24)
        dots.visibility = if (cfg.showDots) View.VISIBLE else View.GONE
        dockLayout.visibility = if (cfg.showDock) View.VISIBLE else View.GONE
        dockLayout.background = round(((cfg.dockOpacity * 255 / 100) shl 24) or 0xFFFFFF)
        drawer.setBackgroundColor(((cfg.drawerOpacity * 255 / 100) shl 24) or 0x101014)
    }

    private fun buildDots(n: Int) {
        dots.removeAllViews()
        if (n <= 1) return
        val s = dp(7)
        for (i in 0 until n) {
            val d = View(this)
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(Color.WHITE)
            d.background = g
            val lp = LinearLayout.LayoutParams(s, s)
            lp.setMargins(s / 2, 0, s / 2, 0)
            dots.addView(d, lp)
        }
        updateDots(scroller.current)
    }

    private fun updateDots(sel: Int) {
        for (i in 0 until dots.childCount) dots.getChildAt(i).alpha = if (i == sel) 1f else 0.4f
    }

    // ------------------------------------------------------------ dati app
    private fun loadApps() {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(q, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName + "/" + it.activityInfo.name) }
            .filter { !it.key.startsWith("$packageName/") }
            .sortedBy { it.label.lowercase() }
        labelOf = apps.associate { it.key to it.label }
    }

    private fun pruneMissing() {
        val it = items.iterator()
        while (it.hasNext()) {
            val i = it.next()
            if (i.type == Item.APP && !labelOf.containsKey(i.key)) it.remove()
            else if (i.type == Item.FOLDER) {
                i.kids = i.kids.filter { k -> labelOf.containsKey(k) }.toMutableList()
                if (i.kids.isEmpty()) it.remove()
                else if (i.kids.size == 1) { i.type = Item.APP; i.key = i.kids[0]; i.kids.clear() }
            }
        }
        Store.save(cfg, items)
    }

    private fun seedDock() {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("sms:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        var x = 0
        for (intent in intents) {
            val pkg = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName ?: continue
            val key = apps.firstOrNull { it.key.startsWith("$pkg/") }?.key ?: continue
            if (items.any { it.key == key }) continue
            val n = Item(newId(), Item.APP)
            n.key = key; n.page = -1; n.x = x++; n.y = 0
            items.add(n)
        }
        Store.save(cfg, items)
    }

    private fun newId(): Long = (items.maxOfOrNull { it.id } ?: 0L) + 1

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ posizionamento
    private fun isFree(page: Int, x: Int, y: Int, sx: Int, sy: Int, ignore: Item?): Boolean {
        val cols = if (page < 0) DOCK_COLS else cfg.cols
        val rows = if (page < 0) 1 else cfg.rows
        if (x < 0 || y < 0 || x + sx > cols || y + sy > rows) return false
        for (o in items) {
            if (o === ignore || o.page != page) continue
            if (x < o.x + o.sx && o.x < x + sx && y < o.y + o.sy && o.y < y + sy) return false
        }
        return true
    }

    private fun placeFirstFree(it: Item, startPage: Int = 0) {
        it.sx = min(it.sx, cfg.cols); it.sy = min(it.sy, cfg.rows)
        var p = startPage
        while (p < 50) {
            for (y in 0..(cfg.rows - it.sy)) for (x in 0..(cfg.cols - it.sx)) {
                if (isFree(p, x, y, it.sx, it.sy, it)) { it.page = p; it.x = x; it.y = y; return }
            }
            p++
        }
        it.page = 0; it.x = 0; it.y = 0
    }

    private fun normalize() {
        for (i in items.toList()) {
            if (i.page < 0) {
                if (i.x >= DOCK_COLS) placeFirstFree(i)
            } else if (i.x + i.sx > cfg.cols || i.y + i.sy > cfg.rows) {
                placeFirstFree(i)
            }
        }
    }

    // ------------------------------------------------------------ disegno home
    private fun renderAll() {
        if (scroller.width == 0) { scroller.post { renderAll() }; return }
        applyCfg()
        normalize()
        val maxPage = items.maxOfOrNull { it.page } ?: 0
        val n = maxOf(1, cfg.pages, maxPage + 1)
        val w = scroller.width

        pagesRow.removeAllViews(); pageLayouts.clear(); viewOf.clear(); dockLayout.removeAllViews()
        for (p in 0 until n) {
            val cl = CellLayout(this, cfg.cols, cfg.rows)
            cl.page = p
            wire(cl)
            pagesRow.addView(cl, LinearLayout.LayoutParams(w, MATCH))
            pageLayouts.add(cl)
        }
        scroller.pageW = w
        (dockLayout.layoutParams as LinearLayout.LayoutParams).height = icons.px + dp(if (cfg.labels) 34 else 18)
        dockLayout.requestLayout()

        for (i in items) {
            val v = buildView(i) ?: continue
            val lay = if (i.page < 0) dockLayout else pageLayouts.getOrNull(i.page) ?: continue
            viewOf[i.id] = v
            lay.addView(v, CellLayout.LP(i.x, i.y, i.sx, i.sy))
        }
        buildDots(n)
        scroller.post { scroller.goTo(min(scroller.current, n - 1), false) }
    }

    private fun buildView(i: Item): View? = when (i.type) {
        Item.APP -> buildAppView(i)
        Item.FOLDER -> buildFolderView(i)
        else -> buildWidgetView(i)
    }

    private fun buildAppView(i: Item): View? {
        val label = labelOf[i.key] ?: return null
        val c = makeCell(this, icons.px, cfg.labels, cfg.labelSize.toFloat())
        icons.load(i.key, c.getChildAt(0) as ImageView)
        (c.getChildAt(1) as TextView).text = label
        c.setOnClickListener { launch(i.key) }
        c.setOnLongClickListener { startDrag(c, i); true }
        return c
    }

    private fun buildFolderView(i: Item): View {
        val s = icons.px
        val pad = s / 12
        val sub = (s - 2 * pad) / 2
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, pad, pad, pad)
        val g = GradientDrawable()
        g.setColor(0x55FFFFFF)
        g.cornerRadius = s / 4f
        box.background = g
        for (r in 0..1) {
            val row = LinearLayout(this)
            for (cc in 0..1) {
                val iv = ImageView(this)
                i.kids.getOrNull(r * 2 + cc)?.let { icons.load(it, iv, sub) }
                row.addView(iv, LinearLayout.LayoutParams(sub, sub))
            }
            box.addView(row)
        }
        val c = makeCell(this, s, cfg.labels, cfg.labelSize.toFloat())
        c.removeViewAt(0)
        c.addView(box, 0, LinearLayout.LayoutParams(s, s))
        (c.getChildAt(1) as TextView).text = i.title
        c.setOnClickListener { openFolder(i) }
        c.setOnLongClickListener { startDrag(c, i); true }
        return c
    }

    private fun buildWidgetView(i: Item): View? {
        val info = mgr.getAppWidgetInfo(i.widgetId) ?: return null
        val hv = host.createView(this, i.widgetId, info)
        val frame = LongPressFrame(this)
        frame.addView(hv, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.onLong = { startDrag(frame, i) }
        frame.post {
            val w = (frame.width / resources.displayMetrics.density).toInt()
            val h = (frame.height / resources.displayMetrics.density).toInt()
            hv.updateAppWidgetSize(null, w, h, w, h)
        }
        return frame
    }

    // ------------------------------------------------------------ azioni
    private fun launch(key: String) {
        closeDrawer()
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(key.substringBefore('/'), key.substringAfter('/'))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { startActivity(i) } catch (e: Exception) { toast("Impossibile aprire l'app") }
    }

    private fun appInfo(key: String) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + key.substringBefore('/'))))
    }

    private fun uninstall(key: String) {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + key.substringBefore('/'))))
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun expandPanel() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        } catch (e: Exception) {
        }
    }

    private fun lockScreen() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, LockAdmin::class.java)
        if (dpm.isAdminActive(admin)) dpm.lockNow()
        else startActivity(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Serve per bloccare lo schermo con il doppio tap.")
        )
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
    }

    // ------------------------------------------------------------ cassetto
    private fun refreshDrawer() {
        val q = search.text.toString().trim().lowercase()
        val hid = cfg.hidden
        val visible = apps.filter { !hid.contains(it.key) }
        shown = if (q.isEmpty()) visible.map { it.key } else visible.filter { it.label.lowercase().contains(q) }.map { it.key }
        drawerGrid.numColumns = cfg.drawerCols
        drawerGrid.adapter = AppGridAdapter(
            this, shown, labelOf, icons, icons.pxFor(cfg.drawerIconScale), cfg.drawerLabels, cfg.labelSize.toFloat()
        )
    }

    private fun openDrawer() {
        if (drawer.visibility == View.VISIBLE) return
        search.setText("")
        refreshDrawer()
        drawer.visibility = View.VISIBLE
        drawer.translationY = root.height.toFloat()
        drawer.animate().translationY(0f).setDuration(180).start()
        if (cfg.drawerKeyboard) {
            search.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, 0)
        }
    }

    private fun closeDrawer() {
        if (drawer.visibility != View.VISIBLE) return
        hideKeyboard()
        drawer.animate().translationY(root.height.toFloat()).setDuration(150)
            .withEndAction { drawer.visibility = View.GONE }.start()
    }

    private fun drawerMenu(anchor: View, key: String) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Aggiungi alla home")
        m.menu.add(0, 2, 1, "Info app")
        m.menu.add(0, 3, 2, "Disinstalla")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> addToHome(key)
                2 -> appInfo(key)
                3 -> uninstall(key)
            }
            true
        }
        m.show()
    }

    private fun addToHome(key: String) {
        val n = Item(newId(), Item.APP)
        n.key = key
        placeFirstFree(n, scroller.current)
        items.add(n)
        Store.save(cfg, items)
        closeDrawer()
        renderAll()
    }

    // ------------------------------------------------------------ trascinamento
    private fun startDrag(v: View, item: Item) {
        if (cfg.lockLayout) { toast("Layout bloccato (si sblocca dalle impostazioni)"); return }
        dragActive = true
        removeZone.visibility = View.VISIBLE
        v.startDragAndDrop(ClipData.newPlainText("item", item.id.toString()), View.DragShadowBuilder(v), item, 0)
        v.visibility = View.INVISIBLE
    }

    private fun onDrag(cl: CellLayout, e: DragEvent): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> edgeScroll(cl, e)
            DragEvent.ACTION_DROP -> handleDrop(cl, e)
        }
        return true
    }

    private fun edgeScroll(cl: CellLayout, e: DragEvent) {
        if (cl.page < 0) return
        val now = SystemClock.uptimeMillis()
        if (now - lastEdgeSwitch < 700) return
        val edge = 40 * resources.displayMetrics.density
        if (e.x < edge && scroller.current > 0) {
            scroller.goTo(scroller.current - 1); lastEdgeSwitch = now
        } else if (e.x > cl.width - edge && scroller.current < pageLayouts.size - 1) {
            scroller.goTo(scroller.current + 1); lastEdgeSwitch = now
        }
    }

    private fun handleDrop(cl: CellLayout, e: DragEvent) {
        val item = e.localState as? Item ?: return
        val (fx, fy) = cl.cellAt(e.x, e.y)
        val page = cl.page

        // rilasciato dove era: mostra il menu
        if (page == item.page && fx >= item.x && fx < item.x + item.sx && fy >= item.y && fy < item.y + item.sy) {
            dragMenuItem = item
            return
        }
        val cx = min(fx, cl.cols - item.sx).coerceAtLeast(0)
        val cy = min(fy, cl.rows - item.sy).coerceAtLeast(0)
        val occ = items.firstOrNull {
            it !== item && it.page == page && fx >= it.x && fx < it.x + it.sx && fy >= it.y && fy < it.y + it.sy
        }
        if (item.type == Item.APP && occ != null && occ.type == Item.APP) {
            occ.kids = mutableListOf(occ.key, item.key)
            occ.key = ""; occ.type = Item.FOLDER; occ.title = "Cartella"
            items.remove(item)
        } else if (item.type == Item.APP && occ != null && occ.type == Item.FOLDER) {
            if (!occ.kids.contains(item.key)) occ.kids.add(item.key)
            items.remove(item)
        } else if (isFree(page, cx, cy, item.sx, item.sy, item)) {
            item.page = page; item.x = cx; item.y = cy
        } else {
            toast("Posizione occupata")
            return
        }
        Store.save(cfg, items)
    }

    private fun removeItem(i: Item) {
        if (i.type == Item.WIDGET) host.deleteAppWidgetId(i.widgetId)
        items.remove(i)
        Store.save(cfg, items)
    }

    // ------------------------------------------------------------ menu e cartelle
    private fun showItemMenu(item: Item) {
        val anchor = viewOf[item.id] ?: return
        val m = PopupMenu(this, anchor)
        when (item.type) {
            Item.APP -> {
                m.menu.add(0, 1, 0, "Info app"); m.menu.add(0, 2, 1, "Disinstalla"); m.menu.add(0, 3, 2, "Rimuovi")
            }
            Item.FOLDER -> {
                m.menu.add(0, 4, 0, "Rinomina"); m.menu.add(0, 3, 1, "Elimina cartella")
            }
            else -> m.menu.add(0, 3, 0, "Rimuovi widget")
        }
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> appInfo(item.key)
                2 -> uninstall(item.key)
                3 -> { removeItem(item); renderAll() }
                4 -> rename(item)
            }
            true
        }
        m.show()
    }

    private fun rename(item: Item) {
        val et = EditText(this)
        et.setText(item.title)
        AlertDialog.Builder(this).setTitle("Nome cartella").setView(et)
            .setPositiveButton("OK") { _, _ ->
                item.title = et.text.toString().ifBlank { "Cartella" }
                Store.save(cfg, items); renderAll()
            }.setNegativeButton("Annulla", null).show()
    }

    private fun openFolder(item: Item) {
        val keys = item.kids.filter { labelOf.containsKey(it) }
        val g = GridView(this)
        g.numColumns = 3
        g.setPadding(dp(8), dp(8), dp(8), dp(8))
        g.adapter = AppGridAdapter(this, keys, labelOf, icons, icons.px, true, cfg.labelSize.toFloat())
        val dlg = AlertDialog.Builder(this).setTitle(item.title).setView(g)
            .setNeutralButton("Rinomina") { _, _ -> rename(item) }.create()
        g.setOnItemClickListener { _, _, pos, _ -> dlg.dismiss(); launch(keys[pos]) }
        g.setOnItemLongClickListener { _, _, pos, _ -> folderChildMenu(item, keys[pos], dlg); true }
        dlg.show()
    }

    private fun folderChildMenu(folder: Item, key: String, parent: AlertDialog) {
        val opts = arrayOf<CharSequence>("Sposta sulla home", "Info app")
        AlertDialog.Builder(this).setItems(opts) { _, w ->
            if (w == 1) { appInfo(key); return@setItems }
            folder.kids.remove(key)
            if (folder.kids.size == 1) { folder.type = Item.APP; folder.key = folder.kids[0]; folder.kids.clear() }
            else if (folder.kids.isEmpty()) items.remove(folder)
            val n = Item(newId(), Item.APP)
            n.key = key
            placeFirstFree(n, scroller.current)
            items.add(n)
            Store.save(cfg, items)
            parent.dismiss()
            renderAll()
        }.show()
    }

    private fun emptyMenu() {
        val opts = arrayOf<CharSequence>("Aggiungi widget", "Aggiungi pagina", "Rimuovi ultima pagina", "Cambia sfondo", "Impostazioni")
        AlertDialog.Builder(this).setItems(opts) { _, w ->
            when (w) {
                0 -> pickWidget()
                1 -> { cfg.pages = pageLayouts.size + 1; renderAll() }
                2 -> removeLastPage()
                3 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Sfondo"))
                4 -> openSettings()
            }
        }.show()
    }

    private fun removeLastPage() {
        val last = pageLayouts.size - 1
        if (last == 0) toast("Serve almeno una pagina")
        else if (items.any { it.page == last }) toast("L'ultima pagina non è vuota")
        else { cfg.pages = last; renderAll() }
    }

    // ------------------------------------------------------------ widget
    private fun pickWidget() {
        val pm = packageManager
        val list = mgr.installedProviders.sortedBy { it.loadLabel(pm).toString().lowercase() }
        val names = list.map { p ->
            val app = try { pm.getApplicationLabel(pm.getApplicationInfo(p.provider.packageName, 0)).toString() } catch (e: Exception) { "" }
            "${p.loadLabel(pm)}  ·  $app"
        }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this).setTitle("Widget").setItems(names) { _, idx ->
            val p = list[idx]
            val id = host.allocateAppWidgetId()
            pendingWidget = id
            if (mgr.bindAppWidgetIdIfAllowed(id, p.provider)) {
                configureOrAdd(id)
            } else {
                val bind = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, p.provider)
                startActivityForResult(bind, REQ_BIND)
            }
        }.show()
    }

    private fun configureOrAdd(id: Int) {
        val info = mgr.getAppWidgetInfo(id)
        if (info?.configure != null) {
            try {
                host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null)
                return
            } catch (e: Exception) {
            }
        }
        addWidget(id)
        pendingWidget = -1
    }

    private fun addWidget(id: Int) {
        val info = mgr.getAppWidgetInfo(id)
        if (info == null) { host.deleteAppWidgetId(id); return }
        val d = resources.displayMetrics.density
        val cellW = scroller.width / cfg.cols / d
        val cellH = scroller.height / cfg.rows / d
        val n = Item(newId(), Item.WIDGET)
        n.widgetId = id
        n.sx = ceil(info.minWidth / cellW).toInt().coerceIn(1, cfg.cols)
        n.sy = ceil(info.minHeight / cellH).toInt().coerceIn(1, cfg.rows)
        placeFirstFree(n, scroller.current)
        items.add(n)
        Store.save(cfg, items)
        renderAll()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val id = pendingWidget
        if (id < 0) return
        if (req == REQ_BIND) {
            if (res == RESULT_OK) configureOrAdd(id) else { host.deleteAppWidgetId(id); pendingWidget = -1 }
        } else if (req == REQ_CONFIG) {
            if (res == RESULT_OK) addWidget(id) else host.deleteAppWidgetId(id)
            pendingWidget = -1
        }
    }
}
