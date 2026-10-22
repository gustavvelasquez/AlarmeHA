package org.fossify.clock.ha

import android.content.Context
import android.os.Bundle
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.cancelAlarmClock
import org.fossify.clock.extensions.createNewAlarm
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.updateWidgets
import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmEvent
import org.greenrobot.eventbus.EventBus
import org.json.JSONObject
import org.fossify.commons.helpers.SILENT
import java.net.URLDecoder

/** Executa as operações pedidas pelo HA: criar, editar, ligar, desligar, apagar e listar. */
class HaCommandProcessor(private val context: Context) {
    private val config = HaConfig(context)
    private val refs = HaRefStore.get(context)
    private val db = context.dbHelper

    private class CommandError(val reason: String) : Exception(reason)

    fun process(extras: Bundle?) {
        if (extras == null) return
        if (!config.isPaired || !config.isValidToken(extras.getAsString("token"))) {
            // Token ausente ou errado: ignora em silêncio, sem responder ao HA.
            config.rejectedCommands = config.rejectedCommands + 1
            return
        }

        val op = extras.getAsString("op")?.trim()?.lowercase()
        val pedido = extras.getAsString("pedido")?.take(MAX_TEXT)
        val ref = extras.getAsString("ref")?.trim()
        HaDiagLog.log(context, "recebido", ref, extra = mapOf("op" to op, "pedido" to pedido,
            "hora" to extras.getAsString("hora"), "minuto" to extras.getAsString("minuto"), "dias" to extras.getAsString("dias"),
            "ativo" to extras.getAsString("ativo")))
        try {
            when (op) {
                "criar" -> create(extras, requireRef(ref), pedido)
                "editar" -> edit(extras, requireRef(ref), pedido, fullPlan = false)
                "ligar" -> setEnabled(ref, true, pedido)
                "desligar" -> setEnabled(ref, false, pedido)
                "apagar" -> delete(ref, pedido)
                "listar" -> HaEvents.sendList(context, pedido)
                "toques" -> HaEvents.sendSounds(context, pedido)
                else -> throw CommandError("op_invalida")
            }
        } catch (e: CommandError) {
            sendError(pedido, op, ref, e.reason)
        } catch (e: Exception) {
            sendError(pedido, op, ref, "erro_interno", e)
        }
    }

    private fun requireRef(ref: String?): String {
        if (ref.isNullOrEmpty()) throw CommandError("ref_ausente")
        if (ref == ALL || !REF_REGEX.matches(ref)) throw CommandError("ref_invalida")
        return ref
    }

    /** Alarmes criados pelo HA para um ref (ou todos, com "*"). Alarmes manuais nunca entram. */
    private fun targets(ref: String?): List<Pair<String, Alarm>> {
        if (ref == ALL) {
            return refs.all().mapNotNull { (r, id) -> db.getAlarmWithId(id)?.let { r to it } }
        }
        val validRef = requireRef(ref)
        return listOf(validRef to findAlarm(validRef))
    }

    private fun findAlarm(ref: String): Alarm {
        val id = refs.getAlarmId(ref) ?: throw CommandError("ref_inexistente")
        val alarm = db.getAlarmWithId(id)
        if (alarm == null) {
            // Apagado à mão no celular: limpa a ligação.
            refs.remove(ref)
            throw CommandError("ref_inexistente")
        }
        return alarm
    }

    private fun create(extras: Bundle, ref: String, pedido: String?) {
        val existingId = refs.getAlarmId(ref)
        if (existingId != null && db.getAlarmWithId(existingId) != null) {
            edit(extras, ref, pedido, fullPlan = true)
            return
        }

        val hour = extras.getAsInt("hora") ?: throw CommandError("hora_ausente")
        val minute = extras.getAsInt("minuto") ?: throw CommandError("minuto_ausente")
        val days = extras.getAsInt("dias") ?: throw CommandError("dias_ausente")
        val alarm = context.createNewAlarm(timeOf(hour, minute), validDays(days)).apply {
            isEnabled = extras.getAsBoolean("ativo") ?: true
            vibrate = extras.getAsBoolean("vibrar") ?: false
            oneShot = extras.getAsBoolean("unico") ?: false
            label = label(extras) ?: ""
            applySound(extras, this)
        }
        alarm.id = db.insertAlarm(alarm)
        if (alarm.id <= 0) throw CommandError("erro_ao_salvar")
        refs.put(ref, alarm.id)
        applyGroup(extras, ref, fullPlan = true)
        applySchedule(alarm)
        HaEvents.sendAlarmChange(context, "criado", ref, alarm, pedido)
    }

    private fun edit(extras: Bundle, ref: String, pedido: String?, fullPlan: Boolean) {
        val alarm = findAlarm(ref)
        context.cancelAlarmClock(alarm)
        val oldTime = alarm.timeInMinutes

        val hour = extras.getAsInt("hora") ?: (alarm.timeInMinutes / 60)
        val minute = extras.getAsInt("minuto") ?: (alarm.timeInMinutes % 60)
        alarm.timeInMinutes = timeOf(hour, minute)
        extras.getAsInt("dias")?.let { alarm.days = validDays(it) }
        extras.getAsBoolean("ativo")?.let { alarm.isEnabled = it }
        extras.getAsBoolean("vibrar")?.let { alarm.vibrate = it }
        extras.getAsBoolean("unico")?.let { alarm.oneShot = it }
        label(extras)?.let { alarm.label = it }
        applySound(extras, alarm)

        if (!db.updateAlarm(alarm)) throw CommandError("erro_ao_salvar")
        // Outro horário = outro compromisso: o pulo antigo não vale mais.
        if (!HaSkipLogic.keepSkipAfterEdit(oldTime, alarm.timeInMinutes)) refs.setSkipped(alarm.id, null)
        applyGroup(extras, ref, fullPlan)
        applySchedule(alarm)
        HaEvents.sendAlarmChange(context, "alterado", ref, alarm, pedido)
    }

    /** "grupo"/"grupo_nome" (integração 0.6+). No "criar" o plano vem completo: sem grupo = sozinho. */
    private fun applyGroup(extras: Bundle, ref: String, fullPlan: Boolean) {
        val raw = extras.getAsString("grupo")
        if (raw == null && !fullPlan) return
        val group = decoded(raw)?.trim()?.lowercase()?.takeIf { REF_REGEX.matches(it) }
        val name = if (group == null) null else decoded(extras.getAsString("grupo_nome"))?.trim()?.take(MAX_TEXT)
        refs.setGroup(ref, group, name)
    }

    private fun setEnabled(ref: String?, enabled: Boolean, pedido: String?) {
        targets(ref).forEach { (r, alarm) ->
            context.cancelAlarmClock(alarm)
            alarm.isEnabled = enabled
            db.updateAlarmEnabledState(alarm.id, enabled)
            applySchedule(alarm)
            HaEvents.sendAlarmChange(context, "alterado", r, alarm, pedido)
        }
    }

    private fun delete(ref: String?, pedido: String?) {
        targets(ref).forEach { (r, alarm) ->
            db.deleteAlarms(arrayListOf(alarm))
            refs.remove(r)
            HaDiagLog.log(context, "apagado", r, alarm)
            HaEvents.sendAlarmChange(context, "apagado", r, alarm, pedido)
        }
        refresh()
    }

    private fun applySchedule(alarm: Alarm) {
        val ref = refs.refForAlarm(alarm.id)
        if (alarm.isEnabled) {
            context.alarmController.scheduleNextOccurrence(alarm)
            HaDiagLog.log(context, "agendado", ref, alarm)
        } else {
            HaDiagLog.log(context, "desagendado", ref, alarm)
            context.cancelAlarmClock(alarm)
            refresh()
        }
    }

    private fun refresh() {
        context.updateWidgets()
        EventBus.getDefault().post(AlarmEvent.Refresh)
    }

    private fun sendError(pedido: String?, op: String?, ref: String?, reason: String, cause: Exception? = null) {
        HaDiagLog.log(context, "erro", ref, extra = mapOf("op" to op, "pedido" to pedido, "motivo" to reason,
            "mensagem" to cause?.let { "${it.javaClass.simpleName}: ${it.message ?: ""}" }))
        HaWebhook.send(context, "erro", JSONObject().apply {
            put("pedido", pedido ?: JSONObject.NULL)
            put("op", op ?: JSONObject.NULL)
            put("ref", ref ?: JSONObject.NULL)
            put("motivo", reason)
        })
    }

    private fun timeOf(hour: Int, minute: Int): Int {
        if (hour !in 0..23 || minute !in 0..59) throw CommandError("horario_invalido")
        return hour * 60 + minute
    }

    private fun validDays(days: Int): Int {
        if (days != -1 && days != -2 && days !in 1..127) throw CommandError("dias_invalido")
        return days
    }

    /** Toque escolhido no HA (uma das opções que o próprio celular mandou em "toques"). */
    private fun applySound(extras: Bundle, alarm: Alarm) {
        val uri = decoded(extras.getAsString("som_uri"))?.trim() ?: return
        if (uri != SILENT && SOUND_SCHEMES.none { uri.startsWith(it) }) throw CommandError("som_invalido")
        alarm.soundUri = uri.take(MAX_URI)
        alarm.soundTitle = decoded(extras.getAsString("som_titulo"))?.trim()?.take(MAX_TEXT) ?: ""
    }

    private fun decoded(raw: String?): String? {
        if (raw == null) return null
        return if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(raw)) {
            runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        } else {
            raw
        }
    }

    private fun label(extras: Bundle): String? {
        val raw = extras.getAsString("rotulo") ?: return null
        // O Companion já decodifica String.urlencoded; decodifica de novo só se ainda vier com %XX.
        val text = if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(raw)) {
            runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        } else {
            raw
        }
        return text.trim().take(MAX_TEXT)
    }

    companion object {
        private const val ALL = "*"
        private const val MAX_TEXT = 64
        private const val MAX_URI = 512
        private val SOUND_SCHEMES = listOf("content://", "file://", "android.resource://")
        private val REF_REGEX = Regex("^[a-z0-9_-]{1,64}$")

        /** O Companion pode mandar números e booleanos como int/boolean ou como texto. */
        @Suppress("DEPRECATION")
        private fun Bundle.getAsString(key: String): String? = get(key)?.toString()

        private fun Bundle.getAsInt(key: String): Int? {
            val s = getAsString(key)?.trim() ?: return null
            return s.toIntOrNull() ?: throw CommandError("${key}_invalido")
        }

        private fun Bundle.getAsBoolean(key: String): Boolean? =
            when (getAsString(key)?.trim()?.lowercase()) {
                null -> null
                "true", "1", "sim" -> true
                "false", "0", "nao", "não" -> false
                else -> throw CommandError("${key}_invalido")
            }
    }
}
