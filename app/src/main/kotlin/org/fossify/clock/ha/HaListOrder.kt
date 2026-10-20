package org.fossify.clock.ha

import java.util.TimeZone

/**
 * Ordem da lista e botões do grupo quando há alarmes do HA. Sem Android: testado em src/test.
 *
 * - Cada grupo (compromisso com 2+ alarmes) é um bloco, posicionado pelo próximo toque do grupo;
 *   dentro do bloco, os alarmes ficam em ordem de horário (os que já tocaram também).
 * - Sem grupo (remédios, manuais): pelo próprio próximo toque.
 * - Desligados sem grupo e grupos já encerrados vão para o fim, na ordem em que estavam.
 */
object HaListOrder {
    data class Item(
        val alarmId: Int,
        val timeInMinutes: Int,
        val days: Int,
        val enabled: Boolean,
        val group: String?,
        val skippedAt: Long?,
    )

    enum class GroupButton { OFF, ON, NONE }

    /** Quando toca de verdade (pulo considerado); para pulado/único sem próximo, a ocorrência pulada. */
    fun nextRing(item: Item, now: Long, tz: TimeZone = TimeZone.getDefault()): Long? {
        if (!item.enabled) return null
        return HaSkipLogic.effectiveNext(item.timeInMinutes, item.days, item.skippedAt, now, tz)
            ?: item.skippedAt?.takeIf { HaSkipLogic.isSkipActive(it, now) }
    }

    /** Instante da ocorrência (para ordenar dentro do grupo), mesmo se já passou/desligado. */
    private fun occurrence(item: Item, now: Long, tz: TimeZone): Long =
        HaSkipLogic.nextOccurrence(item.timeInMinutes, item.days, now, tz) ?: Long.MAX_VALUE

    /** Devolve os ids na ordem de exibição. `groups`: só os grupos válidos (2+ alarmes). */
    fun order(items: List<Item>, groups: Set<String>, now: Long, tz: TimeZone = TimeZone.getDefault()): List<Int> {
        data class Block(val key: Long?, val pos: Int, val ids: List<Int>)
        val blocks = ArrayList<Block>()
        val seen = HashSet<String>()
        items.forEachIndexed { pos, item ->
            val g = item.group?.takeIf { it in groups }
            if (g == null) {
                blocks.add(Block(nextRing(item, now, tz), pos, listOf(item.alarmId)))
            } else if (seen.add(g)) {
                val members = items.filter { it.group == g }
                val key = members.mapNotNull { nextRing(it, now, tz) }.minOrNull()
                val ids = members.sortedWith(compareBy({ occurrence(it, now, tz) }, { it.timeInMinutes })).map { it.alarmId }
                blocks.add(Block(key, pos, ids))
            }
        }
        return blocks
            .sortedWith(compareBy<Block>({ it.key == null }, { it.key ?: 0L }, { it.pos }))
            .flatMap { it.ids }
    }

    /** Algum pulo ativo: "Religar o grupo"; senão, algum por tocar: "Desligar o grupo"; senão nenhum. */
    fun groupButton(members: List<Item>, now: Long, tz: TimeZone = TimeZone.getDefault()): GroupButton = when {
        members.any { it.enabled && HaSkipLogic.isSkipActive(it.skippedAt, now) } -> GroupButton.ON
        members.any { nextRing(it, now, tz)?.let { n -> n > now } == true } -> GroupButton.OFF
        else -> GroupButton.NONE
    }

    /** Na lista principal cada grupo aparece uma vez (cartão): fica o primeiro membro da ordem. */
    fun cards(order: List<Int>, groupOf: Map<Int, String?>, groups: Set<String>): List<Int> {
        val seen = HashSet<String>()
        return order.filter { id -> val g = groupOf[id]?.takeIf { it in groups }; g == null || seen.add(g) }
    }

    data class Summary(val nextRing: Long?, val on: Int, val off: Int)

    /** Resumo do cartão: próximo toque do grupo e quantos ligados (vão tocar) / desligados. */
    fun summary(members: List<Item>, now: Long, tz: TimeZone = TimeZone.getDefault()): Summary {
        val on = members.count { it.enabled && !HaSkipLogic.isSkipActive(it.skippedAt, now) }
        val next = members.filter { it.enabled }.mapNotNull { HaSkipLogic.effectiveNext(it.timeInMinutes, it.days, it.skippedAt, now, tz) }.minOrNull()
        return Summary(next, on, members.size - on)
    }

    /** Grupo misto mostra os dois botões; todo ligado só "Desativar tudo"; todo desligado só "Ativar tudo"
     * (se houver pulo a desfazer); tudo encerrado (já tocou) nenhum. */
    fun cardButtons(members: List<Item>, now: Long, tz: TimeZone = TimeZone.getDefault()): Pair<Boolean, Boolean> {
        val canOff = members.any { nextRing(it, now, tz)?.let { n -> n > now } == true && !HaSkipLogic.isSkipActive(it.skippedAt, now) }
        val canOn = members.any { it.enabled && HaSkipLogic.isSkipActive(it.skippedAt, now) }
        return canOff to canOn
    }

    enum class Ended { RUNG, SKIPPED }

    /** Do grupo e já passou: "Pulado" se aquela ocorrência tinha sido pulada, senão "Tocou às". */
    fun ended(item: Item, groups: Set<String>, now: Long, tz: TimeZone = TimeZone.getDefault()): Ended? {
        if (!isRung(item, groups)) return null
        val skipped = item.skippedAt ?: return Ended.RUNG
        if (HaSkipLogic.isSkipActive(skipped, now)) return Ended.SKIPPED
        val cal = java.util.Calendar.getInstance(tz).apply { timeInMillis = skipped }
        val sameTime = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE) == item.timeInMinutes
        return if (sameTime && now - skipped < 36 * 60 * 60 * 1000L) Ended.SKIPPED else Ended.RUNG
    }

    /** "qua., 21 de out." (pt-BR). */
    fun longDate(ms: Long, tz: TimeZone = TimeZone.getDefault()): String =
        java.text.SimpleDateFormat("EEE, d 'de' MMM", java.util.Locale("pt", "BR")).apply { timeZone = tz }.format(java.util.Date(ms))

    /** Texto do dia de um alarme do HA: data por extenso; recorrente = "Toda ter, qua · qua., 21 de out.". */
    fun dayText(item: Item, recurringDays: String?, now: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val at = (if (item.enabled) HaSkipLogic.effectiveNext(item.timeInMinutes, item.days, item.skippedAt, now, tz) else null)
            ?: HaSkipLogic.nextOccurrence(item.timeInMinutes, item.days, now, tz)
        val date = at?.let { longDate(it, tz) } ?: ""
        return if (item.days > 0 && recurringDays != null) "Toda ${recurringDays.lowercase()} · $date" else date
    }

    /** Alarme do grupo que já tocou (o HA o mantém desligado no bloco até o grupo acabar). */
    fun isRung(item: Item, groups: Set<String>): Boolean = !item.enabled && item.group != null && item.group in groups
}
