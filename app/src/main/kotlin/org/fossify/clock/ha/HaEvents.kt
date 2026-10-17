package org.fossify.clock.ha

import android.content.Context
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.helpers.getTimeOfNextAlarm
import org.fossify.clock.models.Alarm
import org.fossify.commons.helpers.ensureBackgroundThread
import org.json.JSONArray
import org.json.JSONObject

/** Monta e envia os eventos do AlarmeHA para o webhook do HA. */
object HaEvents {
    fun alarmJson(alarm: Alarm, ref: String?): JSONObject = JSONObject().apply {
        put("ref", ref ?: JSONObject.NULL)
        put("id", alarm.id)
        put("hora", alarm.timeInMinutes / 60)
        put("minuto", alarm.timeInMinutes % 60)
        put("dias", alarm.days)
        put("rotulo", alarm.label)
        put("ativo", alarm.isEnabled)
        put("vibrar", alarm.vibrate)
        put("unico", alarm.oneShot)
        put("criado_pelo_ha", ref != null)
        val next = if (alarm.isEnabled) getTimeOfNextAlarm(alarm) else null
        put("proximo_disparo", next?.let { HaWebhook.isoDate(it.time) } ?: JSONObject.NULL)
    }

    fun sendAlarmChange(context: Context, event: String, ref: String, alarm: Alarm, pedido: String?) {
        HaWebhook.send(context, event, JSONObject().apply {
            put("pedido", pedido ?: JSONObject.NULL)
            put("ref", ref)
            put("id", alarm.id)
            put("origem_mudanca", "ha")
            if (event != "apagado") put("alarme", alarmJson(alarm, ref))
        })
    }

    fun sendList(context: Context, pedido: String?) {
        val byId = HaRefStore.get(context).all().entries.associate { (ref, id) -> id to ref }
        val alarms = JSONArray()
        context.dbHelper.getAlarms().forEach { alarms.put(alarmJson(it, byId[it.id])) }
        HaWebhook.send(context, "lista", JSONObject().apply {
            put("pedido", pedido ?: JSONObject.NULL)
            put("alarmes", alarms)
        })
    }

    /** Chamado pelo AlarmController (tocou, soneca, desligado). Não faz nada se não houver pareamento. */
    fun onRingEvent(context: Context, event: String, alarmId: Int, extra: JSONObject.() -> Unit = {}) {
        if (!HaConfig(context).hasWebhook) return
        ensureBackgroundThread {
            val alarm = context.dbHelper.getAlarmWithId(alarmId)
            val ref = HaRefStore.get(context).refForAlarm(alarmId)
            HaWebhook.send(context, event, JSONObject().apply {
                put("ref", ref ?: JSONObject.NULL)
                put("id", alarmId)
                put("rotulo", alarm?.label ?: JSONObject.NULL)
                if (alarm != null) {
                    put("hora", alarm.timeInMinutes / 60)
                    put("minuto", alarm.timeInMinutes % 60)
                }
                extra()
            })
        }
    }
}
