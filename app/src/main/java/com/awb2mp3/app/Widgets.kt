package com.awb2mp3.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

private val W_BLUE = Color.parseColor("#2F6BFF")
private val W_CYAN = Color.parseColor("#22E5F0")
private val W_GREEN = Color.parseColor("#3DEB9A")

/**
 * Анимированный «эквалайзер» из четырёх столбиков: показывает, что идёт обработка звука.
 * Анимация работает только пока [start] вызван, и сама останавливается, когда вид скрыт.
 */
class EqualizerView(context: Context) : View(context) {

    private val bars = 4
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var wanted = false

    // у каждого столбика своя скорость и сдвиг — движение выглядит живым, но цикл бесшовный
    private val speed = intArrayOf(1, 2, 1, 3)
    private val offset = floatArrayOf(0f, 0.25f, 0.5f, 0.75f)

    fun start() {
        wanted = true
        update()
    }

    fun stop() {
        wanted = false
        update()
        invalidate()
    }

    private fun update() {
        if (wanted && isShown) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1400
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        phase = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        } else {
            animator?.cancel()
            animator = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        update()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        update()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        update()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        paint.shader = LinearGradient(0f, h.toFloat(), 0f, 0f, W_CYAN, W_GREEN, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = w * 0.14f
        val bw = (w - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val level = if (animator == null) {
                0.35f
            } else {
                0.30f + 0.70f * (0.5f + 0.5f * sin(2.0 * PI * (speed[i] * phase + offset[i])).toFloat())
            }
            val bh = h * level
            val left = i * (bw + gap)
            rect.set(left, (h - bh) / 2f, left + bw, (h + bh) / 2f)
            canvas.drawRoundRect(rect, bw / 2f, bw / 2f, paint)
        }
    }
}

/**
 * Полоса прогресса в цветах иконки: градиентная заливка, плавное движение к новому значению
 * и мягкий блик, пробегающий по заполненной части, пока включён [shimmer].
 */
class SmoothBar(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#26FFFFFF") }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()

    private var target = 0
    private var shown = 0f
    private var move: ValueAnimator? = null
    private var sweep: ValueAnimator? = null
    private var sweepPhase = 0f
    private var band = 0f

    /** Блик включён (нужен только пока идёт конвертация) */
    var shimmer: Boolean = false
        set(value) {
            field = value
            updateSweep()
        }

    fun setProgress(value: Int, animate: Boolean) {
        val v = value.coerceIn(0, 100)
        if (v == target) return
        target = v
        move?.cancel()
        move = null
        if (!animate || !isShown) {
            shown = v.toFloat()
            invalidate()
            return
        }
        move = ValueAnimator.ofFloat(shown, v.toFloat()).apply {
            duration = 500
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun updateSweep() {
        if (shimmer && isShown && width > 0) {
            if (sweep == null) {
                sweep = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1700
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        sweepPhase = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        } else {
            sweep?.cancel()
            sweep = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSweep()
    }

    override fun onDetachedFromWindow() {
        sweep?.cancel()
        sweep = null
        move?.cancel()
        move = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateSweep()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateSweep()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // градиент растянут на всю ширину: цвет «раскрывается» по мере роста полосы
        fillPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f, intArrayOf(W_BLUE, W_CYAN, W_GREEN), null, Shader.TileMode.CLAMP
        )
        band = max(w * 0.3f, h * 4f)
        shimmerPaint.shader = LinearGradient(
            0f, 0f, band, 0f,
            intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP
        )
        updateSweep()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, trackPaint)

        val fillWidth = w * shown / 100f
        if (fillWidth < 0.5f) return
        val fw = max(fillWidth, h) // совсем малый прогресс рисуем капсулой, а не точкой
        rect.set(0f, 0f, fw, h)
        canvas.drawRoundRect(rect, r, r, fillPaint)

        if (sweep != null) {
            val save = canvas.save()
            clip.reset()
            clip.addRoundRect(rect, r, r, Path.Direction.CW)
            canvas.clipPath(clip)
            canvas.translate(-band + (fw + band) * sweepPhase, 0f)
            canvas.drawRect(0f, 0f, band, h, shimmerPaint)
            canvas.restoreToCount(save)
        }
    }
}
