package org.fossify.clock.ha

import java.util.Calendar
import java.util.TimeZone

/**
 * Textos e regras do visual 0.9.0 (estilo do Relógio da Samsung). Sem Android: testado em src/test.
 */
object HaUi {
    /** Cabeçalho: "Alarme em 19 horas e 1 minuto" (null = nenhum alarme ligado). */
    fun headerTitle(next: Long?, now: Long): String {
        if (next == null) return "Todos os alarmes estão desativados"
        val totalMin = maxOf(1L, (next - now + 59_999L) / 60_000L)
        val d = totalMin / (24 * 60)
        val h = (totalMin % (24 * 60)) / 60
        val m = totalMin % 60
        fun u(n: Long, um: String, varios: String) = "$n ${if (n == 1L) um else varios}"
        val parts = when {
            d > 0 -> listOfNotNull(u(d, "dia", "dias"), if (h > 0) u(h, "hora", "horas") else null)
            h > 0 -> listOfNotNull(u(h, "hora", "horas"), if (m > 0) u(m, "minuto", "minutos") else null)
            else -> listOf(u(m, "minuto", "minutos"))
        }
        return "Alarme em " + parts.joinToString(" e ")
    }

    /** Linha de baixo do cabeçalho: "qui., 22 de out., 03:33". */
    fun headerSubtitle(next: Long?, tz: TimeZone = TimeZone.getDefault()): String {
        if (next == null) return ""
        val cal = Calendar.getInstance(tz).apply { timeInMillis = next }
        return HaListOrder.longDate(next, tz) + ", " + "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    /** Letras da semana começando no domingo (D S T Q Q S S) e se o dia está marcado (bits do app: seg=1 … dom=64). */
    fun weekLetters(days: Int): List<Pair<String, Boolean>> {
        val letters = listOf("D", "S", "T", "Q", "Q", "S", "S")
        val bits = listOf(64, 1, 2, 4, 8, 16, 32)
        return letters.indices.map { letters[it] to (days > 0 && days and bits[it] != 0) }
    }

    /** Nome do grupo no cartão: só o tipo ("Trabalho · Plantão · amanhã" -> "Trabalho"). */
    fun baseName(groupName: String?, group: String): String =
        (groupName ?: group).substringBefore(" · ").trim().ifEmpty { group }

    data class Card(val group: String, val groupName: String?, val nextRing: Long?)

    /**
     * Nome mostrado em cada cartão e, só quando dois cartões ficariam com o mesmo nome (ex.: Trabalho
     * de hoje e de amanhã), uma dica discreta com o dia do próximo toque ("qui., 22 de out.").
     */
    fun cardTitles(cards: List<Card>, tz: TimeZone = TimeZone.getDefault()): Map<String, Pair<String, String?>> {
        val names = cards.associate { it.group to baseName(it.groupName, it.group) }
        val dup = names.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return cards.associate { c ->
            val n = names.getValue(c.group)
            c.group to (n to if (n in dup) c.nextRing?.let { HaListOrder.longDate(it, tz) } else null)
        }
    }

    /** Lista estilo Samsung: primeiro os cartões de grupo, depois os soltos; cada parte pela ordem de toque. */
    fun sections(cards: List<Int>, isGroup: (Int) -> Boolean): List<Int> {
        val (g, s) = cards.partition(isGroup)
        return g + s
    }

    /** Hora sempre em 24 h, como no Relógio da Samsung configurado em 24 h ("07:00", "21:00"). */
    fun hhmm(minutes: Int): String = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)

    /** Cartão do grupo mostra só o total; leitura de tela: "4 alarmes". */
    fun countDescription(total: Int): String = if (total == 1) "1 alarme" else "$total alarmes"

    /** Pulado: "volta qui., 22 de out." (próximo toque depois do pulo) ou só "Pulado". */
    fun skippedLine(item: HaListOrder.Item, now: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val back = HaSkipLogic.effectiveNext(item.timeInMinutes, item.days, item.skippedAt, now, tz)
        return if (back != null) "Volta " + HaListOrder.longDate(back, tz) else "Pulado"
    }

    /** Estado de uma letra da semana numa linha do grupo. */
    const val DAY_NONE = 0
    const val DAY_ON = 1
    const val DAY_OFF = 2 // pulado/desligado/já tocou: letra apagada

    data class Member(
        val alarmId: Int, val timeInMinutes: Int, val label: String, val days: Int,
        val on: Boolean, val ended: Boolean, val at: Long?,
    )

    /**
     * Linha visual da tela do grupo. `states` (D S T Q Q S S) só quando junta 2+ vagas com o mesmo
     * horário e rótulo (ex.: "Teste Alarme 1/4" de qua e qui). `target` = a vaga da próxima
     * ocorrência (a chave age só nela); null se todas já tocaram. `faded` = todas desligadas.
     */
    data class Line(val ids: List<Int>, val states: List<Int>?, val target: Int?, val checked: Boolean, val faded: Boolean)

    private val WEEK_BITS = listOf(64, 1, 2, 4, 8, 16, 32) // D S T Q Q S S

    fun lines(members: List<Member>, tz: TimeZone = TimeZone.getDefault()): List<Line> {
        val groups = LinkedHashMap<Pair<Int, String>, MutableList<Member>>()
        members.forEach { groups.getOrPut(it.timeInMinutes to it.label) { mutableListOf() }.add(it) }
        return groups.values.map { ms ->
            val sorted = ms.sortedBy { it.at ?: Long.MAX_VALUE }
            val target = sorted.firstOrNull { !it.ended }
            val states = if (ms.size < 2) null else {
                val st = MutableList(7) { DAY_NONE }
                sorted.forEach { m ->
                    val idx = if (m.days > 0) WEEK_BITS.indices.filter { m.days and WEEK_BITS[it] != 0 }
                    else listOfNotNull(m.at?.let { at -> (Calendar.getInstance(tz).apply { timeInMillis = at }.get(Calendar.DAY_OF_WEEK) - 1) })
                    idx.forEach { i -> st[i] = if (m.on && !m.ended) DAY_ON else if (st[i] == DAY_ON) DAY_ON else DAY_OFF }
                }
                st
            }
            Line(sorted.map { it.alarmId }, states, target?.alarmId, target?.on == true, ms.none { it.on && !it.ended })
        }
    }
}
