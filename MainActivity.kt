package app.friday.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Friday on your phone. It works two ways and picks by itself:
 *  - Laptop link: when your laptop is on and reachable (home Wi-Fi, or anywhere through Tailscale),
 *    the app shows the page your laptop serves, and Friday can control the laptop.
 *  - Phone only: otherwise the app talks to Google's voice service directly over Wi-Fi or mobile data.
 *    It needs your Google key, which you can copy once from your laptop (Copy settings from laptop).
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var settings: ScrollView
    private lateinit var gear: TextView
    private lateinit var message: TextView
    private lateinit var addresses: EditText
    private lateinit var pin: EditText
    private lateinit var keyBox: EditText
    private lateinit var haUrlBox: EditText
    private lateinit var haTokenBox: EditText
    private lateinit var callBox: EditText
    private var pendingCall: String? = null
    private lateinit var modeButtons: Map<String, Button>
    private lateinit var blockedBox: EditText
    private lateinit var controlStatus: TextView
    private lateinit var control: PhoneControl
    private lateinit var prefs: SharedPreferences
    private var pendingGrant: PermissionRequest? = null
    private var trustedHost = ""
    private var mode = "auto"
    private var launchId = 0
    private var tvMode = false

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun ui(block: () -> Unit) = runOnUiThread { block() }

    // ---------------------------------------------------------------- phone-only page <-> app bridge
    inner class Bridge {
        @JavascriptInterface
        fun getConfig(): String {
            val o = JSONObject()
            o.put("key", prefs.getString("key", "") ?: "")
            o.put("model", prefs.getString("model", "") ?: "")
            o.put("name", prefs.getString("name", "Friday") ?: "Friday")
            o.put("voice", prefs.getString("voice", "") ?: "")
            o.put("language", prefs.getString("language", "en-US") ?: "en-US")
            o.put("style", prefs.getString("style", "") ?: "")
            o.put("memory", parseArray(prefs.getString("memory", "[]")))
            o.put("flags", parseObject(prefs.getString("flags", "{}")))
            o.put("ha_url", prefs.getString("ha_url", "") ?: "")
            o.put("ha_token", prefs.getString("ha_token", "") ?: "")
            o.put("devices", parseObject(prefs.getString("devices", "{}")))
            return o.toString()
        }

        /** If the user answered a call from Friday, the reason she called (once), so the page can start talking. */
        @JavascriptInterface
        fun getPendingCall(): String {
            val r = pendingCall ?: ""
            pendingCall = null
            return r
        }

        @JavascriptInterface
        fun saveMemory(json: String) {
            prefs.edit().putString("memory", json).apply()
        }

        @JavascriptInterface
        fun saveFlags(json: String) {
            prefs.edit().putString("flags", json).apply()
        }

        /** Wake-on-LAN: sends the magic packet by UDP broadcast on whatever network the phone is on. */
        @JavascriptInterface
        fun wake(mac: String, ip: String): String {
            return try {
                val hex = mac.replace(Regex("[^0-9a-fA-F]"), "")
                if (hex.length != 12) return "error: bad MAC address"
                val bytes = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                val packet = ByteArray(102)
                for (i in 0 until 6) packet[i] = 0xFF.toByte()
                for (i in 0 until 16) System.arraycopy(bytes, 0, packet, 6 + i * 6, 6)
                val targets = arrayListOf("255.255.255.255")
                val m = Regex("^(\\d+\\.\\d+\\.\\d+)\\.\\d+$").find(ip.trim())
                if (m != null) targets.add(m.groupValues[1] + ".255")
                var sent = 0
                java.net.DatagramSocket().use { sock ->
                    sock.broadcast = true
                    for (t in targets) for (port in intArrayOf(9, 7)) {
                        try {
                            sock.send(java.net.DatagramPacket(packet, packet.size, java.net.InetAddress.getByName(t), port))
                            sent++
                        } catch (e: Exception) {
                        }
                    }
                }
                if (sent > 0) "ok" else "error: no network to send on"
            } catch (e: Exception) {
                "error: ${e.message}"
            }
        }

        /** One web request for the smart home (Home Assistant or a plug's web address). Returns JSON {code, body}. */
        @JavascriptInterface
        fun http(method: String, url: String, headersJson: String, body: String): String {
            val out = JSONObject()
            try {
                if (!(url.startsWith("http://") || url.startsWith("https://"))) return out.put("code", 0).put("error", "bad address").toString()
                val conn = URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = method.uppercase()
                conn.connectTimeout = 6000
                conn.readTimeout = 9000
                val h = parseObject(headersJson)
                for (k in h.keys()) conn.setRequestProperty(k, h.optString(k))
                if (body.isNotEmpty()) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                out.put("code", code).put("body", text.take(200000))
                conn.disconnect()
            } catch (e: Exception) {
                out.put("code", 0).put("error", e.message ?: "no answer")
            }
            return out.toString()
        }

        @JavascriptInterface
        fun getStore(key: String): String = prefs.getString("store_" + key, "") ?: ""

        @JavascriptInterface
        fun setStore(key: String, value: String) {
            prefs.edit().putString("store_" + key, value.take(400000)).apply()
        }

        @JavascriptInterface
        fun openSettings() {
            showSettings("")
        }

        @JavascriptInterface
        fun log(text: String) {
            Log.d("Friday", text)
        }

        @JavascriptInterface
        fun openUrl(url: String): String {
            if (!(url.startsWith("https://") || url.startsWith("http://"))) return "error"
            return launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        @JavascriptInterface
        fun setTimer(seconds: Int, label: String): String {
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            return launch(i)
        }

        @JavascriptInterface
        fun setAlarm(hour: Int, minute: Int, label: String): String {
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            return launch(i)
        }

        @JavascriptInterface
        fun addCalendar(title: String, startMillis: Double, endMillis: Double, place: String, notes: String): String {
            val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis.toLong())
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis.toLong())
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.Events.EVENT_LOCATION, place)
                .putExtra(CalendarContract.Events.DESCRIPTION, notes)
            return launch(i)
        }

        @JavascriptInterface
        fun composeEmail(to: String, subject: String, body: String): String {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                .putExtra(Intent.EXTRA_EMAIL, if (to.isBlank()) arrayOf<String>() else arrayOf(to))
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, body)
            return launch(i)
        }

        // ---- using other apps (needs Friday switched on in Accessibility settings; never banking or payment apps)
        @JavascriptInterface
        fun openApp(name: String): String = control.openApp(name)

        @JavascriptInterface
        fun screenRead(): String = control.screenRead()

        @JavascriptInterface
        fun tap(text: String, nth: Int, confirmed: Boolean): String = control.tap(text, nth, confirmed)

        @JavascriptInterface
        fun tapAt(x: Int, y: Int, confirmed: Boolean): String = control.tapAt(x, y, confirmed)

        @JavascriptInterface
        fun typeText(text: String, replace: Boolean): String = control.typeText(text, replace)

        @JavascriptInterface
        fun scroll(direction: String): String = control.scroll(direction)

        @JavascriptInterface
        fun pressButton(name: String): String = control.button(name)

        @JavascriptInterface
        fun controlStatus(): String = control.status()

        /** Keeps the microphone alive while you use other apps (shows a notification). */
        @JavascriptInterface
        fun keepListening(on: Boolean) {
            ui {
                try {
                    val i = Intent(this@MainActivity, ListeningService::class.java)
                    if (on) {
                        ListeningService.onStop = { ui { web.evaluateJavascript("window.__friday && __friday.stopAll()", null) } }
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                    } else {
                        stopService(i)
                    }
                } catch (e: Exception) {
                    Log.d("Friday", "listening service: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun dial(number: String): String = launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))

        /** "lat,lon" of the last known position, "denied" if location is off (it asks), or "" if unknown. */
        @JavascriptInterface
        fun getLocation(): String {
            val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) {
                ui { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 8) }
                return "denied"
            }
            return try {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                var best: android.location.Location? = null
                for (provider in lm.getProviders(true)) {
                    @SuppressLint("MissingPermission")
                    val l = lm.getLastKnownLocation(provider)
                    if (l != null && (best == null || l.time > best.time)) best = l
                }
                if (best == null) "" else "${best.latitude},${best.longitude}"
            } catch (e: SecurityException) {
                "denied"
            } catch (e: Exception) {
                ""
            }
        }
    }

    private fun launch(intent: Intent): String {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            "ok"
        } catch (e: Exception) {
            "error"
        }
    }

    private fun parseArray(text: String?): JSONArray = try { JSONArray(text ?: "[]") } catch (e: Exception) { JSONArray() }
    private fun parseObject(text: String?): JSONObject = try { JSONObject(text ?: "{}") } catch (e: Exception) { JSONObject() }

    // ---------------------------------------------------------------- screen
    @SuppressLint("SetJavaScriptEnabled")
    /** Forget everything personal on this phone (memories, name, voice, style, chats, theme). Keeps key and smart home. */
    private fun wipeProfile() {
        prefs.edit()
            .remove("memory").remove("name").remove("voice").remove("style").remove("language")
            .remove("flags").remove("store_chats").remove("store_ui").remove("model")
            .apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("friday", MODE_PRIVATE)
        control = PhoneControl(this) {
            (prefs.getString("blocked_extra", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        mode = prefs.getString("mode", "auto") ?: "auto"
        // Version 3.4: this phone starts as a brand new user (own memory, name, voice, style and chats), once.
        // The Google key, smart-home link, saved devices, addresses and PIN are kept so the TV and lights still work.
        if (!prefs.getBoolean("fresh_v34", false)) {
            wipeProfile()
            prefs.edit().putBoolean("fresh_v34", true).apply()
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#262624"))

        web = WebView(this)
        web.visibility = View.GONE
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.allowFileAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // Friday on the laptop uses its own certificate. Trust it only for the laptop address you chose.
                val host = Uri.parse(error.url).host ?: ""
                if (trustedHost.isNotEmpty() && host == trustedHost) handler.proceed() else handler.cancel()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && tvMode) {
                    // TV display: the laptop may be asleep or Friday not started yet. Keep trying quietly.
                    view.postDelayed({ if (tvMode) view.reload() }, 5000)
                    return
                }
                if (request.isForMainFrame && trustedHost.isNotEmpty()) {
                    showSettings("Could not reach Friday at $trustedHost. Is Friday running on your laptop?")
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: ""
                return !(host == "friday.local" || (trustedHost.isNotEmpty() && host == trustedHost))
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val host = request.origin.host ?: ""
                    if (!(host == "friday.local" || (trustedHost.isNotEmpty() && host == trustedHost))) {
                        request.deny()
                        return@runOnUiThread
                    }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.resources)
                    } else {
                        pendingGrant = request
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
                    }
                }
            }
        }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        gear = TextView(this)
        gear.text = "⚙"
        gear.textSize = 24f
        gear.setTextColor(Color.parseColor("#A5A29A"))
        gear.setPadding(dp(16), dp(10), dp(16), dp(10))
        gear.visibility = View.GONE
        gear.setOnClickListener { showSettings("") }
        // TV remote: the gear can be reached with the arrow keys and lights up when selected
        gear.isFocusable = true
        gear.setOnFocusChangeListener { v, hasFocus -> v.setBackgroundColor(if (hasFocus) Color.parseColor("#55888888") else Color.TRANSPARENT) }
        if (isTv()) { web.settings.textZoom = 140 }
        val gearParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        gearParams.gravity = Gravity.TOP or Gravity.END
        gearParams.topMargin = dp(24)
        root.addView(gear, gearParams)

        settings = buildSettings()
        root.addView(settings, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val wanted = arrayListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(wanted.toTypedArray(), 7)
        }
        val configured = !(prefs.getString("key", "").isNullOrBlank()) || !(prefs.getString("addresses", "").isNullOrBlank())
        if (prefs.getBoolean("calls_on", false) && !(prefs.getString("call_topic", "").isNullOrBlank())) {
            try { CallService.start(this) } catch (e: Exception) { }
        }
        takeTvUrl(intent)
        val tvUrl = prefs.getString("tv_url", "") ?: ""
        if (isTv() && tvUrl.isNotBlank()) openTvDisplay(tvUrl)
        else if (isTv() && !configured) showTvSetup()
        else if (takeCall(intent) && !(prefs.getString("key", "").isNullOrBlank())) openPhoneOnly()
        else if (configured) startFriday() else showSettings("")
    }

    /** TV display: the address of the laptop's Friday screen arrives once through adb (--es tv_url "http://..."), no typing on the remote. */
    private fun takeTvUrl(intent: Intent?) {
        val url = intent?.getStringExtra("tv_url") ?: return
        intent.removeExtra("tv_url")
        prefs.edit().putString("tv_url", if (url == "off") "" else url.trim()).apply()
    }

    /** TV first run: type the laptop's address and code once (the remote's on-screen keyboard works). */
    private fun showTvSetup() {
        ui {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(dp(24), dp(12), dp(24), dp(0))
            val ip = EditText(this)
            ip.hint = "Laptop address, like 192.168.1.23"
            ip.setSingleLine(true)
            val code = EditText(this)
            code.hint = "Code from the laptop"
            code.setSingleLine(true)
            val old = prefs.getString("tv_url", "") ?: ""
            if (old.isNotBlank()) {
                ip.setText(Uri.parse(old).authority ?: "")
                code.setText(Uri.parse(old).getQueryParameter("t") ?: "")
            }
            box.addView(ip)
            box.addView(code)
            android.app.AlertDialog.Builder(this)
                .setTitle("Friday on this TV")
                .setMessage("On the laptop open Friday > Settings > Startup > Show Friday's screen on my TV, then type the address and code it shows.")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Show Friday") { _, _ ->
                    var host = ip.text.toString().trim().removePrefix("http://").substringBefore("/")
                    val c = code.text.toString().trim()
                    if (host.isNotEmpty() && c.isNotEmpty()) {
                        if (!host.contains(":")) host = host + ":8765"
                        val url = "http://" + host + "/?t=" + c + "&tv=1"
                        prefs.edit().putString("tv_url", url).apply()
                        openTvDisplay(url)
                    } else {
                        showTvSetup()
                    }
                }
                .setNeutralButton("Other settings") { _, _ -> tvMode = false; showSettings("") }
                .show()
        }
    }

    /** Show the laptop's Friday interface full screen. The laptop does the listening and talking; the TV is the screen. */
    private fun openTvDisplay(url: String) {
        ui {
            launchId++
            tvMode = true
            trustedHost = Uri.parse(url).host ?: ""
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            settings.visibility = View.GONE
            gear.visibility = View.GONE
            web.settings.textZoom = 100
            web.visibility = View.VISIBLE
            web.requestFocus()
            web.loadUrl(url)
        }
    }

    /** The user tapped Answer on a ringing call from Friday: wake the screen, stop the ring, remember why she called. */
    private fun takeCall(intent: Intent?): Boolean {
        val reason = intent?.getStringExtra("call_reason") ?: return false
        intent.removeExtra("call_reason")
        pendingCall = reason
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(CallService.RING_ID)
        } catch (e: Exception) { }
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        return true
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        takeTvUrl(intent)
        val tvUrl = prefs.getString("tv_url", "") ?: ""
        if (isTv() && tvUrl.isNotBlank()) { openTvDisplay(tvUrl); return }
        if (takeCall(intent) && !(prefs.getString("key", "").isNullOrBlank())) {
            // already open? tell the page; otherwise open the phone-only page, which asks for the call by itself
            val reason = pendingCall ?: ""
            if (web.visibility == View.VISIBLE && trustedHost.isEmpty()) {
                pendingCall = null
                web.evaluateJavascript("window.__friday&&window.__friday.incomingCall(" + JSONObject.quote(reason) + ")", null)
            } else {
                openPhoneOnly()
            }
        }
    }

    private fun label(text: String, size: Float, color: String, top: Int = 0): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(Color.parseColor(color))
        t.setPadding(0, dp(top), 0, 0)
        return t
    }

    private fun field(hint: String, value: String, secret: Boolean = false): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setHintTextColor(Color.parseColor("#8A877F"))
        e.setTextColor(Color.WHITE)
        e.setSingleLine(true)
        e.setText(value)
        if (secret) e.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        return e
    }

    private fun buildSettings(): ScrollView {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(40), dp(24), dp(32))

        col.addView(label("Friday", 30f, "#FFFFFF"))
        col.addView(label(
            "Friday picks by itself: your laptop when it can be reached, otherwise it talks to Google directly " +
                "(Wi-Fi or mobile data, no laptop needed).", 14f, "#A5A29A", 8))

        col.addView(label("How should it connect?", 15f, "#FFFFFF", 22))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val buttons = LinkedHashMap<String, Button>()
        for ((id, text) in listOf("auto" to "Auto", "phone" to "Phone only", "laptop" to "Laptop only")) {
            val b = Button(this)
            b.text = text
            b.isAllCaps = false
            b.setOnClickListener { setMode(id) }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            buttons[id] = b
        }
        modeButtons = buttons
        col.addView(row)

        col.addView(label("Laptop address(es), separated by commas", 15f, "#FFFFFF", 22))
        col.addView(label("Your home Wi-Fi address (shown in Friday's Phone link window) and, to reach it when you're out, " +
            "the 100.x.x.x Tailscale address.", 12f, "#A5A29A", 2))
        addresses = field("e.g. 192.168.1.23, 100.101.102.103", prefs.getString("addresses", "") ?: "")
        col.addView(addresses)

        col.addView(label("PIN from the Phone link window", 15f, "#FFFFFF", 14))
        pin = field("6 digits", prefs.getString("pin", "") ?: "", true)
        col.addView(pin)

        val copy = Button(this)
        copy.text = "Copy settings from laptop"
        copy.isAllCaps = false
        copy.setOnClickListener { provision() }
        val copyParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        copyParams.topMargin = dp(12)
        col.addView(copy, copyParams)
        col.addView(label("Do this once on home Wi-Fi with Friday running. It copies your Google key, your memories and voice " +
            "to this phone only, so Friday works without the laptop.", 12f, "#A5A29A", 2))

        col.addView(label("Let Friday use other apps (phone only mode)", 15f, "#FFFFFF", 24))
        col.addView(label("Friday can open apps and tap, type and scroll for you. It never touches banking, payment, investing, " +
            "password or authenticator apps, sign-in or code screens, or password boxes, and it asks you before anything that " +
            "sends, buys, deletes or posts.", 12f, "#A5A29A", 2))
        controlStatus = label("", 13f, "#A5A29A", 8)
        col.addView(controlStatus)
        fun action(text: String, run: () -> Unit) {
            val b = Button(this)
            b.text = text
            b.isAllCaps = false
            b.setOnClickListener { run() }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            col.addView(b, lp)
        }
        action("1. Allow restricted settings (Android 13+)") { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }
        action("2. Turn Friday on in Accessibility") { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, false) }
        action("3. Xiaomi: allow pop-ups in background") { xiaomiPermissions() }
        action("4. Battery: don't restrict Friday") { openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, false) }
        col.addView(label("Also never touch apps whose name contains (comma separated, optional)", 13f, "#FFFFFF", 12))
        blockedBox = field("e.g. tinder, wechat pay", prefs.getString("blocked_extra", "") ?: "")
        col.addView(blockedBox)

        col.addView(label("This phone's own memory", 15f, "#FFFFFF", 24))
        col.addView(label("By default this phone has its own separate memory, name and voice, and does not copy yours from the laptop. " +
            "Your smart home (TV, lights) is still shared.", 12f, "#A5A29A", 2))
        val sepButton = Button(this)
        sepButton.isAllCaps = false
        fun sepText() { sepButton.text = if (prefs.getBoolean("separate", true)) "Memory: separate from laptop (tap to share)" else "Memory: shared with laptop copy (tap to separate)" }
        sepText()
        sepButton.setOnClickListener {
            prefs.edit().putBoolean("separate", !prefs.getBoolean("separate", true)).apply()
            sepText()
        }
        col.addView(sepButton)
        action("Wipe this phone's memory and preferences now") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Wipe this phone's memory?")
                .setMessage("This forgets everything this phone has learned about you, its name, voice, style and old chats. " +
                    "It keeps your Google key and your smart-home link. The laptop is not touched.")
                .setPositiveButton("Wipe") { _, _ -> wipeProfile(); message.text = "Done. This phone is a fresh start." }
                .setNegativeButton("Cancel", null)
                .show()
        }

        col.addView(label("Smart home (Home Assistant, optional)", 15f, "#FFFFFF", 24))
        col.addView(label("Copied from the laptop too. Address like http://192.168.1.10:8123 and a long-lived token from your Home Assistant profile.", 12f, "#A5A29A", 2))
        haUrlBox = field("Home Assistant address", prefs.getString("ha_url", "") ?: "")
        col.addView(haUrlBox)
        haTokenBox = field("Home Assistant token", prefs.getString("ha_token", "") ?: "", true)
        col.addView(haTokenBox)

        col.addView(label("Friday calls you", 15f, "#FFFFFF", 24))
        col.addView(label("Say \"Friday, call me\" (or \"call me in 10 minutes\") on your laptop and this phone rings, like a call. " +
            "Answer, and Friday talks to you here, even on mobile data. The call code is copied from the laptop with \"Copy settings from laptop\", " +
            "or ask Friday \"set up phone calls\" and type the code here.", 12f, "#A5A29A", 2))
        callBox = field("Call code", prefs.getString("call_topic", "") ?: "")
        col.addView(callBox)
        val callButton = Button(this)
        callButton.isAllCaps = false
        fun callText() { callButton.text = if (prefs.getBoolean("calls_on", false)) "Calls: ON (tap to turn off)" else "Calls: off (tap to turn on)" }
        callText()
        callButton.setOnClickListener {
            saveFields()
            if (prefs.getBoolean("calls_on", false)) {
                prefs.edit().putBoolean("calls_on", false).apply()
                CallService.stop(this)
                message.setTextColor(Color.parseColor("#A5A29A"))
                message.text = "Calls are off."
            } else if ((prefs.getString("call_topic", "") ?: "").isBlank()) {
                message.setTextColor(Color.parseColor("#E5786B"))
                message.text = "Type the call code first (ask Friday: set up phone calls)."
            } else {
                prefs.edit().putBoolean("calls_on", true).apply()
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
                }
                try { CallService.start(this) } catch (e: Exception) { }
                message.setTextColor(Color.parseColor("#9BD6A8"))
                message.text = "Calls are on. Now do the two buttons below so it can ring when the screen is off."
            }
            callText()
        }
        col.addView(callButton)
        action("1. Allow ringing over the lock screen") {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                } else {
                    openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
                }
            } catch (e: Exception) {
                openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
            }
        }
        action("2. Let Friday run in the background (stops the phone killing it)") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
            }
        }
        action("Test: ring this phone in 5 seconds") {
            saveFields()
            val topic = (prefs.getString("call_topic", "") ?: "").trim()
            val server = (prefs.getString("call_server", "") ?: "").trim().ifEmpty { "https://ntfy.sh" }.trimEnd('/')
            if (topic.isEmpty()) {
                message.setTextColor(Color.parseColor("#E5786B"))
                message.text = "Type the call code first."
            } else {
                message.setTextColor(Color.parseColor("#A5A29A"))
                message.text = "Ringing in a few seconds. Lock the screen to see how it looks."
                Thread {
                    try {
                        val c = URL("$server/$topic").openConnection() as java.net.HttpURLConnection
                        c.requestMethod = "POST"
                        c.doOutput = true
                        c.setRequestProperty("Delay", "5s")
                        c.setRequestProperty("Priority", "5")
                        c.outputStream.use { it.write("This is a test call".toByteArray()) }
                        c.responseCode
                        c.disconnect()
                    } catch (e: Exception) {
                        ui { message.text = "Couldn't send the test: ${e.message}" }
                    }
                }.start()
            }
        }

        col.addView(label("Google key (only if you don't copy it)", 15f, "#FFFFFF", 18))
        keyBox = field("Gemini API key", prefs.getString("key", "") ?: "", true)
        col.addView(keyBox)

        val start = Button(this)
        start.text = "Save and start"
        start.isAllCaps = false
        start.setOnClickListener { saveFields(); startFriday() }
        val startParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        startParams.topMargin = dp(22)
        col.addView(start, startParams)

        message = label("", 14f, "#E5786B", 14)
        message.gravity = Gravity.CENTER
        col.addView(message)

        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#262624"))
        sv.addView(col)
        refreshModeButtons()
        return sv
    }

    private fun openSettings(action: String, thisApp: Boolean) {
        try {
            val i = Intent(action)
            if (thisApp) i.data = Uri.parse("package:$packageName")
            startActivity(i)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun xiaomiPermissions() {
        try {
            val i = Intent("miui.intent.action.APP_PERM_EDITOR")
            i.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            i.putExtra("extra_pkgname", packageName)
            startActivity(i)
        } catch (e: Exception) {
            openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::controlStatus.isInitialized) {
            val on = FridayAccessibilityService.instance != null
            controlStatus.text = if (on) "Using other apps: ON" else "Using other apps: off (do steps 1 to 4)"
            controlStatus.setTextColor(Color.parseColor(if (on) "#9BD6A8" else "#A5A29A"))
        }
    }

    private fun setMode(id: String) {
        mode = id
        prefs.edit().putString("mode", id).apply()
        refreshModeButtons()
    }

    private fun refreshModeButtons() {
        for ((id, b) in modeButtons) {
            b.alpha = if (id == mode) 1f else 0.45f
        }
    }

    private fun saveFields() {
        prefs.edit()
            .putString("addresses", addresses.text.toString().trim())
            .putString("pin", pin.text.toString().trim())
            .putString("key", keyBox.text.toString().trim())
            .putString("ha_url", haUrlBox.text.toString().trim().trimEnd('/'))
            .putString("ha_token", haTokenBox.text.toString().trim())
            .putString("call_topic", callBox.text.toString().trim())
            .putString("blocked_extra", blockedBox.text.toString().trim())
            .apply()
    }

    private fun showSettings(problem: String) {
        ui {
            launchId++
            web.loadUrl("about:blank")
            web.removeJavascriptInterface("Android")
            web.visibility = View.GONE
            gear.visibility = View.GONE
            settings.visibility = View.VISIBLE
            message.setTextColor(Color.parseColor("#E5786B"))
            message.text = problem
        }
    }

    // ---------------------------------------------------------------- choosing laptop or phone-only
    private fun addressList(): List<String> =
        (prefs.getString("addresses", "") ?: "").split(",", ";", " ")
            .map { it.trim().removePrefix("https://").removePrefix("http://").trimEnd('/') }
            .filter { it.isNotEmpty() }
            .map { if (it.contains(":")) it else "$it:8765" }

    private fun reachable(address: String): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(address.substringBefore(":"), address.substringAfter(":").toInt()), 2500)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun startFriday() {
        val id = ++launchId
        val hasKey = !(prefs.getString("key", "").isNullOrBlank())
        val list = addressList()
        settings.visibility = View.GONE
        if (mode == "phone" || (mode == "auto" && list.isEmpty())) {
            if (hasKey) openPhoneOnly() else showSettings("Phone-only mode needs your Google key. Use \"Copy settings from laptop\" or paste it.")
            return
        }
        if (list.isEmpty()) {
            showSettings("Type your laptop's address first.")
            return
        }
        web.visibility = View.VISIBLE
        gear.visibility = View.VISIBLE
        web.loadDataWithBaseURL("https://friday.local/",
            "<body style='background:#262624;color:#A5A29A;font-family:sans-serif;text-align:center;padding-top:40vh'>Looking for your laptop…</body>",
            "text/html", "utf-8", null)
        Thread {
            val found = list.firstOrNull { reachable(it) }
            ui {
                if (id != launchId) return@ui
                if (found != null) {
                    openLaptop(found)
                } else if (mode == "auto" && hasKey) {
                    openPhoneOnly()
                } else if (mode == "auto") {
                    showSettings("Couldn't reach your laptop, and there's no Google key on this phone yet. " +
                        "Copy settings from the laptop while you're on its Wi-Fi.")
                } else {
                    showSettings("Couldn't reach your laptop at ${list.joinToString(", ")}. Is Friday running on it?")
                }
            }
        }.start()
    }

    private fun openLaptop(address: String) {
        trustedHost = address.substringBefore(":")
        web.removeJavascriptInterface("Android")
        settings.visibility = View.GONE
        web.visibility = View.VISIBLE
        gear.visibility = View.VISIBLE
        web.loadUrl("https://$address/")
    }

    private fun openPhoneOnly() {
        trustedHost = ""
        web.removeJavascriptInterface("Android")
        web.addJavascriptInterface(Bridge(), "Android")
        val html = assets.open("standalone.html").bufferedReader().use { it.readText() }
        settings.visibility = View.GONE
        web.visibility = View.VISIBLE
        gear.visibility = View.GONE
        web.loadDataWithBaseURL("https://friday.local/", html, "text/html", "utf-8", null)
    }

    // ---------------------------------------------------------------- copy settings from the laptop
    private fun provision() {
        saveFields()
        val list = addressList()
        val pinText = pin.text.toString().trim()
        if (list.isEmpty() || pinText.isEmpty()) {
            message.setTextColor(Color.parseColor("#E5786B"))
            message.text = "Type the laptop address and the PIN first."
            return
        }
        message.setTextColor(Color.parseColor("#A5A29A"))
        message.text = "Contacting your laptop…"
        Thread {
            var result = ""
            var ok = false
            for (address in list) {
                try {
                    val body = fetchProvision(address, pinText)
                    val o = JSONObject(body)
                    val edit = prefs.edit()
                    edit.putString("key", o.optString("key", ""))
                    edit.putString("model", o.optString("model", ""))
                    // Memories, name, voice and style are the laptop's own. By default this phone keeps its own, empty ones.
                    if (!prefs.getBoolean("separate", true)) {
                        edit.putString("name", o.optString("name", "Friday"))
                        edit.putString("voice", o.optString("voice", ""))
                        edit.putString("language", o.optString("language", "en-US"))
                        edit.putString("style", o.optString("style", ""))
                        edit.putString("memory", (o.optJSONArray("memory") ?: JSONArray()).toString())
                    }
                    if (o.has("call_topic")) {
                        edit.putString("call_topic", o.optString("call_topic", ""))
                        edit.putString("call_server", o.optString("call_server", ""))
                    }
                    if (o.has("ha_url")) edit.putString("ha_url", o.optString("ha_url", ""))
                    if (o.has("ha_token")) edit.putString("ha_token", o.optString("ha_token", ""))
                    if (o.has("devices")) edit.putString("devices", (o.optJSONObject("devices") ?: JSONObject()).toString())
                    edit.apply()
                    ok = true
                    result = "Copied. This phone can now work on its own."
                    break
                } catch (e: Exception) {
                    result = "Couldn't copy from $address: ${e.message ?: "no answer"}"
                }
            }
            ui {
                message.setTextColor(Color.parseColor(if (ok) "#9BD6A8" else "#E5786B"))
                message.text = result
                if (ok) {
                    keyBox.setText(prefs.getString("key", ""))
                    haUrlBox.setText(prefs.getString("ha_url", ""))
                    haTokenBox.setText(prefs.getString("ha_token", ""))
                    callBox.setText(prefs.getString("call_topic", ""))
                }
            }
        }.start()
    }

    /** One HTTPS request to the laptop. Its certificate is self-made, so it is trusted for this one host only. */
    private fun fetchProvision(address: String, pinText: String): String {
        val host = address.substringBefore(":")
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sc = SSLContext.getInstance("TLS")
        sc.init(null, trustAll, SecureRandom())
        val conn = URL("https://$address/provision").openConnection() as HttpsURLConnection
        conn.sslSocketFactory = sc.socketFactory
        conn.hostnameVerifier = HostnameVerifier { name, _ -> name == host }
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.setRequestProperty("X-Friday-Pin", pinText)
        try {
            val code = conn.responseCode
            if (code == 403) throw Exception("wrong PIN")
            if (code == 429) throw Exception("too many wrong PINs, wait a minute")
            if (code != 200) throw Exception("laptop said $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            val request = pendingGrant
            pendingGrant = null
            if (request != null) {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    /** True on Android TV / Google TV. */
    private fun isTv(): Boolean = try {
        (getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    } catch (e: Exception) { false }

    /** TV remote: the MENU (or the "settings"/"*") button opens settings, since there is no touch screen for the gear. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU || keyCode == android.view.KeyEvent.KEYCODE_SETTINGS ||
            keyCode == android.view.KeyEvent.KEYCODE_STAR || keyCode == android.view.KeyEvent.KEYCODE_BUTTON_Y) {
            if (isTv() && tvMode) {
                tvMode = false
                web.loadUrl("about:blank")
                showTvSetup()
            } else {
                showSettings("")
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        if (web.visibility == View.VISIBLE) {
            showSettings("")        // back stops listening and returns to the settings screen
        } else {
            super.onBackPressed()
        }
    }
}
