package com.shohan.deadzonemirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * This service does not read screen content. Its only job is to replay
 * gestures (taps, drags, swipes) at real screen coordinates on Shohan's
 * behalf, computed from what he touches inside the floating mirror overlay.
 * Injected gestures work even over the dead zone, because dispatchGesture
 * is a software-level input injection and does not depend on the physical
 * digitizer at that location.
 */
class TouchAccessibilityService : AccessibilityService() {

    companion object {
        var instance: TouchAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No window content is read; this service only performs gestures.
    }

    override fun onInterrupt() {
        // Nothing to interrupt.
    }

    /**
     * Replays a full down-to-up touch path (built up while the user's
     * finger was moving across the overlay) as one continuous gesture on
     * the real screen, over the same total duration it actually took.
     */
    fun dispatchPathGesture(path: Path, durationMs: Long): Boolean {
        return try {
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            false
        }
    }
}
