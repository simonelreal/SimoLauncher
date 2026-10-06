package it.simone.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.appwidget.AppWidgetHost
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var cfg: Cfg

    private val actionCodes = arrayOf("none", "drawer", "notif", "lock", "settings", "menu")
    private val actionNames = arrayOf<CharSequence>(
        "Nessuna", "Cassetto app", "Tendina notifiche", "Blocca schermo",
        "Impostazioni launcher", "Menu home (widget, pagine, sfondo)"
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cfg = Cfg(this)
        title = "Impostazioni launcher"

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(10), dp(20), dp(40))

        section(col, "Home")
        seek(col, "Colonne", 3, 7, cfg.cols) { cfg.cols = it }
        seek(col, "Righe", 3, 8, cfg.rows) { cfg.rows = it }
        seek(col, "Dimensione icone (%)", 60, 140, cfg.iconScale) { cfg.iconScale = it }
        seek(col, "Dimensione testo nomi", 8, 16, cfg.labelSize) { cfg.labelSize = it }
        toggle(col, "Mostra i nomi delle app", cfg.labels) { cfg.labels = it }
        toggle(col, "Mostra i pallini delle pagine", cfg.showDots) { cfg.showDots = it }
        seek(col, "Oscura lo sfondo (%)", 0, 80, cfg.dim) { cfg.dim = it }
        toggle(col, "Blocca il layout (impedisce di spostare le icone)", cfg.lockLayout) { cfg.lockLayout = it }

        section(col, "Dock")
        toggle(col, "Mostra il dock", cfg.showDock) { cfg.showDock = it }
        seek(col, "Opacità sfondo dock (%)", 0, 100, cfg.dockOpacity) { cfg.dockOpacity = it }

        section(col, "Cassetto app")
        seek(col, "Colonne", 3, 6, cfg.drawerCols) { cfg.drawerCols = it }
        seek(col, "Dimensione icone (%)", 60, 140, cfg.drawerIconScale) { cfg.drawerIconScale = it }
        toggle(col, "Mostra i nomi delle app", cfg.drawerLabels) { cfg.drawerLabels = it }
        seek(col, "Opacità sfondo (%)", 50, 100, cfg.drawerOpacity) { cfg.drawerOpacity = it }
        toggle(col, "Apri la tastiera aprendo il cassetto", cfg.drawerKeyboard) { cfg.drawerKeyboard = it }
        button(col, "Nascondi app dal cassetto…") { pickHidden() }

        section(col, "Gesti")
        gesture(col, "Swipe su", { cfg.gSwipeUp }) { cfg.gSwipeUp = it }
        gesture(col, "Swipe giù", { cfg.gSwipeDown }) { cfg.gSwipeDown = it }
        gesture(col, "Doppio tap", { cfg.gDouble }) { cfg.gDouble = it }
        gesture(col, "Pizzico", { cfg.gPinch }) { cfg.gPinch = it }
        seek(col, "Lunghezza minima dello swipe (dp)", 40, 160, cfg.swipeDist) { cfg.swipeDist = it }

        section(col, "Aspetto")
        val pack = button(col, "Icon pack: " + (cfg.iconPack ?: "nessuno")) { }
        pack.setOnClickListener { pickPack(pack) }
        button(col, "Cambia sfondo") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Sfondo"))
        }

        section(col, "Altro")
        button(col, "Ripristina la home…") { confirmReset() }

        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)
    }

    // ---------------------------------------------------------- componenti
    private fun section(col: LinearLayout, text: String) {
        val t = TextView(this)
        t.text = text
        t.textSize = 18f
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(0, dp(26), 0, dp(2))
        col.addView(t)
    }

    private fun button(col: LinearLayout, text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        col.addView(b)
        return b
    }

    private fun seek(col: LinearLayout, title: String, min: Int, max: Int, cur: Int, onSet: (Int) -> Unit) {
        val label = TextView(this)
        label.text = "$title: $cur"
        label.setPadding(0, dp(12), 0, 0)
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = (cur - min).coerceIn(0, max - min)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { label.text = "$title: ${p + min}" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                onSet((s?.progress ?: 0) + min)
                cfg.bump()
            }
        })
        col.addView(label)
        col.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun toggle(col: LinearLayout, text: String, cur: Boolean, onSet: (Boolean) -> Unit) {
        val cb = CheckBox(this)
        cb.text = text
        cb.isChecked = cur
        cb.setOnCheckedChangeListener { _, v -> onSet(v); cfg.bump() }
        col.addView(cb)
    }

    private fun gesture(col: LinearLayout, title: String, get: () -> String, set: (String) -> Unit) {
        val btn = Button(this)
        btn.isAllCaps = false
        fun refresh() {
            btn.text = title + ": " + actionNames[actionCodes.indexOf(get()).coerceAtLeast(0)]
        }
        refresh()
        btn.setOnClickListener {
            AlertDialog.Builder(this).setTitle(title).setItems(actionNames) { _, i ->
                set(actionCodes[i])
                cfg.bump()
                refresh()
                if (actionCodes[i] == "lock") askAdmin()
            }.show()
        }
        col.addView(btn)
    }

    // ---------------------------------------------------------- azioni
    private fun askAdmin() {
        val admin = ComponentName(this, LockAdmin::class.java)
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isAdminActive(admin)) {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Serve per bloccare lo schermo con un gesto.")
            )
        }
    }

    private fun pickPack(btn: Button) {
        val pm = packageManager
        val found = LinkedHashMap<String, String>()
        for (a in listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")) {
            for (ri in pm.queryIntentActivities(Intent(a), 0)) {
                found[ri.activityInfo.packageName] = ri.loadLabel(pm).toString()
            }
        }
        if (found.isEmpty()) {
            Toast.makeText(this, "Nessun icon pack installato", Toast.LENGTH_LONG).show()
            return
        }
        val pk = found.keys.toList()
        val names = (listOf("Nessuno (icone di sistema)") + found.values).toTypedArray<CharSequence>()
        AlertDialog.Builder(this).setTitle("Icon pack").setItems(names) { _, i ->
            cfg.iconPack = if (i == 0) null else pk[i - 1]
            cfg.bump()
            btn.text = "Icon pack: " + (cfg.iconPack ?: "nessuno")
        }.show()
    }

    private fun pickHidden() {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(q, 0)
            .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName + "/" + it.activityInfo.name) }
            .filter { !it.second.startsWith("$packageName/") }
            .sortedBy { it.first.lowercase() }
        val hidden = cfg.hidden.toMutableSet()
        val names = list.map { it.first }.toTypedArray<CharSequence>()
        val checked = BooleanArray(list.size) { hidden.contains(list[it].second) }
        AlertDialog.Builder(this).setTitle("Nascondi dal cassetto")
            .setMultiChoiceItems(names, checked) { _, i, on ->
                if (on) hidden.add(list[i].second) else hidden.remove(list[i].second)
            }
            .setPositiveButton("OK") { _, _ -> cfg.hidden = hidden; cfg.bump() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this).setTitle("Ripristinare la home?")
            .setMessage("Icone, cartelle e widget verranno rimossi e il dock tornerà com'era all'inizio.")
            .setPositiveButton("Ripristina") { _, _ ->
                try { AppWidgetHost(this, 1024).deleteHost() } catch (e: Exception) { }
                cfg.itemsJson = "[]"
                cfg.pages = 1
                cfg.sp.edit().putBoolean("seeded", false).apply()
                cfg.bump()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}
