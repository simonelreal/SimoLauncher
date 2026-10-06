package it.simone.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AppInfo(val label: String, val key: String)

class Cfg(ctx: Context) {
    val sp = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var cols: Int get() = sp.getInt("cols", 4); set(v) { sp.edit().putInt("cols", v).apply() }
    var rows: Int get() = sp.getInt("rows", 5); set(v) { sp.edit().putInt("rows", v).apply() }
    var iconScale: Int get() = sp.getInt("iconScale", 100); set(v) { sp.edit().putInt("iconScale", v).apply() }
    var drawerCols: Int get() = sp.getInt("drawerCols", 4); set(v) { sp.edit().putInt("drawerCols", v).apply() }
    var pages: Int get() = sp.getInt("pages", 1); set(v) { sp.edit().putInt("pages", v).apply() }
    var labels: Boolean get() = sp.getBoolean("labels", true); set(v) { sp.edit().putBoolean("labels", v).apply() }
    var dtLock: Boolean get() = sp.getBoolean("dtLock", false); set(v) { sp.edit().putBoolean("dtLock", v).apply() }
    var iconPack: String?
        get() = sp.getString("iconPack", null)
        set(v) { if (v == null) sp.edit().remove("iconPack").apply() else sp.edit().putString("iconPack", v).apply() }
    var itemsJson: String get() = sp.getString("items", "[]") ?: "[]"; set(v) { sp.edit().putString("items", v).apply() }
    val rev: Int get() = sp.getInt("rev", 0)
    fun bump() { sp.edit().putInt("rev", rev + 1).apply() }
}

class Item(var id: Long, var type: Int) {
    var page = 0
    var x = 0
    var y = 0
    var sx = 1
    var sy = 1
    var key = ""
    var title = "Cartella"
    var kids: MutableList<String> = mutableListOf()
    var widgetId = -1

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id); o.put("t", type); o.put("p", page); o.put("x", x); o.put("y", y)
        o.put("sx", sx); o.put("sy", sy); o.put("k", key); o.put("title", title); o.put("w", widgetId)
        val a = JSONArray()
        for (k in kids) a.put(k)
        o.put("kids", a)
        return o
    }

    companion object {
        const val APP = 0
        const val FOLDER = 1
        const val WIDGET = 2

        fun fromJson(o: JSONObject): Item {
            val i = Item(o.getLong("id"), o.getInt("t"))
            i.page = o.optInt("p"); i.x = o.optInt("x"); i.y = o.optInt("y")
            i.sx = o.optInt("sx", 1); i.sy = o.optInt("sy", 1)
            i.key = o.optString("k"); i.title = o.optString("title", "Cartella"); i.widgetId = o.optInt("w", -1)
            val a = o.optJSONArray("kids")
            if (a != null) for (n in 0 until a.length()) i.kids.add(a.getString(n))
            return i
        }
    }
}

object Store {
    fun load(cfg: Cfg): MutableList<Item> {
        val list = mutableListOf<Item>()
        try {
            val a = JSONArray(cfg.itemsJson)
            for (n in 0 until a.length()) list.add(Item.fromJson(a.getJSONObject(n)))
        } catch (e: Exception) {
        }
        return list
    }

    fun save(cfg: Cfg, items: List<Item>) {
        val a = JSONArray()
        for (i in items) a.put(i.toJson())
        cfg.itemsJson = a.toString()
    }
}
