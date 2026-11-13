package app.friday.phone

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/** The part of the app Android lets read and tap the screen, once you switch it on in Accessibility settings. */
class FridayAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: FridayAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nothing is recorded or watched in the background. The service only acts when Friday asks it to.
    }

    override fun onInterrupt() {}
}
