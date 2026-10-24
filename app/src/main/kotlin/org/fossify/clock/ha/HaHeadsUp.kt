package org.fossify.clock.ha

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.os.PowerManager
import android.widget.RemoteViews
import org.fossify.clock.R

/**
 * 0.13.0: alarme tocando com a tela ligada e desbloqueada (celular em uso) vira um aviso grande no
 * topo, como o do Relógio da Samsung: nome, hora grande, Adiar e Desligar. Fica até uma ação (é a
 * notificação do alarme em primeiro plano, com tela cheia pedida: o sistema a mantém fixa).
 * Tela desligada ou bloqueada: nada muda, continua a tela cheia de sempre.
 */
object HaHeadsUp {
    /** Puro (testado): só com a tela ligada E desbloqueada. */
    fun shouldUse(interactive: Boolean, locked: Boolean): Boolean = interactive && !locked

    fun inUse(context: Context): Boolean = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shouldUse(power.isInteractive, keyguard.isKeyguardLocked)
    }.getOrDefault(false)

    fun views(context: Context, label: String, time: String, snooze: PendingIntent, dismiss: PendingIntent): RemoteViews {
        val text = HaStyle.text(context)
        val primary = HaStyle.primary(context)
        return RemoteViews(context.packageName, R.layout.ha_alarm_headsup).apply {
            setInt(R.id.ha_hu_root, "setBackgroundColor", HaStyle.card(context))
            setTextViewText(R.id.ha_hu_label, label)
            setTextColor(R.id.ha_hu_label, HaStyle.text2(context))
            setTextViewText(R.id.ha_hu_time, time)
            setTextColor(R.id.ha_hu_time, text)
            setInt(R.id.ha_hu_icon, "setColorFilter", primary)
            setTextColor(R.id.ha_hu_snooze, text)
            setTextColor(R.id.ha_hu_dismiss, HaStyle.onPrimary(context))
            tint(this, R.id.ha_hu_snooze, HaStyle.button(context))
            tint(this, R.id.ha_hu_dismiss, primary)
            setOnClickPendingIntent(R.id.ha_hu_snooze, snooze)
            setOnClickPendingIntent(R.id.ha_hu_dismiss, dismiss)
        }
    }

    private fun tint(views: RemoteViews, id: Int, color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(id, "setBackgroundTintList", ColorStateList.valueOf(color))
        } else {
            views.setInt(id, "setBackgroundColor", color)
        }
    }
}
