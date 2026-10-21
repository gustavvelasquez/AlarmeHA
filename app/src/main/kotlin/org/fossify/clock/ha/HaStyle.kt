package org.fossify.clock.ha

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.SwitchCompat

/** Paleta e peças do visual 0.9.0, copiadas do Relógio da Samsung (One UI, tema escuro). */
object HaStyle {
    const val BG = 0xFF010101.toInt()
    const val CARD = 0xFF17171A.toInt()
    const val ACCENT = 0xFF8C88F3.toInt()
    const val SWITCH_ON = 0xFF7C78EA.toInt()
    const val SWITCH_OFF = 0xFF616166.toInt()
    const val TEXT = 0xFFFAFAFA.toInt()
    const val TEXT_2 = 0xFFBDBDC2.toInt()
    const val DIM = 0xFF6F6F73.toInt()
    const val DIVIDER = 0xFF2E2E31.toInt()
    const val BUTTON = 0xFF3A3A3C.toInt()
    const val PILL = 0xFF2A2B30.toInt()
    const val PILL_ON = 0xFF45464C.toInt()

    fun dp(context: Context, v: Float) = v * context.resources.displayMetrics.density

    fun rounded(context: Context, color: Int, radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = dp(context, radiusDp)
        setColor(color)
    }

    /** Cartão único dos soltos: topo arredondado no primeiro, base no último. */
    fun segment(context: Context, color: Int, first: Boolean, last: Boolean) = GradientDrawable().apply {
        val r = dp(context, 26f)
        val t = if (first) r else 0f
        val b = if (last) r else 0f
        cornerRadii = floatArrayOf(t, t, t, t, b, b, b, b)
        setColor(color)
    }

    fun boldTime(): Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    fun medium(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Chave roxa da Samsung (polegar branco; desligada cinza). */
    fun paintSwitch(sw: SwitchCompat) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFE6E6E6.toInt()))
        sw.trackTintList = ColorStateList(states, intArrayOf(SWITCH_ON, SWITCH_OFF))
        sw.trackTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        (sw as? com.google.android.material.materialswitch.MaterialSwitch)?.trackDecorationTintList = ColorStateList.valueOf(0)
    }
}

/** D S T Q Q S S com os dias ativos em roxo, negrito e um ponto em cima (alarme que repete). */
class HaDaysView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var days = 0
    var faded = false
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = HaStyle.dp(context, 14f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setDays(value: Int) { days = value; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(HaStyle.dp(context, 120f).toInt(), widthMeasureSpec), HaStyle.dp(context, 24f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val step = HaStyle.dp(context, 15f)
        val base = height - HaStyle.dp(context, 4f)
        HaUi.weekLetters(days).forEachIndexed { i, (letter, on) ->
            paint.typeface = if (on) HaStyle.boldTime() else Typeface.DEFAULT
            paint.color = when {
                faded -> HaStyle.DIM
                on -> HaStyle.ACCENT
                else -> HaStyle.DIM
            }
            val x = i * step + HaStyle.dp(context, 2f)
            canvas.drawText(letter, x, base, paint)
            if (on) {
                dot.color = paint.color
                canvas.drawCircle(x + paint.measureText(letter) / 2, HaStyle.dp(context, 3f), HaStyle.dp(context, 1.6f), dot)
            }
        }
    }
}
