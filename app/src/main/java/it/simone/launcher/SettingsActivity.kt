package it.simone.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
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

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cfg = Cfg(this)
        title = "Impostazioni launcher"

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(20), dp(20), dp(40))

        seek(col, "Colonne home", 3, 7, cfg.cols) { cfg.cols = it }
        seek(col, "Righe home", 3, 8, cfg.rows) { cfg.rows = it }
        seek(col, "Dimensione icone (%)", 60, 140, cfg.iconScale) { cfg.iconScale = it }
        seek(col, "Colonne cassetto app", 3, 6, cfg.drawerCols) { cfg.drawerCols = it }
        toggle(col, "Mostra i nomi delle app", cfg.labels) { cfg.labels = it }
        toggle(col, "Doppio tap: blocca lo schermo", cfg.dtLock) {
            cfg.dtLock = it
            if (it) askAdmin()
        }
        val pack = Button(this)
        pack.text = "Icon pack: " + (cfg.iconPack ?: "nessuno")
        pack.setOnClickListener { pickPack(pack) }
        col.addView(pack)

        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)
    }

    private fun seek(col: LinearLayout, title: String, min: Int, max: Int, cur: Int, onSet: (Int) -> Unit) {
        val label = TextView(this)
        label.text = "$title: $cur"
        label.setPadding(0, dp(14), 0, 0)
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = cur - min
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

    private fun askAdmin() {
        val admin = ComponentName(this, LockAdmin::class.java)
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isAdminActive(admin)) {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Serve per bloccare lo schermo con il doppio tap.")
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
}
