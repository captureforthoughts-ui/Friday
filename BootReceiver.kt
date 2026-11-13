package app.friday.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a restart, start listening for calls again (only if the user turned calls on). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("friday", Context.MODE_PRIVATE)
        if (prefs.getBoolean("calls_on", false) && !(prefs.getString("call_topic", "").isNullOrBlank())) {
            try { CallService.start(context) } catch (e: Exception) { }
        }
    }
}
