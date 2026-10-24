package org.fossify.clock.ha

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import org.fossify.clock.R

/**
 * 0.14.0: cartão grande desenhado pelo próprio app por cima da tela (janela de sobreposição), para
 * quando o alarme toca com a tela ligada e desbloqueada. Em alguns aparelhos (ex.: Samsung com
 * "Pop-up breve") o aviso do sistema vira uma pílula pequena e o app não consegue mudar isso.
 * Sem a permissão "Sobrepor a outros apps", fica o aviso do sistema da 0.13.0.
 * Um cartão por vez; sai ao desligar, adiar, acabar o tempo ou o serviço parar.
 */
object HaOverlay {
    private const val PREFS = "alarmeha_overlay"
    private const val KEY_ASKED = "asked"

    private var view: View? = null
    private var shownFor: Int? = null

    /** Puro (testado): usa o cartão próprio só com tela em uso e permissão. */
    fun shouldShow(inUse: Boolean, canDraw: Boolean): Boolean = inUse && canDraw

    fun canDraw(context: Context): Boolean = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    val isShowing: Boolean get() = view != null

    /** Mostra o cartão do alarme. Devolve true se está na tela (o aviso do sistema não deve aparecer). */
    fun show(context: Context, alarmId: Int, label: String, time: String, snooze: PendingIntent, dismiss: PendingIntent): Boolean {
        if (!shouldShow(HaHeadsUp.inUse(context), canDraw(context))) return false
        hide(context)
        return runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val card = LayoutInflater.from(context).inflate(R.layout.ha_alarm_headsup, null)
            paint(context, card, label, time)
            card.findViewById<View>(R.id.ha_hu_snooze).setOnClickListener { hide(context); runCatching { snooze.send() } }
            card.findViewById<View>(R.id.ha_hu_dismiss).setOnClickListener { hide(context); runCatching { dismiss.send() } }
            val margin = HaStyle.dp(context, 8f).toInt()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP
                horizontalMargin = 0f
                y = statusBarHeight(context) + margin
            }
            card.setPadding(card.paddingLeft, card.paddingTop, card.paddingRight, card.paddingBottom)
            val holder = android.widget.FrameLayout(context).apply {
                setPadding(margin, 0, margin, 0)
                addView(card)
            }
            wm.addView(holder, params)
            view = holder
            shownFor = alarmId
            true
        }.getOrElse {
            view = null
            shownFor = null
            false
        }
    }

    /** Tira o cartão (se houver). Seguro chamar várias vezes. */
    fun hide(context: Context) {
        val v = view ?: return
        view = null
        shownFor = null
        runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeViewImmediate(v) }
    }

    private fun paint(context: Context, card: View, label: String, time: String) {
        card.background = HaStyle.rounded(context, HaStyle.card(context), 24f)
        card.elevation = HaStyle.dp(context, 8f)
        card.findViewById<TextView>(R.id.ha_hu_label).apply { text = label; setTextColor(HaStyle.text2(context)) }
        card.findViewById<TextView>(R.id.ha_hu_time).apply { text = time; setTextColor(HaStyle.text(context)); textSize = 44f }
        card.findViewById<ImageView>(R.id.ha_hu_icon).setColorFilter(HaStyle.primary(context))
        card.findViewById<TextView>(R.id.ha_hu_snooze).apply {
            setTextColor(HaStyle.text(context))
            backgroundTintList = ColorStateList.valueOf(HaStyle.button(context))
            minimumHeight = HaStyle.dp(context, 48f).toInt()
        }
        card.findViewById<TextView>(R.id.ha_hu_dismiss).apply {
            setTextColor(HaStyle.onPrimary(context))
            backgroundTintList = ColorStateList.valueOf(HaStyle.primary(context))
            minimumHeight = HaStyle.dp(context, 48f).toInt()
        }
    }

    private fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    // ---- permissão ----

    fun openPermission(activity: Activity) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
        runCatching { activity.startActivity(intent) }.onFailure {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
        }
    }

    /** Uma vez só: explica e oferece abrir a permissão. "Agora não" não bloqueia nada. */
    fun askOnce(activity: Activity) {
        if (canDraw(activity)) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        AlertDialog.Builder(activity)
            .setTitle(R.string.ha_overlay_title)
            .setMessage(R.string.ha_overlay_why)
            .setPositiveButton(R.string.ha_overlay_allow) { _, _ -> openPermission(activity) }
            .setNegativeButton(R.string.ha_overlay_later, null)
            .show()
    }
}
