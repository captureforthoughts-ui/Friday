package app.friday.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lets Friday ring this phone like a call. It keeps one quiet connection open to your private channel on ntfy.sh
 * (the laptop posts there when you say "call me"). A message shows a ringing notification with Answer and Decline.
 * Answer opens Friday, which says hello and why she called. Works on mobile data. Turn it off in Friday's settings.
 */
class CallService : Service() {

    companion object {
        const val CH_WATCH = "friday_call_watch"
        const val CH_RING = "friday_ring_v1"
        const val CH_MISSED = "friday_missed"
        const val WATCH_ID = 21
        const val RING_ID = 22
        const val MISSED_ID = 23
        const val ACTION_DECLINE = "app.friday.phone.DECLINE"
        const val ACTION_STOP = "app.friday.phone.CALLS_OFF"

        fun start(context: Context) {
            val i = Intent(context, CallService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }

    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (intent?.action == ACTION_DECLINE) {
            nm.cancel(RING_ID)
            return START_STICKY
        }
        makeChannels(nm)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH_WATCH) else Notification.Builder(this)
        val watching = builder
            .setContentTitle("Friday can call you")
            .setContentText("Waiting for a call from Friday. Tap to open.")
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(WATCH_ID, watching, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(WATCH_ID, watching)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            worker = Thread { listen() }.also { it.isDaemon = true; it.start() }
        }
        return START_STICKY
    }

    private fun makeChannels(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCH, "Friday can call you", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shows while Friday is waiting to call you."
            },
        )
        val ring = NotificationChannel(CH_RING, "Calls from Friday", NotificationManager.IMPORTANCE_HIGH)
        ring.description = "Rings when Friday calls you."
        ring.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        ring.enableVibration(true)
        ring.vibrationPattern = longArrayOf(0, 900, 700, 900, 700, 900)
        ring.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ring)
        nm.createNotificationChannel(NotificationChannel(CH_MISSED, "Missed calls from Friday", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** One long-lived request to the channel. Reconnects by itself, so a flaky signal just means a short delay. */
    private fun listen() {
        val prefs = getSharedPreferences("friday", MODE_PRIVATE)
        var backoff = 3000L
        while (running) {
            val topic = (prefs.getString("call_topic", "") ?: "").trim()
            val server = (prefs.getString("call_server", "") ?: "").trim().ifEmpty { "https://ntfy.sh" }.trimEnd('/')
            if (topic.isEmpty()) {
                sleep(20000)
                continue
            }
            var conn: HttpURLConnection? = null
            try {
                val last = prefs.getString("call_last", "") ?: ""
                val since = if (last.isNotEmpty()) last else (System.currentTimeMillis() / 1000).toString()
                conn = URL("$server/$topic/json?since=$since").openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 120000        // the server sends a keep-alive about every 45 seconds
                conn.setRequestProperty("Accept", "application/x-ndjson")
                if (conn.responseCode != 200) throw Exception("server said ${conn.responseCode}")
                backoff = 3000L
                conn.inputStream.bufferedReader().use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val o = try { JSONObject(line) } catch (e: Exception) { continue }
                        if (o.optString("event") != "message") continue
                        val id = o.optString("id", "")
                        if (id.isNotEmpty()) prefs.edit().putString("call_last", id).apply()
                        val sentAt = o.optLong("time", System.currentTimeMillis() / 1000) * 1000
                        val reason = o.optString("message", "").ifBlank { "Friday wants to talk" }
                        if (System.currentTimeMillis() - sentAt > 10 * 60 * 1000L) missed(reason) else ring(reason)
                    }
                }
            } catch (e: Exception) {
                // network gone, phone asleep, server hiccup: wait a little and try again
            } finally {
                try { conn?.disconnect() } catch (e: Exception) { }
            }
            sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(60000L)
        }
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { }
    }

    private fun ring(reason: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val answerIntent = Intent(this, MainActivity::class.java)
            .putExtra("call_reason", reason)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val answer = PendingIntent.getActivity(this, 31, answerIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val decline = PendingIntent.getService(
            this, 32, Intent(this, CallService::class.java).setAction(ACTION_DECLINE), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH_RING) else Notification.Builder(this)
        builder
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Friday is calling")
            .setContentText(reason)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(answer)
            .setFullScreenIntent(answer, true)
            .addAction(Notification.Action.Builder(null, "Answer", answer).build())
            .addAction(Notification.Action.Builder(null, "Decline", decline).build())
        if (Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter(45000)
        if (Build.VERSION.SDK_INT < 26) {
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            builder.setVibrate(longArrayOf(0, 900, 700, 900, 700, 900))
            builder.setPriority(Notification.PRIORITY_MAX)
        }
        val n = builder.build()
        n.flags = n.flags or Notification.FLAG_INSISTENT     // keep ringing until answered, declined or 45 seconds pass
        try {
            nm.notify(RING_ID, n)
        } catch (e: SecurityException) {
            // notifications not allowed: nothing to ring with
        }
    }

    private fun missed(reason: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH_MISSED) else Notification.Builder(this)
        val n = builder.setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Missed call from Friday")
            .setContentText(reason)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(MISSED_ID, n) } catch (e: SecurityException) { }
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }
}
