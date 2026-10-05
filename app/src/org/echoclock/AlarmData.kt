package org.echoclock

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * Next-alarm readout + alarm setting (DESIGN §8.2, §8.4).
 *
 * `next()` wraps `AlarmManager.getNextAlarmClock()`; `setAlarm()` fires
 * `AlarmClock.ACTION_SET_ALARM`, adding `EXTRA_SKIP_UI` when the `SET_ALARM`
 * permission is held (the pre-filled Clock UI stays as the fallback).
 */
object AlarmData {

    /**
     * `state.nextAlarm`: `{epoch,label,inMinutes,dayOffset,weekday,time}`, or null when no
     * alarm is set. Always the **next** alarm (`AlarmManager.getNextAlarmClock()` picks the
     * earliest of however many are set), recomputed on every call.
     *
     * `dayOffset` (0 = today, 1 = tomorrow, …) and `weekday` (short, device locale) are
     * resolved in the device's timezone so faces can decide how to present a later alarm
     * (e.g. show "Tue") without re-deriving calendars in JS. `time` is "HH:MM" in 24-hour form.
     */
    fun nextJson(context: Context): JSONObject? = try {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val info = manager?.nextAlarmClock
        if (info == null) {
            null
        } else {
            val epoch = info.triggerTime
            val now = System.currentTimeMillis()
            val zone = java.time.ZoneId.systemDefault()
            val at = java.time.Instant.ofEpochMilli(epoch).atZone(zone)
            val today = java.time.LocalDate.now(zone)
            val dayOffset = java.time.temporal.ChronoUnit.DAYS.between(today, at.toLocalDate()).toInt()
            // AlarmClockInfo exposes only a PendingIntent, not the alarm's message, so
            // there is no public API for the user's label: use a fixed one.
            JSONObject()
                .put("epoch", epoch)
                .put("label", "Alarm")
                .put("inMinutes", ((epoch - now) / 60_000.0).roundToLong().coerceAtLeast(0L))
                .put("dayOffset", dayOffset)
                .put("weekday", at.dayOfWeek.getDisplayName(
                    java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()))
                .put("time", String.format(java.util.Locale.US, "%02d:%02d", at.hour, at.minute))
        }
    } catch (t: Throwable) {
        Util.warn("cannot read the next alarm", t)
        null
    }

    /**
     * "Set alarm" row / local-picker fallback (DESIGN §8.4): opens the system Clock to set
     * the alarm. Skips the UI when `SET_ALARM` is granted (normal permission — granted at
     * install).
     */
    fun setAlarm(context: Context, hour: Int, minute: Int): Boolean {
        if (hour !in 0..23 || minute !in 0..59) {
            Util.warn("invalid alarm time: $hour:$minute")
            return false
        }
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.checkSelfPermission(Manifest.permission.SET_ALARM) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            context.startActivity(intent)
            Util.log(String.format(java.util.Locale.US, "set alarm %02d:%02d", hour, minute))
            true
        } catch (t: Throwable) {
            Util.warn("cannot set the alarm (no clock app?)", t)
            false
        }
    }
}
