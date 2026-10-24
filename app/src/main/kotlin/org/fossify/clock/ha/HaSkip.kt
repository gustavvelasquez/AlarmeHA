package org.fossify.clock.ha

import android.content.Context
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.cancelAlarmClock
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.updateWidgets
import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmEvent
import org.fossify.commons.helpers.ensureBackgroundThread
import org.greenrobot.eventbus.EventBus
import org.json.JSONObject
import java.util.Date

/**
 * Pular / religar o próximo toque dos alarmes da agenda (com ref do HA) e o grupo do compromisso.
 * Alarmes manuais nunca passam por aqui. Cada mudança manual vira um aviso "pulado"/"religado"
 * para o HA (fila do HaWebhook: funciona offline e vai quando a rede voltar).
 */
object HaSkip {
    private const val PREFS = "alarmeha_skip"
    private const val KEY_SNOOZED = "snoozed"

    // 0.13.0: origem do pulo/religar. "origem" já é o campo fixo do envelope ("alarmeha"),
    // por isso o nome do campo é "origem_pulo".
    const val KEY_ORIGEM = "origem_pulo"
    const val ORIGEM_SWITCH_LISTA = "switch_lista"
    const val ORIGEM_SWITCH_CARTAO = "switch_cartao"
    const val ORIGEM_DESATIVAR_TUDO = "desativar_tudo"
    const val ORIGEM_NOTIFICACAO = "notificacao"

    fun info(context: Context, alarmId: Int): HaRefStore.Info? =
        runCatching { HaRefStore.get(context).infoForAlarm(alarmId) }.getOrNull()

    /** Horário (epoch ms) do toque pulado, só se o pulo ainda vale. */
    fun activeSkip(context: Context, alarmId: Int, now: Long = System.currentTimeMillis()): Long? =
        info(context, alarmId)?.skippedAt?.takeIf { HaSkipLogic.isSkipActive(it, now) }

    fun isHaAlarm(context: Context, alarmId: Int): Boolean = info(context, alarmId) != null

    /** Próximo toque real (com pulo). null = não toca. */
    fun effectiveNext(context: Context, alarm: Alarm, now: Long = System.currentTimeMillis()): Long? =
        HaSkipLogic.effectiveNext(alarm.timeInMinutes, alarm.days, activeSkip(context, alarm.id, now), now)

    // ---- soneca em andamento (para "desligar o grupo" cancelar) ----

    fun markSnoozed(context: Context, alarmId: Int, snoozed: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_SNOOZED, emptySet())!!.toMutableSet()
        if (snoozed) set.add(alarmId.toString()) else set.remove(alarmId.toString())
        prefs.edit().putStringSet(KEY_SNOOZED, set).apply()
    }

    fun isSnoozed(context: Context, alarmId: Int): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SNOOZED, emptySet())!!.contains(alarmId.toString())

    // ---- ações (chamadas da lista) ----

    /** Pula o próximo toque. Devolve false se não é alarme do HA ou não há toque futuro. Thread de fundo. */
    fun skipNow(context: Context, alarm: Alarm, now: Long = System.currentTimeMillis(), origem: String? = null): Boolean {
        val info = info(context, alarm.id) ?: return false
        if (!alarm.isEnabled) return false
        val target = HaSkipLogic.skipTarget(alarm.timeInMinutes, alarm.days, now) ?: return false
        HaRefStore.get(context).setSkipped(alarm.id, target)
        context.cancelAlarmClock(alarm)
        context.alarmController.scheduleNextOccurrence(alarm) // agenda a ocorrência seguinte (ou nada)
        HaDiagLog.log(context, "pulou", info.ref, alarm, diagExtra(info, origem) + ("ocorrencia" to HaWebhook.isoDate(Date(target))))
        notifyHa(context, "pulado", info.ref, alarm, target, origem)
        return true
    }

    /** Religa: desfaz o pulo (e liga, se estava desligado). Thread de fundo. */
    fun unskipNow(context: Context, alarm: Alarm, origem: String? = null) {
        val info = info(context, alarm.id) ?: return
        HaRefStore.get(context).setSkipped(alarm.id, null)
        if (!alarm.isEnabled) {
            alarm.isEnabled = true
            context.dbHelper.updateAlarmEnabledState(alarm.id, true)
        }
        context.cancelAlarmClock(alarm)
        context.alarmController.scheduleNextOccurrence(alarm)
        HaDiagLog.log(context, "religado", info.ref, alarm, diagExtra(info, origem))
        notifyHa(context, "religado", info.ref, alarm, null, origem)
    }

    /** 0.13.0: de onde veio o pulo/religar e o grupo, para o registro do Modo diagnóstico. */
    fun diagExtra(info: HaRefStore.Info, origem: String?): Map<String, Any?> =
        mapOf(KEY_ORIGEM to origem, "grupo" to info.group)

    fun toggle(context: Context, alarmId: Int, on: Boolean, origem: String? = null) = ensureBackgroundThread {
        val alarm = context.dbHelper.getAlarmWithId(alarmId) ?: return@ensureBackgroundThread
        if (on) unskipNow(context, alarm, origem) else skipNow(context, alarm, origem = origem)
        refresh(context)
    }

    /** Desligar o grupo: pula o próximo toque de cada um e cancela soneca em andamento. */
    fun setGroup(context: Context, group: String, on: Boolean) = ensureBackgroundThread {
        val now = System.currentTimeMillis()
        val db = context.dbHelper
        val members = HaRefStore.get(context).allInfo().filter { it.group == group }
            .mapNotNull { info -> db.getAlarmWithId(info.alarmId)?.let { info to it } }
        if (members.isEmpty()) return@ensureBackgroundThread
        if (on) {
            members.forEach { (info, alarm) -> if (HaSkipLogic.isSkipActive(info.skippedAt, now)) unskipNow(context, alarm, ORIGEM_DESATIVAR_TUDO) }
        } else {
            val list = members.map { (_, a) ->
                HaSkipLogic.Member(
                    a.id,
                    if (a.isEnabled) HaSkipLogic.nextOccurrence(a.timeInMinutes, a.days, now) else null,
                    isSnoozed(context, a.id),
                )
            }
            val targets = HaSkipLogic.groupSkipTargets(list, now)
            members.forEach { (info, alarm) ->
                if (isSnoozed(context, alarm.id)) {
                    markSnoozed(context, alarm.id, false)
                    context.cancelAlarmClock(alarm)
                    context.alarmController.stopAlarm(alarm.id) // cancela a soneca e avisa "desligado"
                    if (alarm.isRecurring()) context.alarmController.scheduleNextOccurrence(alarm)
                } else if (alarm.id in targets && !HaSkipLogic.isSkipActive(info.skippedAt, now)) {
                    skipNow(context, alarm, now, ORIGEM_DESATIVAR_TUDO)
                }
            }
        }
        refresh(context)
    }

    private fun notifyHa(context: Context, event: String, ref: String, alarm: Alarm, occurrence: Long?, origem: String?) {
        HaWebhook.send(context, event, JSONObject().apply {
            put("ref", ref)
            put(KEY_ORIGEM, origem ?: JSONObject.NULL)
            put("id", alarm.id)
            put("origem_mudanca", "app")
            put("ocorrencia", occurrence?.let { HaWebhook.isoDate(Date(it)) } ?: JSONObject.NULL)
            put("alarme", HaEvents.alarmJson(alarm, ref, info(context, alarm.id)))
        })
    }

    private fun refresh(context: Context) {
        context.updateWidgets()
        EventBus.getDefault().post(AlarmEvent.Refresh)
    }
}
