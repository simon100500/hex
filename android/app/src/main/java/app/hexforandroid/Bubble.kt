package app.hexforandroid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.hypot

/**
 * The dictation key, an accessibility overlay pinned to the keyboard's
 * suggestion strip:
 *
 * - Tap: start recording; tap again to stop, transcribe and insert.
 * - Hold: record while held; lift to stop and insert.
 * - Long-press while a tapped recording runs: cancel it.
 *
 * It exists only while a text field has focus and the keyboard is open; when
 * either goes away [HexAccessibilityService] hides it.
 */
class Bubble(
    private val context: Context,
    private val controls: HexAccessibilityService.Controls,
    private val keyboardBounds: () -> Rect?,
) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val settings = Settings(context)
    private val key = MicKeyView(context)
    private var shown = false
    private var lastMessage: String? = null

    private val params = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "GigaAM dictation"
    }

    init {
        key.setOnTouchListener(Gestures())
        // TalkBack's activate gesture arrives as a click: toggle recording.
        key.setOnClickListener {
            if (Dictation.recording) controls.finish() else controls.press()
        }
        key.applyTheme(Tokens.of(context).dark)
        applyStyle()
    }

    /** The shape and size the settings chose. */
    private fun applyStyle() {
        key.shape = BubbleShape.of(settings.keyShape)
        key.sizeDp = MicKeyView.sizeFor(settings.keySize)
        key.invalidate()
    }

    /** The settings changed the key's look or place. */
    fun restyle() {
        applyStyle()
        resize()
        if (shown) place()
    }

    fun show() {
        if (shown) {
            // The keyboard moved (rotation, a taller strip): follow it.
            if (!place()) hide()
            return
        }
        if (keyboardBounds() == null) return
        resize()
        if (!place()) return
        windows.addView(key, params)
        shown = true
        key.update(Dictation.state)
    }

    fun hide() {
        if (!shown) return
        windows.removeViewImmediate(key)
        shown = false
    }

    /** The screen changed: folded or unfolded, rotated, or switched light/dark. */
    fun keepOnScreen() {
        key.applyTheme(Tokens.of(context).dark)
        if (shown && !place()) hide()
    }

    fun render(state: Dictation.State) {
        key.update(state)
        val message = state.message
        if (message != null && message != lastMessage && shown) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        lastMessage = message
    }

    private fun resize() {
        val size = (key.sizeDp * context.resources.displayMetrics.density).toInt()
        params.width = size
        params.height = size
    }

    /**
     * Puts the key in the suggestion strip's trailing end: right-aligned, a
     * small margin in, centred in the strip's height. Returns false when the
     * keyboard has no bounds to sit in.
     */
    private fun place(): Boolean {
        val bounds = keyboardBounds() ?: return false
        val screen = windows.currentWindowMetrics.bounds
        val density = context.resources.displayMetrics.density
        val margin = (4 * density).toInt()
        val offsetX = (settings.keyOffsetX * density).toInt()
        val offsetY = (settings.keyOffsetY * density).toInt()
        val strip = (44 * density).toInt()
        val x = bounds.right - params.width - margin + offsetX
        val y = bounds.top + maxOf(0, (strip - params.height) / 2) + offsetY
        params.x = x.coerceIn(screen.left, maxOf(screen.left, screen.right - params.width))
        params.y = y.coerceIn(screen.top, maxOf(screen.top, screen.bottom - params.height))
        if (shown) windows.updateViewLayout(key, params)
        return true
    }

    private inner class Gestures : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downAt = 0L
        private var downX = 0f
        private var downY = 0f
        /** This press started the recording (tap-to-lock or hold-to-talk). */
        private var startedHere = false
        /** A tapped recording keeps running after the finger lifts. */
        private var locked = false
        private var consumed = false

        private val longPress = Runnable {
            if (!startedHere && locked && Dictation.recording) {
                consumed = true
                locked = false
                controls.cancel()
                key.performHapticFeedback(HapticFeedbackConstants.REJECT)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis()
                    downX = event.rawX
                    downY = event.rawY
                    consumed = false
                    startedHere = false
                    if (!Dictation.recording && Dictation.state.phase != Dictation.Phase.Transcribing) {
                        startedHere = controls.press()
                        locked = false
                        if (startedHere) v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }
                    v.postDelayed(longPress, CANCEL_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    // The key stays put; a finger that wanders still talks.
                    if (startedHere && hypot(event.rawX - downX, event.rawY - downY) > slop * 3) {
                        v.removeCallbacks(longPress)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    if (consumed || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        if (startedHere) controls.cancel()
                        return true
                    }
                    val held = SystemClock.uptimeMillis() - downAt
                    when {
                        startedHere && held >= HOLD_MS -> {
                            controls.finish()
                            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        }
                        startedHere -> locked = true
                        locked && Dictation.recording -> {
                            locked = false
                            controls.finish()
                            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        }
                    }
                }
            }
            return true
        }
    }

    companion object {
        /** A press at least this long before lifting is hold-to-talk. */
        const val HOLD_MS = 350L
        /** Holding a tapped recording this long cancels it. */
        const val CANCEL_MS = 600L
    }
}
