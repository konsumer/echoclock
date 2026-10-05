package org.echoclock

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * HOME/launcher activity (DESIGN §2.1, §2.4, §2.5): fullscreen WebView clock face plus
 * an app-drawer overlay. KEYCODE_HOME/KEYCODE_BACK return to the clock.
 *
 * External automation (`am start ... --es action "<action>"`) is dispatched by [Actions].
 */
class HomeActivity : Activity() {

    companion object {
        private const val TICK_MS = 1000L

        /** Horizontal drag distance that counts as a face swipe (DESIGN §7.1). */
        private const val SWIPE_DP = 90

        /** Clean-face gestures (DESIGN §8.1): tap < 300 ms, long press >= 600 ms. */
        private const val TAP_MS = 300L
        private const val LONG_PRESS_MS = 600L

        private const val FALLBACK_FACE_HTML =
            "<!doctype html><html><head><meta charset=utf-8><style>" +
                "html,body{margin:0;height:100%;background:#05070a;color:#e8f4ff;" +
                "font-family:Helvetica,Arial,sans-serif;display:flex;align-items:center;" +
                "justify-content:center}#t{font-size:200px;font-weight:200}</style></head><body>" +
                "<div id=t>--:--</div><script>function r(){var d=new Date();" +
                "document.getElementById('t').textContent=('0'+d.getHours()).slice(-2)+':'+" +
                "('0'+d.getMinutes()).slice(-2);}r();setInterval(r,1000);</script></body></html>"
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var drawer: AppDrawer

    private val handler = Handler(Looper.getMainLooper())

    private var faceId = "digital"
    @Volatile private var pageReady = false

    // v2 (DESIGN §7.1): settings sheet + swipe navigation.
    private var settingsSheet: SettingsSheet? = null
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownAt = 0L
    private var tapCandidate = false
    private var swipeTracking = false
    private var swipeDone = false
    private var longPressFired = false

    /** Touch slop for the clean-face tap gesture (DESIGN §8.1). */
    private val tapSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private val ticker = object : Runnable {
        override fun run() {
            pushTick()
            Weather.tick(this@HomeActivity)
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        Config.ensure(this)
        faceId = FaceStore.selected(this)

        buildUi()

        loadFace(faceId)

        handler.post(ticker)
        Weather.tick(this)
        handleExternalAction(intent)
    }

    /**
     * Lets an external caller (adb, Tasker, another app) trigger a launcher action:
     *   adb shell am start -n org.echoclock/.HomeActivity --es action "face:flip"
     * Supported actions live in [Actions]. Useful for automation and for testing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExternalAction(intent)
    }

    private fun handleExternalAction(intent: Intent?) {
        val action = intent?.getStringExtra("action")?.trim().orEmpty()
        if (action.isEmpty()) return
        Util.log("external action: $action")
        runOnUiThread {
            if (!Actions.run(this, action)) Util.warn("unknown action: $action")
        }
    }

    // ---- UI ------------------------------------------------------------------

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)
        // Clean face (DESIGN §8.1): no overlay buttons. Taps and long presses are
        // detected in dispatchTouchEvent; keep the WebView from starting its own
        // text-selection long-press.
        webView.isLongClickable = false
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.textZoom = 100
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadsImagesAutomatically = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                installHitHelper()
                pushConfig()
                pushTick()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return when (uri.scheme) {
                    "file", "about", "data" -> false
                    else -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (t: Throwable) {
                            Util.warn("cannot open $uri", t)
                        }
                        true
                    }
                }
            }
        }
        webView.addJavascriptInterface(EcBridge(this), "EC")
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))

        drawer = AppDrawer(this, root)
        settingsSheet = SettingsSheet(this, root)

        setContentView(root)
    }

    @Suppress("DEPRECATION")
    private fun goImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        val selected = FaceStore.selected(this)
        if (selected != faceId) switchFace(selected)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            try {
                webView.destroy()
            } catch (t: Throwable) {
                Util.warn("webView.destroy failed", t)
            }
        }
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (settingsShown()) hideSettings() else showClock()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            showClock()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openDrawer()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- v2: swipe navigation (DESIGN §7.1) -----------------------------------

    /**
     * Horizontal drags on the clock (past [SWIPE_DP]) move between faces; vertical drags
     * and drags while the drawer/settings are open are left to the child views.
     */
    // Gestures are detected here rather than via View callbacks: the WebView swallows
    // its own long-press (text selection), and face-internal clicks must still arrive.
    private val longPressRunnable = Runnable {
        // Cancelled by ACTION_MOVE on larger drift; a small drift only disqualifies the tap.
        if (swipeTracking && !swipeDone) {
            swipeTracking = false
            tapCandidate = false
            longPressFired = true
            openSettings()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeTracking = !(::drawer.isInitialized && drawer.isShown()) && !settingsShown()
                swipeDone = false
                longPressFired = false
                tapCandidate = swipeTracking
                touchDownX = ev.x
                touchDownY = ev.y
                touchDownAt = SystemClock.elapsedRealtime()
                if (swipeTracking) handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                // A press that starts on a face control must not be stolen for the settings
                // sheet — otherwise holding the pomodoro timer would open settings instead of
                // starting/pausing it. Ask the face; if it is a control, drop our long-press.
                if (swipeTracking && pageReady) {
                    queryInteractive(ev.x, ev.y) { interactive ->
                        if (interactive) handler.removeCallbacks(longPressRunnable)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeTracking && !swipeDone) {
                    val dx = ev.x - touchDownX
                    val dy = ev.y - touchDownY
                    if (abs(dx) >= dp(SWIPE_DP) && abs(dx) > abs(dy) * 1.5f) {
                        swipeDone = true
                        tapCandidate = false
                        handler.removeCallbacks(longPressRunnable)
                        if (dx < 0) switchRelativeFace(1) else switchRelativeFace(-1)
                        return true
                    }
                    if (abs(dx) > tapSlop || abs(dy) > tapSlop) tapCandidate = false
                    if (abs(dx) > dp(12) || abs(dy) > dp(12)) handler.removeCallbacks(longPressRunnable)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                val tap = ev.actionMasked == MotionEvent.ACTION_UP && swipeTracking && !swipeDone &&
                    !longPressFired && tapCandidate &&
                    SystemClock.elapsedRealtime() - touchDownAt < TAP_MS
                val consumed = swipeTracking && (swipeDone || longPressFired)
                swipeTracking = false
                swipeDone = false
                longPressFired = false
                tapCandidate = false
                if (tap) {
                    // Deliver the release first so a face button still gets its click,
                    // then open the drawer only when no interactive element was hit.
                    val handled = super.dispatchTouchEvent(ev)
                    if (pageReady) hitTest(ev.x, ev.y) else openDrawer()
                    return handled
                }
                if (consumed) return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---- v3: clean-face hit testing (DESIGN §8.1) -----------------------------

    /**
     * Installs `window._ecHit(xCss,yCss)` — the JS-side equivalent of the spec's
     * `EC._hitInteractive` (a plain function cannot reliably be attached to the Java
     * bridge object) — and best-effort attaches it as `EC._hitInteractive` too.
     */
    private fun installHitHelper() {
        evaluate(
            "(function(){try{window._ecHit=function(x,y){try{" +
                "var el=document.elementFromPoint(x,y);" +
                "return !!(el&&el.closest&&el.closest('button,a,input,select,textarea,[data-ec]'));" +
                "}catch(e){return false;}};}catch(e){}" +
                "try{window.EC._hitInteractive=window._ecHit;}catch(e){}})();"
        )
    }

    /** Asks the face whether device point (x,y) is over one of its interactive elements. */
    private fun queryInteractive(xPx: Float, yPx: Float, onResult: (Boolean) -> Unit) {
        val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val zoom = try {
            webView.scale
        } catch (t: Throwable) {
            1f
        }.takeIf { it > 0f } ?: 1f
        val scale = density * zoom
        val cssX = xPx / scale
        val cssY = yPx / scale
        val js = "(function(){try{if(typeof window._ecHit==='function')return window._ecHit($cssX,$cssY);" +
            "var el=document.elementFromPoint($cssX,$cssY);" +
            "return !!(el&&el.closest&&el.closest('button,a,input,select,textarea,[data-ec]'));" +
            "}catch(e){return false;}})()"
        try {
            webView.evaluateJavascript(js) { result -> onResult(result?.trim() == "true") }
        } catch (t: Throwable) {
            Util.warn("hit test failed", t)
            onResult(false)
        }
    }

    /** Opens the drawer unless the tap landed on an interactive element inside the face. */
    private fun hitTest(xPx: Float, yPx: Float) {
        queryInteractive(xPx, yPx) { interactive -> if (!interactive) openDrawer() }
    }

    @Deprecated("Handled via onKeyDown; kept for framework callers.")
    override fun onBackPressed() {
        showClock()
    }

    // ---- face plumbing -------------------------------------------------------

    private fun loadFace(id: String) {
        pageReady = false
        val url = FaceStore.indexUrl(this, id)
        if (url == null) {
            webView.loadDataWithBaseURL(null, FALLBACK_FACE_HTML, "text/html", "utf-8", null)
        } else {
            webView.loadUrl(url)
        }
    }

    /** Switch face and persist the choice. Returns false for unknown ids. */
    fun switchFace(id: String): Boolean {
        val clean = id.trim().lowercase(Locale.US)
        if (!FaceStore.exists(this, clean)) return false
        faceId = clean
        FaceStore.setSelected(this, clean)
        Util.log("face -> $clean")
        loadFace(clean)
        return true
    }

    fun setFaceFromJs(id: String?): Boolean {
        val clean = id?.trim()?.lowercase(Locale.US).orEmpty()
        if (!FaceStore.exists(this, clean)) return false
        runOnUiThread { switchFace(clean) }
        return true
    }

    fun openDrawer() {
        if (::drawer.isInitialized) drawer.show()
    }

    fun showClock() {
        if (::drawer.isInitialized && drawer.isShown()) drawer.hide()
        settingsSheet?.hide()
    }

    /** External action `app:<pkg>`: launches the package's launcher activity. */
    fun launchApp(pkg: String): Boolean {
        val id = pkg.trim()
        if (id.isEmpty()) return false
        return try {
            val intent = packageManager.getLaunchIntentForPackage(id) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (t: Throwable) {
            Util.warn("cannot launch $id", t)
            false
        }
    }

    // ---- v2: face navigation (DESIGN §7.1) ------------------------------------

    fun nextFace(): Boolean = switchRelativeFace(1)

    fun prevFace(): Boolean = switchRelativeFace(-1)

    fun nextFaceFromJs(): Boolean {
        runOnUiThread { switchRelativeFace(1) }
        return true
    }

    fun prevFaceFromJs(): Boolean {
        runOnUiThread { switchRelativeFace(-1) }
        return true
    }

    /** Moves `delta` steps through the ordered face list, wrapping around. */
    private fun switchRelativeFace(delta: Int): Boolean {
        val faces = FaceStore.list(this)
        if (faces.isEmpty()) return false
        val index = faces.indexOfFirst { it.id == faceId }
        val next = faces[((if (index < 0) 0 else index) + delta).mod(faces.size)]
        return switchFace(next.id)
    }

    // ---- v2: per-face settings (DESIGN §7.1) ----------------------------------

    fun faceSettingsJson(): String = FaceStore.settingsJson(this, faceId)

    fun settingsShown(): Boolean = settingsSheet?.isShown() == true

    fun hideSettings() {
        settingsSheet?.hide()
    }

    fun openSettings() {
        val sheet = settingsSheet ?: return
        sheet.show(
            faceName = FaceStore.nameOf(this, faceId) ?: faceId,
            schema = FaceStore.settingsSchema(this, faceId),
            current = FaceStore.faceConfig(this, faceId),
            onSave = { values -> applyFaceSettings(faceId, values) },
            onAlarm = { hour, minute -> AlarmData.setAlarm(this, hour, minute) }
        )
    }

    fun setSettingFromJs(key: String?, rawValue: String?): Boolean {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return false
        val setting = FaceStore.settingsSchema(this, faceId).firstOrNull { it.key == k } ?: return false
        val value = FaceStore.coerceSetting(setting, rawValue) ?: return false
        runOnUiThread {
            FaceStore.writeFaceSetting(this, faceId, k, value)
            pushConfig()
            pushTick()
        }
        return true
    }

    private fun applyFaceSettings(targetFace: String, values: JSONObject) {
        FaceStore.writeFaceSettings(this, targetFace, values)
        pushConfig()
        pushTick()
    }

    // ---- state + JS push -----------------------------------------------------

    fun stateJson(): String {
        val (battery, charging) = Util.battery(this)
        val cfg = Config.load(this)
        val o = JSONObject()
        o.put("epoch", System.currentTimeMillis())
        o.put("battery", battery)
        o.put("charging", charging)
        o.put("face", faceId)
        o.put("locale", Locale.getDefault().toLanguageTag())
        o.put("brightness", cfg.optDouble("brightness", 0.7))
        // v3 state additions (DESIGN §8.2); every one is optional and faces guard it.
        o.put("nextAlarm", AlarmData.nextJson(this) ?: JSONObject.NULL)
        o.put("calendar", CalendarData.eventsJson(this))
        Weather.current(this)?.let { o.put("weather", it) }
        return o.toString()
    }

    /** `EC.calendar()`: the same array `state.calendar` carries (DESIGN §8.4). */
    fun calendarJson(): String = CalendarData.eventsJson(this).toString()

    /** `EC.nextAlarm()`: the alarm object, or the string `null` (DESIGN §8.4). */
    fun nextAlarmJson(): String = AlarmData.nextJson(this)?.toString() ?: "null"

    /** `EC.weather()`: the cached weather object, or the string `null` (DESIGN §8.4). */
    fun weatherJson(): String = Weather.current(this)?.toString() ?: "null"

    /** `EC.refresh()` (DESIGN §8.4): force calendar + weather refresh, never blocks. */
    fun refreshFromJs(): Boolean {
        CalendarData.invalidate()
        Weather.refreshAsync(this, force = true)
        runOnUiThread { pushTick() }
        return true
    }

    /** External action `weather:refresh`: force a fetch; never blocks the UI. */
    fun refreshWeather() {
        Weather.refreshAsync(this, force = true)
    }

    /** External action `calendar:refresh`: drop the cache and re-push state. */
    fun refreshCalendar() {
        CalendarData.invalidate()
        runOnUiThread { pushTick() }
    }

    private fun evaluate(js: String) {
        if (!::webView.isInitialized) return
        try {
            webView.evaluateJavascript(js, null)
        } catch (t: Throwable) {
            Util.warn("evaluateJavascript failed", t)
        }
    }

    private fun pushTick() {
        if (pageReady) evaluate("window.ec&&window.ec.tick&&window.ec.tick(${Util.jsonQuote(stateJson())});")
    }

    private fun pushConfig() {
        evaluate("window.ec&&window.ec.config&&window.ec.config(${Util.jsonQuote(FaceStore.faceConfig(this, faceId).toString())});")
    }
}
