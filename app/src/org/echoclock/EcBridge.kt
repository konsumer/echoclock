package org.echoclock

import android.webkit.JavascriptInterface

/**
 * `window.EC` JS bridge (DESIGN §2.4, §7.1). Methods run on the WebView JavaBridge
 * thread; anything touching UI is posted by [HomeActivity].
 */
class EcBridge(private val activity: HomeActivity) {

    @JavascriptInterface
    fun state(): String = activity.stateJson()

    @JavascriptInterface
    fun faces(): String = FaceStore.listJson(activity)

    /** v2: the current face's `settings` schema + values (DESIGN §7.1). */
    @JavascriptInterface
    fun settings(): String = activity.faceSettingsJson()

    @JavascriptInterface
    fun setFace(id: String?): Boolean = activity.setFaceFromJs(id)

    /** v2: sets one schema-typed per-face setting (DESIGN §7.1). */
    @JavascriptInterface
    fun setSetting(key: String?, value: String?): Boolean = activity.setSettingFromJs(key, value)

    @JavascriptInterface
    fun nextFace(): Boolean = activity.nextFaceFromJs()

    @JavascriptInterface
    fun prevFace(): Boolean = activity.prevFaceFromJs()

    @JavascriptInterface
    fun openDrawer(): Boolean {
        activity.runOnUiThread { activity.openDrawer() }
        return true
    }

    /** v3 (DESIGN §8.1): open the per-face settings sheet from JS. */
    @JavascriptInterface
    fun openSettings(): Boolean {
        activity.runOnUiThread { activity.openSettings() }
        return true
    }

    /** v3 (DESIGN §8.4): `state.calendar` as a JSON array string. */
    @JavascriptInterface
    fun calendar(): String = activity.calendarJson()

    /** v3 (DESIGN §8.4): the next alarm object, or the string `null`. */
    @JavascriptInterface
    fun nextAlarm(): String = activity.nextAlarmJson()

    /** v3 (DESIGN §8.4): the cached weather object, or the string `null`. */
    @JavascriptInterface
    fun weather(): String = activity.weatherJson()

    /** v3 (DESIGN §8.4): force weather + calendar refresh; returns immediately. */
    @JavascriptInterface
    fun refresh(): Boolean = activity.refreshFromJs()
}
