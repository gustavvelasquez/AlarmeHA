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
import androidx.core.graphics.ColorUtils
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.views.MyMaterialSwitch

/**
 * Formas do visual dos cartões (0.9.0) com as cores do TEMA do Fossify (0.12.0): texto, cor principal
 * e fundo vêm de "Personalizar aparência" (claro/escuro incluídos). Esmaecido = alpha sobre o texto.
 */
object HaStyle {
    const val FADED_ALPHA = 0.45f

    fun text(c: Context) = c.getProperTextColor()
    fun primary(c: Context) = c.getProperPrimaryColor()
    fun bg(c: Context) = c.getProperBackgroundColor()
    /** Texto secundário e desligado/pulado. */
    fun text2(c: Context) = text(c).adjustAlpha(0.7f)
    fun dim(c: Context) = text(c).adjustAlpha(FADED_ALPHA)
    /** Superfície do cartão: o fundo do tema levemente puxado para a cor do texto. */
    fun card(c: Context) = ColorUtils.blendARGB(bg(c), text(c), 0.07f)
    fun button(c: Context) = ColorUtils.blendARGB(bg(c), text(c), 0.16f)
    fun chip(c: Context) = ColorUtils.blendARGB(bg(c), text(c), 0.12f)
    fun divider(c: Context) = text(c).adjustAlpha(0.12f)
    fun onPrimary(c: Context) = primary(c).getContrastColor()

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

    /** Chave com as cores do tema, como no Fossify original (setColors). */
    fun paintSwitch(sw: SwitchCompat, c: Context) {
        if (sw is MyMaterialSwitch) {
            sw.setColors(text(c), primary(c), bg(c))
            return
        }
        // Chave criada no código (tela do grupo): mesmas cores que o setColors do Fossify aplicaria.
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(onPrimary(c), text(c).adjustAlpha(0.6f)))
        sw.trackTintList = ColorStateList(states, intArrayOf(primary(c), chip(c)))
        (sw as? com.google.android.material.materialswitch.MaterialSwitch)?.trackDecorationTintList =
            ColorStateList(states, intArrayOf(primary(c), text(c).adjustAlpha(0.3f)))
    }
}

/** D S T Q Q S S com os dias ativos na cor principal do tema, negrito e um ponto em cima. */
class HaDaysView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var days = 0
    private var states: List<Int>? = null
    var faded = false
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = HaStyle.dp(context, 14f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setDays(value: Int) { days = value; states = null; invalidate() }

    /** Linha da tela do grupo: HaUi.DAY_ON cor principal com ponto, DAY_OFF apagado (pulado/desligado), DAY_NONE texto esmaecido. */
    fun setStates(value: List<Int>) { states = value; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(HaStyle.dp(context, 120f).toInt(), widthMeasureSpec), HaStyle.dp(context, 24f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val step = HaStyle.dp(context, 15f)
        val base = height - HaStyle.dp(context, 4f)
        val primary = HaStyle.primary(context)
        HaUi.weekLetters(days).forEachIndexed { i, (letter, marked) ->
            val st = states?.get(i) ?: if (marked) HaUi.DAY_ON else HaUi.DAY_NONE
            val on = st == HaUi.DAY_ON
            paint.typeface = if (st != HaUi.DAY_NONE) HaStyle.boldTime() else Typeface.DEFAULT
            paint.color = when {
                faded -> HaStyle.dim(context)
                on -> primary
                st == HaUi.DAY_OFF -> primary.adjustAlpha(0.4f)
                else -> HaStyle.text2(context)
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
