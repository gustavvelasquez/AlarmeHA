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

    /** Alarme do grupo que já tocou (o HA o mantém desligado no bloco até o grupo acabar). */
    fun isRung(item: Item, groups: Set<String>): Boolean = !item.enabled && item.group != null && item.group in groups
}
