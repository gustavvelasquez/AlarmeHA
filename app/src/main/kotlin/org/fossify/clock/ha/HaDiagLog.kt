package org.fossify.clock.ha

import android.content.Context
import org.fossify.clock.models.Alarm
import org.json.JSONObject

/**
 * Modo diagnóstico: quando a chave está ligada em Configurações, manda ao HA (pelo mesmo webhook e
 * pela mesma fila de [HaWebhook]) um registro técnico de cada passo: comando recebido, alarme
 * agendado/apagado, tocou, soneca, pulou, dispensou e erros. Desligado por padrão e sem desligar
 * sozinho. Sem dados pessoais: só ref (vaga), id, horário, dias e textos técnicos; nunca o rótulo.
 * A versão do app e o horário já vão em todo envio ("versao" e "quando").
 */
object HaDiagLog {
    const val EVENT = "diagnostico"
    private const val MAX_DETAIL = 300

    /** Monta os campos do registro (puro, testável). */
    fun fields(tipo: String, ref: String?, alarm: Alarm?, extra: Map<String, Any?> = emptyMap()): JSONObject =
        JSONObject().apply {
            put("tipo", tipo)
            put("ref", ref ?: JSONObject.NULL)
            if (alarm != null) {
                put("id", alarm.id)
                put("hora", alarm.timeInMinutes / 60)
                put("minuto", alarm.timeInMinutes % 60)
                put("dias", alarm.days)
                put("ativo", alarm.isEnabled)
            }
            extra.forEach { (k, v) -> put(k, if (v is String) v.take(MAX_DETAIL) else v ?: JSONObject.NULL) }
        }

    fun log(context: Context, tipo: String, ref: String?, alarm: Alarm? = null, extra: Map<String, Any?> = emptyMap()) {
        runCatching {
            val config = HaConfig(context)
            if (!config.diagnosticMode || !config.hasWebhook) return
            HaWebhook.send(context, EVENT, fields(tipo, ref, alarm, extra))
        }
    }
}
