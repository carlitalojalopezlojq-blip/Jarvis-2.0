package com.giancarlos.jarvissbn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class ArcReactorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State { IDLE, WAKE_LISTENING, LISTENING, THINKING, SPEAKING }

    private var primaryColor = Color.parseColor("#00E5FF")
    private var secondaryColor = Color.parseColor("#64FFDA")
    private var state = State.IDLE
    private var angle = 0f
    private var pulse = 0f
    private var audioLevel = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val oval = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 6000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val speed = when (state) {
                State.IDLE -> 1.0f
                State.WAKE_LISTENING -> 1.6f
                State.LISTENING -> 2.8f
                State.THINKING -> 4.5f
                State.SPEAKING -> 3.2f
            }
            angle = (angle + speed) % 360f
            pulse = (sin(Math.toRadians((angle * 4).toDouble())).toFloat() + 1f) / 2f
            invalidate()
        }
    }

    init {
        animator.start()
    }

    fun setState(newState: State) {
        state = newState
        invalidate()
    }

    fun setThemeColor(primaryHex: String, secondaryHex: String) {
        primaryColor = Color.parseColor(primaryHex)
        secondaryColor = Color.parseColor(secondaryHex)
        invalidate()
    }

    fun setAudioLevel(rmsdB: Float) {
        audioLevel = ((rmsdB.coerceIn(0f, 12f)) / 12f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(cx, cy) - 12f

        // Halo exterior
        ringPaint.color = primaryColor
        ringPaint.alpha = 70
        ringPaint.strokeWidth = 3f
        canvas.drawCircle(cx, cy, maxR, ringPaint)

        // Anillo segmentado exterior giratorio
        ringPaint.color = primaryColor
        ringPaint.alpha = 220
        ringPaint.strokeWidth = 6f
        val r1 = maxR * 0.88f
        oval.set(cx - r1, cy - r1, cx + r1, cy + r1)
        for (i in 0 until 6) {
            canvas.drawArc(oval, angle + i * 60f, 38f, false, ringPaint)
        }

        // Anillo intermedio en dirección opuesta
        ringPaint.color = secondaryColor
        ringPaint.alpha = 180
        ringPaint.strokeWidth = 4f
        val r2 = maxR * 0.72f
        oval.set(cx - r2, cy - r2, cx + r2, cy + r2)
        for (i in 0 until 4) {
            canvas.drawArc(oval, -angle * 1.4f + i * 90f, 55f, false, ringPaint)
        }

        // Marcas radiales HUD
        ringPaint.color = primaryColor
        ringPaint.alpha = 140
        ringPaint.strokeWidth = 2f
        val tickInner = maxR * 0.58f
        val tickOuter = maxR * 0.65f
        for (i in 0 until 24) {
            val rad = Math.toRadians((i * 15f + angle * 0.5f).toDouble())
            val sx = cx + (tickInner * cos(rad)).toFloat()
            val sy = cy + (tickInner * sin(rad)).toFloat()
            val ex = cx + (tickOuter * cos(rad)).toFloat()
            val ey = cy + (tickOuter * sin(rad)).toFloat()
            canvas.drawLine(sx, sy, ex, ey, ringPaint)
        }

        // Núcleo central pulsante
        val extra = if (state == State.LISTENING || state == State.SPEAKING) {
            (audioLevel * 14f) + (pulse * 8f)
        } else {
            pulse * 5f
        }
        val coreR = maxR * 0.42f + extra

        corePaint.color = primaryColor
        corePaint.alpha = 45
        canvas.drawCircle(cx, cy, coreR + 10f, corePaint)

        corePaint.color = primaryColor
        corePaint.alpha = 190
        canvas.drawCircle(cx, cy, coreR, corePaint)

        corePaint.color = Color.parseColor("#030712")
        corePaint.alpha = 210
        canvas.drawCircle(cx, cy, coreR * 0.72f, corePaint)

        // Texto central
        textPaint.color = primaryColor
        textPaint.textSize = maxR * 0.16f
        val label = when (state) {
            State.IDLE -> "JARVIS"
            State.WAKE_LISTENING -> "ACTIVO"
            State.LISTENING -> "OYENDO"
            State.THINKING -> "IA..."
            State.SPEAKING -> "SBN"
        }
        canvas.drawText(label, cx, cy + textPaint.textSize * 0.35f, textPaint)
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
