package org.echoclock

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import org.json.JSONObject
import java.io.File

/**
 * On-device layout + defaults (docs/DESIGN.md §2.3, §4, §5).
 *
 * Primary root is `/sdcard/EchoClock`; when that is not writable (MANAGE_EXTERNAL_STORAGE
 * not granted yet) we fall back to the app-specific external dir. Default `config.json`
 * is written on first run.
 */
object Config {

    const val PREFS = "echoclock"

    private const val DIR_NAME = "EchoClock"

    val DEFAULT_CONFIG_JSON = """
{
  "defaultFace": "digital",
  "brightness": 0.7,
  "weather": { "enabled": true, "lat": 37.77, "lon": -122.42, "refreshMinutes": 30 },
  "calendar": { "enabled": true, "maxEvents": 4, "horizonHours": 48 },
  "faces": {
    "digital": { "format24h": false, "showSeconds": true, "showDate": true, "accent": "#4fd6ff" },
    "world": { "zones": ["America/Los_Angeles", "America/New_York", "Europe/London", "Asia/Tokyo"] },
    "pomodoro": { "focusMinutes": 25, "breakMinutes": 5 }
  }
}
""".trim()

    @Volatile
    private var cachedRoot: File? = null

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** `/sdcard/EchoClock` when writable, else `<externalFilesDir>/EchoClock`. */
    fun root(context: Context): File {
        cachedRoot?.let { return it }
        synchronized(this) {
            cachedRoot?.let { return it }
            val primary = File(Environment.getExternalStorageDirectory(), DIR_NAME)
            val dir = if (probe(primary)) primary else {
                val fallback = File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME)
                if (!probe(fallback)) fallback.mkdirs()
                Util.warn("using fallback EchoClock dir: ${fallback.absolutePath}")
                fallback
            }
            cachedRoot = dir
            return dir
        }
    }

    private fun probe(dir: File): Boolean = try {
        if (!dir.isDirectory && !dir.mkdirs()) false
        else {
            val p = File(dir, ".probe")
            p.writeText("ok")
            p.delete()
            true
        }
    } catch (t: Throwable) {
        false
    }

    /** Creates the directory tree and the default config if missing. Idempotent. */
    fun ensure(context: Context): File {
        val dir = root(context)
        try {
            dir.mkdirs()
            File(dir, "faces").mkdirs()
            writeDefault(File(dir, "config.json"), DEFAULT_CONFIG_JSON)
        } catch (t: Throwable) {
            Util.warn("cannot prepare ${dir.absolutePath}", t)
        }
        return dir
    }

    private fun writeDefault(file: File, content: String) {
        if (file.isFile) return
        try {
            file.writeText(content + "\n")
        } catch (t: Throwable) {
            Util.warn("cannot write default ${file.name}", t)
        }
    }

    /** config.json merged over the built-in defaults (missing keys fall back). */
    fun load(context: Context): JSONObject {
        val defaults = try {
            JSONObject(DEFAULT_CONFIG_JSON)
        } catch (t: Throwable) {
            JSONObject()
        }
        val file = File(ensure(context), "config.json")
        if (!file.isFile) return defaults
        return try {
            val parsed = JSONObject(file.readText())
            val keys = parsed.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                defaults.put(k, parsed.get(k))
            }
            defaults
        } catch (t: Throwable) {
            Util.warn("config.json unreadable; using defaults", t)
            defaults
        }
    }

    // ---- config access (DESIGN §7, §8) ----------------------------------------

    /** Raw contents of config.json (no defaults merged). Never null. */
    private fun fileJson(context: Context): JSONObject {
        val file = File(ensure(context), "config.json")
        return try {
            if (file.isFile) JSONObject(file.readText()) else JSONObject()
        } catch (t: Throwable) {
            Util.warn("config.json unreadable; starting from an empty object", t)
            JSONObject()
        }
    }

    /** Writes `obj` to config.json (temp file + rename). */
    private fun writeJson(context: Context, obj: JSONObject): Boolean = try {
        val file = File(ensure(context), "config.json")
        val text = obj.toString(2) + "\n"
        val tmp = File(file.parentFile, "config.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
        true
    } catch (t: Throwable) {
        Util.warn("cannot write config.json", t)
        false
    }

    /** Merges `values` into config.json `faces.<id>` (per-face settings, DESIGN §7.1). */
    fun putFaceSettings(context: Context, faceId: String, values: JSONObject): Boolean {
        if (faceId.isBlank()) return false
        val obj = fileJson(context)
        val faces = obj.optJSONObject("faces") ?: JSONObject().also { obj.put("faces", it) }
        val face = faces.optJSONObject(faceId) ?: JSONObject().also { faces.put(faceId, it) }
        val keys = values.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            face.put(k, values.get(k))
        }
        return writeJson(context, obj)
    }

    /** Patches a single per-face setting. */
    fun putFaceSetting(context: Context, faceId: String, key: String, value: Any): Boolean =
        putFaceSettings(context, faceId, JSONObject().put(key, value))

    // ---- v3 config access (DESIGN §8.3) ---------------------------------------

    private val DEFAULT_WEATHER = JSONObject(
        "{\"enabled\":true,\"lat\":37.77,\"lon\":-122.42,\"units\":\"metric\",\"refreshMinutes\":30}"
    )
    private val DEFAULT_CALENDAR = JSONObject(
        "{\"enabled\":true,\"maxEvents\":4,\"horizonHours\":48}"
    )

    /** Base object overridden key-by-key (sub-objects are merged, not replaced). */
    private fun merged(base: JSONObject, override: JSONObject?): JSONObject {
        val out = JSONObject(base.toString())
        if (override == null) return out
        val keys = override.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out.put(k, override.get(k))
        }
        return out
    }

    /** Effective `weather` block (DESIGN §8.3), defaults filled in. */
    fun weather(context: Context): JSONObject = merged(DEFAULT_WEATHER, fileJson(context).optJSONObject("weather"))

    /** Effective `calendar` block (DESIGN §8.3), defaults filled in. */
    fun calendar(context: Context): JSONObject = merged(DEFAULT_CALENDAR, fileJson(context).optJSONObject("calendar"))
}
