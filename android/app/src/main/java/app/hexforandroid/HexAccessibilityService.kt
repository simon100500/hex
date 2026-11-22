package app.hexforandroid

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Owns the floating bubble (an accessibility overlay, so it needs no
 * draw-over-apps permission and floats above the keyboard) and inserts
 * finished dictations into the focused field.
 */
class HexAccessibilityService : AccessibilityService() {
    private var bubble: Bubble? = null
    private var shown: List<Any?>? = null
    private val listener: (Dictation.State) -> Unit = { state ->
        bubble?.render(state)
        // Visibility depends on the phase, not on the 50 Hz level.
        if (state.shape != shown) {
            shown = state.shape
            refreshBubble()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Dictation.init(this)
        bubble = Bubble(this, Controls(), ::imeBounds)
        Dictation.listen(listener)
        refreshBubble()
    }

    /** The last field the user focused, clicked or edited: a fallback for editors that hide their input focus. */
    var lastField: AccessibilityNodeInfo? = null
        private set

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        ) {
            event.source?.takeIf { it.packageName != packageName && Inserter.takesText(it) }?.let { lastField = it }
        }
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) {
            scheduleBubble()
        }
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bubble?.hide()
        scheduleBubble()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        main.removeCallbacks(settle)
        // Without the service there's no bubble to stop a recording with.
        Dictation.cancel()
        lastField = null
        Dictation.unlisten(listener)
        bubble?.hide()
        bubble = null
        instance = null
        return super.onUnbind(intent)
    }

    /** Settings changed the bubble's shape or size. */
    fun restyleBubble() = bubble?.restyle()

    /** The keyboard window's bounds, or null while no keyboard is open. */
    private fun imeBounds(): Rect? = runCatching {
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { window ->
            Rect().also(window::getBoundsInScreen)
        }
    }.getOrNull()

    /** An editable field anywhere in the active app: the key exists only for one. */
    private fun hasField(): Boolean {
        val focus = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (focus != null && focus.packageName != packageName && Inserter.takesText(focus)) return true
        return lastField?.let { node ->
            runCatching {
                node.packageName != packageName && node.refresh() && node.isVisibleToUser && Inserter.takesText(node)
            }.getOrDefault(false)
        } == true
    }

    /** Shows the key only while Hex is on, a text field has focus, and the keyboard is open. */
    fun refreshBubble() {
        main.removeCallbacks(settle)
        applyBubble()
    }

    /**
     * The keyboard animates in and out over several frames; placing the key on
     * every window event made it chase the animation. Wait until the window
     * bounds stop changing, then place once. A closed keyboard hides at once.
     */
    private fun scheduleBubble() {
        val bubble = bubble ?: return
        main.removeCallbacks(settle)
        if (imeBounds() == null) {
            bubble.hide()
            return
        }
        main.postDelayed(settle, SETTLE_MS)
    }

    private fun applyBubble() {
        val bubble = bubble ?: return
        if (bubbleWanted()) bubble.show() else bubble.hide()
    }

    private fun bubbleWanted(): Boolean {
        val settings = Settings(this)
        val busy = Dictation.state.phase == Dictation.Phase.Recording || Dictation.state.phase == Dictation.Phase.Transcribing
        return settings.enabled && (busy || (imeBounds() != null && hasField()))
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val settle = Runnable { applyBubble() }

    /** What the bubble's gestures do. */
    inner class Controls {
        /** Press: start recording, or re-arm Hex if Android stopped it. */
        fun press(): Boolean {
            if (!Dictation.armed) {
                try {
                    startActivity(Intent(this@HexAccessibilityService, ArmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@HexAccessibilityService, "Open Hex to turn it on", android.widget.Toast.LENGTH_SHORT).show()
                }
                return false
            }
            return Dictation.start()
        }

        fun finish() = Dictation.finish { text -> Delivery.deliver(this@HexAccessibilityService, text) }

        fun cancel() = Dictation.cancel()
    }

    companion object {
        private const val SETTLE_MS = 220L

        @Volatile var instance: HexAccessibilityService? = null
            private set
    }
}
