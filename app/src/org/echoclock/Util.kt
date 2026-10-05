package org.echoclock

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import org.json.JSONObject

/** Shared helpers: logging, battery, JS string quoting. */
object Util {

    const val TAG = "EchoClock"

    fun log(msg: String) = Log.i(TAG, msg)

    fun warn(msg: String, t: Throwable? = null) {
        if (t == null) Log.w(TAG, msg) else Log.w(TAG, msg, t)
    }

    /** Safe JS/JSON string literal for injection into `evaluateJavascript`. */
    fun jsonQuote(s: String): String = JSONObject.quote(s)

    /** (percent, charging) from the sticky battery broadcast; (100, true) if unknown. */
    fun battery(context: Context): Pair<Int, Boolean> {
        val intent = try {
            context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (t: Throwable) {
            null
        } ?: return 100 to true
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0
        return pct to charging
    }
}

/** dp -> px for the current screen (960x480 mdpi on cronos). */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
