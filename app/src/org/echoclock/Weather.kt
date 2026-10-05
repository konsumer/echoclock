package org.echoclock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Weather from open-meteo.com (DESIGN §8.2, §8.3, §8.4): no API key, cached on disk,
 * refreshed at most every `weather.refreshMinutes`.
 *
 * All network I/O happens on a daemon thread — `current()` only ever returns the
 * in-memory cache, so the UI and the faces can never be blocked by the network. A
 * failed fetch keeps serving the cache with `stale:true`; `refreshAsync(force=true)`
 * (`weather:refresh`) bypasses the interval.
 */
object Weather {

    private const val CACHE_FILE = "weather-cache.json"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    private val refreshing = AtomicBoolean(false)
    private val forceQueued = AtomicBoolean(false)

    @Volatile private var cache: JSONObject? = null
    @Volatile private var loaded = false
    @Volatile private var enabled = true
    @Volatile private var nextDueAt = 0L
    @Volatile private var lastAttempt = 0L

    /** Cached weather (`state.weather` / `EC.weather()`), or null while unknown. */
    fun current(context: Context): JSONObject? {
        load(context)
        return cache
    }

    /** Cheap periodic hook (called ~1/s): triggers a fetch when due. Never blocks. */
    fun tick(context: Context) {
        if (!enabled || System.currentTimeMillis() < nextDueAt) return
        maybeRefresh(context.applicationContext, force = false)
    }

    /** `weather:refresh` / `EC.refresh()`: force a network fetch. Never blocks the UI. */
    fun refreshAsync(context: Context, force: Boolean = true) {
        val app = context.applicationContext
        load(app)
        maybeRefresh(app, force)
    }

    private fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            cache = try {
                val file = File(context.filesDir, CACHE_FILE)
                if (file.isFile) {
                    JSONObject(file.readText()).apply { put("stale", true) }
                } else {
                    null
                }
            } catch (t: Throwable) {
                Util.warn("weather cache unreadable", t)
                null
            }
        }
    }

    private fun maybeRefresh(context: Context, force: Boolean) {
        val cfg = Config.weather(context)
        enabled = cfg.optBoolean("enabled", true)
        if (!enabled) {
            nextDueAt = Long.MAX_VALUE
            return
        }
        val now = System.currentTimeMillis()
        // `nextDueAt` gates every attempt, successful or not, so an offline device
        // retries at most once per `refreshMinutes` instead of every tick.
        if (!force && now < nextDueAt) return
        val interval = cfg.optInt("refreshMinutes", 30).coerceAtLeast(1) * 60_000L
        nextDueAt = now + interval
        if (!refreshing.compareAndSet(false, true)) {
            if (force) forceQueued.set(true)
            return
        }
        lastAttempt = now
        Thread({
            try {
                cache = fetch(cfg)
                persist(context, cache)
                Util.log("weather updated (${cache?.optString("text", "?")})")
            } catch (t: Throwable) {
                Util.warn("weather fetch failed: ${t.message}")
                cache?.put("stale", true)
            } finally {
                refreshing.set(false)
                if (forceQueued.getAndSet(false)) {
                    nextDueAt = 0L
                    maybeRefresh(context, force = true)
                }
            }
        }, "echoclock-weather").apply { isDaemon = true }.start()
    }

    private fun fetch(cfg: JSONObject): JSONObject {
        val lat = cfg.optDouble("lat", 37.77)
        val lon = cfg.optDouble("lon", -122.42)
        // Always fetch Celsius (the API default) and derive Fahrenheit here, so every face can
        // choose its own unit from `tempC`/`tempF` without network or conversion in JS.
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast?latitude=")
            append(lat)
            append("&longitude=")
            append(lon)
            append("&current=temperature_2m,weather_code")
            append("&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto")
        }
        val root = JSONObject(httpGet(url))
        val current = root.optJSONObject("current") ?: JSONObject()
        val code = current.optInt("weather_code", -1)
        val temp = current.optDouble("temperature_2m", Double.NaN)
        val out = JSONObject()
        out.put("code", if (code >= 0) code else JSONObject.NULL)
        if (!temp.isNaN() && !temp.isInfinite()) {
            out.put("tempC", round1(temp))
            out.put("tempF", round1(cToF(temp)))
        }
        out.put("text", codeText(code))
        out.put("daily", parseDaily(root.optJSONObject("daily")))
        out.put("stale", false)
        out.put("updated", System.currentTimeMillis() / 1000)
        return out
    }

    private fun parseDaily(daily: JSONObject?): JSONArray {
        val out = JSONArray()
        if (daily == null) return out
        val dates = daily.optJSONArray("time") ?: return out
        val codes = daily.optJSONArray("weather_code")
        val maxs = daily.optJSONArray("temperature_2m_max")
        val mins = daily.optJSONArray("temperature_2m_min")
        for (i in 0 until minOf(dates.length(), 7)) {
            val day = JSONObject()
            day.put("date", dates.optString(i, ""))
            val c = codes?.optInt(i, -1) ?: -1
            day.put("code", if (c >= 0) c else JSONObject.NULL)
            val lo = mins?.optDouble(i, Double.NaN) ?: Double.NaN
            val hi = maxs?.optDouble(i, Double.NaN) ?: Double.NaN
            if (!lo.isNaN()) {
                day.put("minC", round1(lo))
                day.put("minF", round1(cToF(lo)))
            }
            if (!hi.isNaN()) {
                day.put("maxC", round1(hi))
                day.put("maxF", round1(cToF(hi)))
            }
            out.put(day)
        }
        return out
    }

    private fun cToF(c: Double): Double = c * 9.0 / 5.0 + 32.0

    private fun persist(context: Context, json: JSONObject?) {
        if (json == null) return
        try {
            val file = File(context.filesDir, CACHE_FILE)
            val tmp = File(file.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(json.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Util.warn("cannot cache weather", t)
        }
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "EchoClock/1.0 (Android)")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            try {
                conn.disconnect()
            } catch (t: Throwable) {
                // ignored
            }
        }
    }

    private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

    /** WMO weather interpretation codes -> short text (open-meteo docs). */
    fun codeText(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> "Unknown"
    }
}
