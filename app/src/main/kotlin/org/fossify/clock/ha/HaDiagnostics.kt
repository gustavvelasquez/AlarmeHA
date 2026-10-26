package org.fossify.clock.ha

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Diagnóstico do envio ao HA e da bateria (tela do HA e aviso na lista de alarmes). */
object HaDiagnostics {
    private fun fmt(ms: Long) = SimpleDateFormat("dd/MM HH:mm:ss", Locale("pt", "BR")).format(Date(ms))

    fun sendStatus(context: Context): String {
        val c = HaConfig(context)
        return buildString {
            append("Envio ao HA:")
            append("\n• Último envio ok: ").append(if (c.lastOkAt > 0) "${fmt(c.lastOkAt)} (${c.lastOkUrl})" else "nenhum")
            if (c.lastErrorAt > 0 && c.lastErrorAt > c.lastOkAt) {
                append("\n• Último erro: ").append(fmt(c.lastErrorAt)).append("\n").append(c.lastError)
            }
            append("\n• Na fila: ").append(HaWebhook.pending(context))
            if (c.droppedEvents > 0) append("\n• Descartados (mais de 7 dias ou pareamento antigo): ").append(c.droppedEvents)
            batteryProblem(context)?.let { append("\n• ").append(it) }
        }
    }

    /** Motivo de o Android estar segurando o app, ou null. */
    fun batteryProblem(context: Context): String? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) return "Bateria: o app não está como \"Sem restrição\""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val bucket = (context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).appStandbyBucket
            if (bucket >= UsageStatsManager.STANDBY_BUCKET_RARE) return "Android colocou o app em espera (\"raro/restrito\")"
        }
        return null
    }

    /** Texto do aviso fixo na lista (null = sem aviso). Só para celular pareado. */
    fun banner(context: Context, now: Long = System.currentTimeMillis()): String? {
        val c = HaConfig(context)
        if (!c.hasWebhook) return null
        batteryProblem(context)?.let { return "$it. Os avisos ao HA podem atrasar. Toque para corrigir." }
        val failing = c.lastErrorAt > c.lastOkAt && HaWebhook.pending(context) > 0 && now - c.lastErrorAt < 60 * 60 * 1000L &&
            (c.lastOkAt == 0L || now - c.lastOkAt > 30 * 60 * 1000L)
        if (failing) return "Não consigo falar com o Home Assistant desde ${fmt(c.lastOkAt.takeIf { it > 0 } ?: c.lastErrorAt)}. Toque para ver o erro."
        return null
    }
}
