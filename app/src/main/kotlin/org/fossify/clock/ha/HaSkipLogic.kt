package org.fossify.clock.ha

import java.util.Calendar
import java.util.TimeZone

/**
 * Regras puras do "pular o próximo toque" (skip once) dos alarmes da agenda do HA.
 * Sem Android: testadas em src/test (HaSkipLogicTest).
 *
 * - Pular guarda o horário (epoch ms) da próxima ocorrência; o alarme continua ligado.
 * - Recorrente (dias da semana): pula só aquela ocorrência e agenda a seguinte.
 * - Único (hoje/amanhã): enquanto o pulo vale, não toca.
 * - Religar apaga o pulo. Depois que a ocorrência passa, o pulo não vale mais.
 */
object HaSkipLogic {
    const val TODAY = -1
    const val TOMORROW = -2

    /** Mesma regra de getTimeOfNextAlarm do Fossify, com "agora" e fuso explícitos. */
    fun nextOccurrence(timeInMinutes: Int, days: Int, now: Long, tz: TimeZone = TimeZone.getDefault()): Long? {
        val cal = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, timeInMinutes / 60)
            set(Calendar.MINUTE, timeInMinutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return when (days) {
            TODAY -> cal.timeInMillis
            TOMORROW -> cal.apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            else -> {
                repeat(8) {
                    val day = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // seg=0 … dom=6
                    if (days and (1 shl day) != 0 && now < cal.timeInMillis) return cal.timeInMillis
                    cal.add(Calendar.DAY_OF_MONTH, 1)
                }
                null
            }
        }
    }

    /** O pulo ainda vale (a ocorrência pulada não passou). */
    fun isSkipActive(skippedAt: Long?, now: Long): Boolean = skippedAt != null && skippedAt > now

    /** Quando o alarme deve tocar de verdade, considerando o pulo; null = não agenda. */
    fun effectiveNext(timeInMinutes: Int, days: Int, skippedAt: Long?, now: Long, tz: TimeZone = TimeZone.getDefault()): Long? {
        val active = isSkipActive(skippedAt, now)
        if (active && days < 0) return null // único pulado: não toca
        val next = nextOccurrence(timeInMinutes, days, now, tz) ?: return null
        if (!active || next != skippedAt) return next
        return nextOccurrence(timeInMinutes, days, skippedAt!! + 1000, tz)
    }

    /** Horário a pular agora: a próxima ocorrência (ex.: às 23h, o das 03:33 de amanhã). */
    fun skipTarget(timeInMinutes: Int, days: Int, now: Long, tz: TimeZone = TimeZone.getDefault()): Long? =
        nextOccurrence(timeInMinutes, days, now, tz)?.takeIf { it > now }

    /** Mudou hora/minuto (o HA reeditou para outro compromisso): o pulo antigo não vale mais. */
    fun keepSkipAfterEdit(oldTimeInMinutes: Int, newTimeInMinutes: Int): Boolean = oldTimeInMinutes == newTimeInMinutes

    data class Member(val alarmId: Int, val next: Long?, val snoozed: Boolean)

    /**
     * Desligar o grupo: quais alarmes pulam o próximo toque. Quem já tocou (recorrente, próximo só
     * amanhã) ou está em soneca não pula a ocorrência de amanhã: a soneca é cancelada e pronto.
     * Janela: até 12 h depois do primeiro toque pendente do grupo.
     */
    fun groupSkipTargets(members: List<Member>, now: Long): Set<Int> {
        val pending = members.filter { !it.snoozed && it.next != null && it.next > now }
        val first = pending.minOfOrNull { it.next!! } ?: return emptySet()
        val limit = first + GROUP_WINDOW_MS
        return pending.filter { it.next!! <= limit }.map { it.alarmId }.toSet()
    }

    /** O grupo só existe com 2 ou mais alarmes. */
    fun groupsOf(groupByAlarm: Map<Int, String?>): Map<String, List<Int>> =
        groupByAlarm.entries.filter { it.value != null }.groupBy({ it.value!! }, { it.key }).filterValues { it.size >= 2 }

    const val GROUP_WINDOW_MS = 12 * 60 * 60 * 1000L
}
