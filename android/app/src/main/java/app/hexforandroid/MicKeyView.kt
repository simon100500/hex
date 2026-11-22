package app.hexforandroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The small dictation key the overlay shows in the keyboard's suggestion
 * strip. Idle it is a microphone; while recording its plate turns coral and a
 * live sound wave replaces the glyph, in the spirit of the original Hex
 * bubble's dot scope.
 */
class MicKeyView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    /** The plate's outline, the same choices the old bubble had. */
    var shape = BubbleShape.Circle
        set(value) {
            field = value
            invalidate()
        }

    /** The view's size in dp; the plate is 4 dp smaller. */
    var sizeDp = 36f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }
    private val spinner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val capsule = RectF()
    private val holder = RectF()
    private val outline = Path()

    private var dark = Tokens.of(context).dark
    private var tokens = Tokens(dark)
    private var phase = Dictation.Phase.Off
    private var level = 0f
    private var shownAt = 0L

    init {
        contentDescription = "GigaAM dictation. Tap to start or stop, hold to talk."
    }

    fun applyTheme(dark: Boolean) {
        this.dark = dark
        tokens = Tokens(dark)
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
        val size = dp(sizeDp).toInt()
        setMeasuredDimension(size, size)
    }

    /** The plate's outline at the current shape, inset for its rim. */
    private fun platePath(cx: Float, cy: Float, radius: Float): Path {
        outline.reset()
        when (shape) {
            BubbleShape.Circle -> outline.addCircle(cx, cy, radius, Path.Direction.CW)
            BubbleShape.Square -> {
                val r = radius * 0.42f
                val box = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
                outline.addRoundRect(box, r, r, Path.Direction.CW)
            }
            BubbleShape.Hexagon -> {
                for (i in 0 until 6) {
                    val angle = (PI / 3 * i - PI / 6).toFloat()
                    val x = cx + radius * kotlin.math.cos(angle)
                    val y = cy + radius * kotlin.math.sin(angle)
                    if (i == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
                }
                outline.close()
            }
            BubbleShape.Pill -> {
                val r = radius * 0.55f
                val box = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
                outline.addRoundRect(box, r, r, Path.Direction.CW)
            }
        }
        return outline
    }

    override fun onDraw(canvas: Canvas) {
        val t = tokens
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - dp(2f)
        val k = sizeDp / 40f

        val recording = phase == Dictation.Phase.Recording
        val busy = phase == Dictation.Phase.Transcribing || phase == Dictation.Phase.Loading

        plate.color = if (recording) t.danger else t.surface
        canvas.drawPath(platePath(cx, cy, radius), plate)

        rim.strokeWidth = dp(1f)
        rim.color = if (recording) Color.argb(90, 255, 255, 255) else t.border
        canvas.drawPath(platePath(cx, cy, radius), rim)

        if (recording) {
            // A coral halo that follows the voice, so the key reads as live.
            rim.strokeWidth = dp(2f)
            rim.color = Color.argb((40 + 150 * (level * 8f).coerceIn(0f, 1f)).toInt(), 255, 255, 255)
            canvas.drawCircle(cx, cy, radius + dp(2.5f), rim)
        }

        if (recording) {
            // Five rounded bars whose height follows the microphone level.
            val bars = 5
            val width = dp(3f * k)
            val gap = dp(2.6f * k)
            val total = bars * width + (bars - 1) * gap
            val now = SystemClock.uptimeMillis() / 1000f
            val loud = (level * 8f).coerceIn(0f, 1f)
            wave.color = Color.WHITE
            for (i in 0 until bars) {
                val phase = now * 9f + i * 0.85f
                val swing = 0.55f + 0.45f * sin(phase).toFloat()
                val h = dp((3.5f + 14f * (0.22f + 0.78f * loud) * swing) * k)
                val left = cx - total / 2 + i * (width + gap)
                canvas.drawRoundRect(RectF(left, cy - h / 2, left + width, cy + h / 2), width / 2, width / 2, wave)
            }
        } else {
            val ink = if (busy) t.muted else t.text
            glyph.color = ink
            glyph.strokeWidth = dp(1.9f * k)
            val top = cy - dp(8f * k)
            val bottom = cy + dp(3f * k)
            capsule.set(cx - dp(4.2f * k), top, cx + dp(4.2f * k), bottom)
            canvas.drawRoundRect(capsule, dp(4.2f * k), dp(4.2f * k), glyph)

            holder.set(cx - dp(7.5f * k), cy - dp(2.5f * k), cx + dp(7.5f * k), cy + dp(7.5f * k))
            canvas.drawArc(holder, 0f, 180f, false, glyph)
            canvas.drawLine(cx, cy + dp(7.5f * k), cx, cy + dp(11f * k), glyph)
            canvas.drawLine(cx - dp(3.5f * k), cy + dp(11f * k), cx + dp(3.5f * k), cy + dp(11f * k), glyph)
        }

        if (phase == Dictation.Phase.Transcribing || phase == Dictation.Phase.Loading) {
            val start = ((SystemClock.uptimeMillis() - shownAt) % 900L) / 900f * 360f
            spinner.strokeWidth = dp(2f * k)
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
        /** The key's default touch target, in dp. */
        const val SIZE_DP = 36f

        fun sizeFor(name: String) = when (name) {
            "S" -> 32f
            "L" -> 40f
            else -> SIZE_DP
        }
    }
}
