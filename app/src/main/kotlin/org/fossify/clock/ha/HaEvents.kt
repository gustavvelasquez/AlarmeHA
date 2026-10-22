package org.fossify.clock.ha

import android.content.Context
import android.media.RingtoneManager
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.helpers.getTimeOfNextAlarm
import org.fossify.clock.models.Alarm
import org.fossify.commons.helpers.ensureBackgroundThread
import org.json.JSONArray
import org.json.JSONObject

/** Monta e envia os eventos do AlarmeHA para o webhook do HA. */
object HaEvents {
    fun alarmJson(alarm: Alarm, ref: String?, info: HaRefStore.Info? = null): JSONObject = JSONObject().apply {
        put("ref", ref ?: JSONObject.NULL)
        put("id", alarm.id)
        put("hora", alarm.timeInMinutes / 60)
        put("minuto", alarm.timeInMinutes % 60)
        put("dias", alarm.days)
        put("rotulo", alarm.label)
        put("ativo", alarm.isEnabled)
        put("vibrar", alarm.vibrate)
        put("som_titulo", alarm.soundTitle)
        put("unico", alarm.oneShot)
        put("criado_pelo_ha", ref != null)
        val now = System.currentTimeMillis()
        val skipped = info?.skippedAt?.takeIf { HaSkipLogic.isSkipActive(it, now) }
        val next = when {
            !alarm.isEnabled -> null
            ref != null -> HaSkipLogic.effectiveNext(alarm.timeInMinutes, alarm.days, skipped, now)
            else -> getTimeOfNextAlarm(alarm)?.timeInMillis
        }
        put("proximo_disparo", next?.let { HaWebhook.isoDate(java.util.Date(it)) } ?: JSONObject.NULL)
        if (ref != null) {
            // 0.4.0: pulo (skip once) e grupo do compromisso. Integração antiga ignora.
            put("pulado", skipped?.let { HaWebhook.isoDate(java.util.Date(it)) } ?: JSONObject.NULL)
            put("grupo", info?.group ?: JSONObject.NULL)
            put("grupo_nome", info?.groupName ?: JSONObject.NULL)
        }
    }

    fun sendAlarmChange(context: Context, event: String, ref: String, alarm: Alarm, pedido: String?) {
        HaWebhook.send(context, event, JSONObject().apply {
            put("pedido", pedido ?: JSONObject.NULL)
            put("ref", ref)
            put("id", alarm.id)
            put("origem_mudanca", "ha")
            if (event != "apagado") put("alarme", alarmJson(alarm, ref, HaSkip.info(context, alarm.id)))
        })
    }

    fun sendList(context: Context, pedido: String?) {
        val infos = HaRefStore.get(context).allInfo().associateBy { it.alarmId }
        val alarms = JSONArray()
        context.dbHelper.getAlarms().forEach { alarms.put(alarmJson(it, infos[it.id]?.ref, infos[it.id])) }
        HaWebhook.send(context, "lista", JSONObject().apply {
            put("pedido", pedido ?: JSONObject.NULL)
            put("alarmes", alarms)
        })
    }

    /** Toques de alarme do celular (os do sistema e os que já estão em uso em algum alarme). */
    fun sendSounds(context: Context, pedido: String?) {
        val sounds = LinkedHashMap<String, String>()
        context.dbHelper.getAlarms().forEach { if (it.soundUri.isNotEmpty()) sounds.putIfAbsent(it.soundUri, it.soundTitle) }
        runCatching {
            val manager = RingtoneManager(context).apply { setType(RingtoneManager.TYPE_ALARM) }
            val cursor = manager.cursor
            while (cursor.moveToNext() && sounds.size < MAX_SOUNDS) {
                val uri = manager.getRingtoneUri(cursor.position)?.toString() ?: continue
                sounds.putIfAbsent(uri, cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: uri)
            }
        }
        val list = JSONArray()
        sounds.forEach { (uri, title) -> list.put(JSONObject().put("uri", uri).put("titulo", title)) }
        HaWebhook.send(context, "toques", JSONObject().apply {
            put("pedido", pedido ?: JSONObject.NULL)
            put("toques", list)
        })
    }

    private const val MAX_SOUNDS = 150

    /** Chamado pelo AlarmController (tocou, soneca, desligado). Não faz nada se não houver pareamento. */
    fun onRingEvent(context: Context, event: String, alarmId: Int, extra: JSONObject.() -> Unit = {}) {
        if (!HaConfig(context).hasWebhook) return
        ensureBackgroundThread {
            val alarm = context.dbHelper.getAlarmWithId(alarmId)
            val ref = HaRefStore.get(context).refForAlarm(alarmId)
            val tipo = when (event) { "desligado" -> "dispensou"; else -> event }
            HaDiagLog.log(context, tipo, ref, alarm, JSONObject().apply(extra).let { j -> j.keys().asSequence().associateWith { j.get(it) } })
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
