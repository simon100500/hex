package app.hexforandroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.min

/**
 * The small microphone key the overlay shows in the keyboard's suggestion
 * strip. It is a plain rounded key, not Hex's dot scope: next to Yandex
 * Keyboard's own keys it has to read as one of them.
 */
class MicKeyView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val spinner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val capsule = RectF()
    private val holder = RectF()

    private var dark = Tokens.of(context).dark
    private var phase = Dictation.Phase.Off
    private var level = 0f
    private var shownAt = 0L

    init {
        contentDescription = "GigaAM dictation. Tap to start or stop, hold to talk."
    }

    fun applyTheme(dark: Boolean) {
        this.dark = dark
        invalidate()
    }

    fun update(state: Dictation.State) {
        if (state.phase != phase) shownAt = SystemClock.uptimeMillis()
        phase = state.phase
        level = state.level
        if (phase == Dictation.Phase.Recording || phase == Dictation.Phase.Transcribing ||
            phase == Dictation.Phase.Loading
        ) postInvalidateOnAnimation() else invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(SIZE_DP).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val t = Tokens(dark)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - dp(2f)

        val recording = phase == Dictation.Phase.Recording
        val busy = phase == Dictation.Phase.Transcribing || phase == Dictation.Phase.Loading
        val plateColor = when {
            recording -> t.danger
            busy -> t.surface
            else -> t.surface
        }
        plate.color = plateColor
        canvas.drawCircle(cx, cy, radius, plate)

        rim.strokeWidth = dp(1f)
        rim.color = when {
            recording -> Color.argb(90, 255, 255, 255)
            busy -> t.border
            else -> t.border
        }
        canvas.drawCircle(cx, cy, radius, rim)

        if (recording) {
            // A coral halo that follows the voice, so the key reads as live.
            rim.strokeWidth = dp(2f)
            rim.color = Color.argb((40 + 120 * level.coerceIn(0f, 1f)).toInt(), 255, 255, 255)
            canvas.drawCircle(cx, cy, radius + dp(2.5f), rim)
        }

        val ink = when {
            recording -> Color.WHITE
            busy -> t.muted
            else -> t.text
        }
        glyph.color = ink
        glyph.strokeWidth = dp(1.9f)
        val top = cy - dp(8f)
        val bottom = cy + dp(3f)
        capsule.set(cx - dp(4.2f), top, cx + dp(4.2f), bottom)
        canvas.drawRoundRect(capsule, dp(4.2f), dp(4.2f), glyph)

        holder.set(cx - dp(7.5f), cy - dp(2.5f), cx + dp(7.5f), cy + dp(7.5f))
        canvas.drawArc(holder, 0f, 180f, false, glyph)
        canvas.drawLine(cx, cy + dp(7.5f), cx, cy + dp(11f), glyph)
        canvas.drawLine(cx - dp(3.5f), cy + dp(11f), cx + dp(3.5f), cy + dp(11f), glyph)

        if (phase == Dictation.Phase.Transcribing || phase == Dictation.Phase.Loading) {
            val start = ((SystemClock.uptimeMillis() - shownAt) % 900L) / 900f * 360f
            spinner.strokeWidth = dp(2f)
            spinner.color = if (phase == Dictation.Phase.Transcribing) t.accent else t.faint
            canvas.drawArc(
                RectF(cx - radius + dp(3f), cy - radius + dp(3f), cx + radius - dp(3f), cy + radius - dp(3f)),
                start, 110f, false, spinner,
            )
        }

        if (phase == Dictation.Phase.Recording || phase == Dictation.Phase.Transcribing ||
            phase == Dictation.Phase.Loading
        ) postInvalidateOnAnimation()
    }

    companion object {
        /** The key's touch target; the plate inside it is about 4 dp smaller. */
        const val SIZE_DP = 40f
    }
}
