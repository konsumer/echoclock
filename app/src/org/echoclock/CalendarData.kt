package org.echoclock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * Upcoming calendar events (DESIGN §8.2, §8.4) from `CalendarContract.Instances` over
 * `[now, now + horizonHours]`, newest first, capped at `maxEvents`.
 *
 * Missing permission / no accounts / provider errors all yield an empty array. Results
 * are cached for [TTL_MS] so the once-per-second state push does not query every tick.
 */
object CalendarData {

    private const val TTL_MS = 60_000L

    private var cached: JSONArray = JSONArray()
    private var cachedAt = 0L

    /** Drops the cache so the next read queries the provider (`calendar:refresh`). */
    @Synchronized
    fun invalidate() {
        cachedAt = 0L
    }

    /** `state.calendar` / `EC.calendar()`: JSON array of event objects. */
    @Synchronized
    fun eventsJson(context: Context): JSONArray {
        val cfg = Config.calendar(context)
        if (!cfg.optBoolean("enabled", true)) return JSONArray()
        val now = System.currentTimeMillis()
        if (now - cachedAt < TTL_MS) return cached
        cachedAt = now
        val maxEvents = cfg.optInt("maxEvents", 4).coerceIn(1, 50)
        val horizonHours = cfg.optInt("horizonHours", 48).coerceIn(1, 24 * 90)
        cached = try {
            query(context, now, now + horizonHours * 3_600_000L, maxEvents)
        } catch (t: Throwable) {
            Util.warn("calendar query failed", t)
            JSONArray()
        }
        return cached
    }

    private fun query(context: Context, begin: Long, end: Long, maxEvents: Int): JSONArray {
        val out = JSONArray()
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return out
        }
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        )
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(begin.toString())
            .appendPath(end.toString())
            .build()
        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            CalendarContract.Instances.BEGIN + " ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && out.length() < maxEvents) {
                val evBegin = cursor.getLong(1)
                val evEnd = cursor.getLong(2)
                if (evEnd < begin) continue
                out.put(
                    JSONObject()
                        .put("title", cursor.getString(0).orEmpty())
                        .put("begin", evBegin)
                        .put("end", evEnd)
                        .put("allDay", cursor.getInt(3) != 0)
                        .put("calendar", cursor.getString(4).orEmpty())
                        .put("inMinutes", ((evBegin - begin) / 60_000.0).roundToLong().coerceAtLeast(0L))
                )
            }
        }
        return out
    }
}
