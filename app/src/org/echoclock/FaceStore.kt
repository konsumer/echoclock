package org.echoclock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** A clock face: bundled in assets or dropped into /sdcard/EchoClock/faces. */
data class FaceInfo(val id: String, val name: String, val bundled: Boolean)

/** One entry of a face.json `settings` schema (DESIGN §7.1). */
data class FaceSetting(
    val key: String,
    val label: String,
    val type: String,      // bool | int | enum | color | text | list
    val default: Any?,     // Boolean | Int | String | JSONArray | null
    val min: Int,
    val max: Int,
    val options: List<String>
)

/**
 * Enumerates/loads clock faces + per-face config (DESIGN §2.4, §7.1).
 * User faces (`/sdcard/EchoClock/faces/<id>`) override bundled ones with the same id.
 */
object FaceStore {

    private val COLOR_RE = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    fun bundledIds(context: Context): List<String> {
        val out = ArrayList<String>()
        try {
            for (id in context.assets.list("faces").orEmpty()) {
                if (id.isNullOrEmpty()) continue
                val files = context.assets.list("faces/$id").orEmpty()
                if (files.contains("index.html")) out.add(id)
            }
        } catch (t: Throwable) {
            Util.warn("cannot list bundled faces", t)
        }
        return out.sorted()
    }

    fun userIds(context: Context): List<String> {
        val dir = File(Config.root(context), "faces")
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory && File(it, "index.html").isFile }
            .map { it.name }
            .sorted()
    }

    /** Ordered face list (DESIGN §7.1): user faces first, then bundled; by name. */
    fun list(context: Context): List<FaceInfo> {
        val user = userIds(context).map { FaceInfo(it, nameOf(context, it) ?: it, false) }
        val bundled = bundledIds(context)
            .filter { id -> user.none { it.id == id } }
            .map { FaceInfo(it, nameOf(context, it) ?: it, true) }
        val byName = compareBy<FaceInfo>({ it.name.lowercase(Locale.US) }, { it.id })
        return user.sortedWith(byName) + bundled.sortedWith(byName)
    }

    fun listJson(context: Context): String {
        val arr = JSONArray()
        for (f in list(context)) {
            arr.put(JSONObject().put("id", f.id).put("name", f.name).put("bundled", f.bundled))
        }
        return arr.toString()
    }

    fun nameOf(context: Context, id: String): String? =
        faceJson(context, id)?.optString("name")?.trim()?.ifEmpty { null }

    fun faceJson(context: Context, id: String): JSONObject? {
        val user = File(Config.root(context), "faces/$id/face.json")
        if (File(user.parentFile, "index.html").isFile && user.isFile) {
            try {
                return JSONObject(user.readText())
            } catch (t: Throwable) {
                Util.warn("bad face.json for $id", t)
            }
        }
        return try {
            context.assets.open("faces/$id/face.json").use { JSONObject(it.readBytes().decodeToString()) }
        } catch (t: Throwable) {
            null
        }
    }

    fun exists(context: Context, id: String): Boolean {
        if (id.isEmpty()) return false
        if (File(Config.root(context), "faces/$id/index.html").isFile) return true
        return try {
            context.assets.list("faces/$id").orEmpty().contains("index.html")
        } catch (t: Throwable) {
            false
        }
    }

    /** WebView URL for the face entry point, user dir first, then bundled assets. */
    fun indexUrl(context: Context, id: String): String? {
        val user = File(Config.root(context), "faces/$id/index.html")
        if (user.isFile) return "file://${user.absolutePath}"
        return try {
            if (context.assets.list("faces/$id").orEmpty().contains("index.html")) {
                "file:///android_asset/faces/$id/index.html"
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ---- v2 per-face settings (DESIGN §7.1) -----------------------------------

    /** Parsed `settings` schema of a face (empty when the face declares none). */
    fun settingsSchema(context: Context, id: String): List<FaceSetting> =
        parseSettings(faceJson(context, id)?.optJSONArray("settings"))

    fun parseSettings(arr: JSONArray?): List<FaceSetting> {
        if (arr == null) return emptyList()
        val out = ArrayList<FaceSetting>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("key", "").trim()
            if (key.isEmpty()) continue
            var type = o.optString("type", "text").trim().lowercase(Locale.US)
            if (type != "bool" && type != "int" && type != "enum" && type != "color" &&
                type != "text" && type != "list"
            ) {
                type = "text"
            }
            val lo = minOf(o.optInt("min", 0), o.optInt("max", 100))
            val hi = maxOf(o.optInt("min", 0), o.optInt("max", 100))
            val options = ArrayList<String>()
            val oa = o.optJSONArray("options")
            if (oa != null) {
                for (j in 0 until oa.length()) {
                    val s = oa.optString(j, "").trim()
                    if (s.isNotEmpty()) options.add(s)
                }
            }
            val def: Any? = when (type) {
                "bool" -> o.optBoolean("default", false)
                "int" -> o.optInt("default", lo).coerceIn(lo, hi)
                "enum" -> o.optString("default", "").trim().ifEmpty { options.firstOrNull() ?: "" }
                "color" -> o.optString("default", "#ffffff").trim().ifEmpty { "#ffffff" }
                "list" -> when (val d = o.opt("default")) {
                    is JSONArray -> d
                    is String -> splitList(d)
                    else -> JSONArray()
                }
                else -> o.optString("default", "")
            }
            val label = o.optString("label", "").trim().ifEmpty { key }
            out.add(FaceSetting(key, label, type, def, lo, hi, options))
        }
        return out
    }

    /** Newline-separated string list -> JSON array (DESIGN §7.1 `list` type). */
    fun splitList(text: String): JSONArray {
        val arr = JSONArray()
        for (line in text.split('\n')) {
            val t = line.trim()
            if (t.isNotEmpty()) arr.put(t)
        }
        return arr
    }

    /** `window.EC.settings()` payload: the schema plus each setting's current value. */
    fun settingsJson(context: Context, id: String): String {
        val arr = JSONArray()
        val current = faceConfig(context, id)
        for (s in settingsSchema(context, id)) {
            val o = JSONObject()
            o.put("key", s.key)
            o.put("label", s.label)
            o.put("type", s.type)
            o.put("default", s.default ?: JSONObject.NULL)
            if (s.type == "int") {
                o.put("min", s.min)
                o.put("max", s.max)
            }
            if (s.type == "enum") o.put("options", JSONArray(s.options))
            o.put("value", if (current.has(s.key)) current.get(s.key) else (s.default ?: JSONObject.NULL))
            arr.put(o)
        }
        return arr.toString()
    }

    /**
     * Per-face config pushed to the face: schema defaults overridden by the stored
     * values from config.json `"faces": { "<id>": {...} }` (DESIGN §7.1).
     */
    fun faceConfig(context: Context, id: String): JSONObject {
        val out = JSONObject()
        for (s in settingsSchema(context, id)) {
            if (s.default != null) out.put(s.key, s.default)
        }
        val stored = Config.load(context).optJSONObject("faces")?.optJSONObject(id)
        if (stored != null) {
            val keys = stored.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out.put(k, stored.get(k))
            }
        }
        return out
    }

    /**
     * Coerces a raw value (usually a String from the settings UI or `EC.setSetting`)
     * to the type declared in the schema. Returns null for unknown/invalid input.
     */
    fun coerceSetting(setting: FaceSetting, raw: Any?): Any? = when (setting.type) {
        "bool" -> when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            else -> when (raw?.toString()?.trim()?.lowercase(Locale.US)) {
                "true", "1", "on", "yes" -> true
                "false", "0", "off", "no" -> false
                else -> null
            }
        }
        "int" -> {
            val n = when (raw) {
                is Number -> raw.toInt()
                else -> raw?.toString()?.trim()?.toDoubleOrNull()?.toInt()
            }
            n?.coerceIn(setting.min, setting.max)
        }
        "enum" -> {
            val s = raw?.toString()?.trim().orEmpty()
            when {
                s.isEmpty() -> null
                setting.options.isEmpty() -> s
                else -> setting.options.firstOrNull { it.equals(s, ignoreCase = true) }
            }
        }
        "color" -> {
            val s = raw?.toString()?.trim().orEmpty()
            if (COLOR_RE.matches(s)) s else null
        }
        "list" -> when (raw) {
            is JSONArray -> raw
            is Collection<*> -> JSONArray(raw)
            else -> splitList(raw?.toString().orEmpty())
        }
        else -> raw?.toString()
    }

    /**
     * Persists settings into config.json `"faces": { "<id>": {...} }` (DESIGN §7.1).
     * Missing keys are left untouched; other faces are preserved.
     */
    fun writeFaceSettings(context: Context, id: String, values: JSONObject): Boolean =
        Config.putFaceSettings(context, id, values)

    fun writeFaceSetting(context: Context, id: String, key: String, value: Any): Boolean =
        Config.putFaceSetting(context, id, key, value)

    fun selected(context: Context): String {
        val pref = Config.prefs(context).getString("face", null)
        if (!pref.isNullOrEmpty() && exists(context, pref)) return pref
        val def = Config.load(context).optString("defaultFace", "digital").trim().lowercase(Locale.US)
        if (exists(context, def)) return def
        return list(context).firstOrNull()?.id ?: def.ifEmpty { "digital" }
    }

    fun setSelected(context: Context, id: String) {
        Config.prefs(context).edit().putString("face", id).apply()
    }
}
