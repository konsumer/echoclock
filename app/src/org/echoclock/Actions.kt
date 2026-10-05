package org.echoclock

/**
 * External action trigger (DESIGN §7.3, §8.4): `adb shell am start -n org.echoclock/.HomeActivity
 * --es action "<action>"`. Deliberately tiny — the whole action vocabulary is:
 *
 *   face:<id> | app:<pkg> | nextFace | prevFace | openDrawer |
 *   weather:refresh | calendar:refresh
 *
 * Anything else is logged and ignored.
 */
object Actions {

    /** Runs [raw] against [activity]; false for an unknown/empty action. */
    fun run(activity: HomeActivity, raw: String): Boolean {
        val action = raw.trim()
        if (action.isEmpty()) return false
        return when {
            action.startsWith("face:", ignoreCase = true) ->
                activity.switchFace(action.substringAfter(':').trim())

            action.startsWith("app:", ignoreCase = true) ->
                activity.launchApp(action.substringAfter(':'))

            action.equals("nextFace", ignoreCase = true) -> activity.nextFace()

            action.equals("prevFace", ignoreCase = true) -> activity.prevFace()

            action.equals("openDrawer", ignoreCase = true) -> {
                activity.openDrawer()
                true
            }

            action.equals("weather:refresh", ignoreCase = true) -> {
                activity.refreshWeather()
                true
            }

            action.equals("calendar:refresh", ignoreCase = true) -> {
                activity.refreshCalendar()
                true
            }

            else -> false
        }
    }
}
