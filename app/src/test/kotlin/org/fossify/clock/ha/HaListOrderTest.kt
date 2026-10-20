package org.fossify.clock.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HaListOrderTest {
    private val tz = TimeZone.getTimeZone("America/Sao_Paulo")
    private val tueWed = (1 shl 1) or (1 shl 2)

    /** 2026-10-20 é terça. */
    private fun at(day: Int, h: Int, m: Int): Long = Calendar.getInstance(tz).apply {
        clear(); set(2026, Calendar.OCTOBER, day, h, m, 0)
    }.timeInMillis

    private fun item(id: Int, h: Int, m: Int, days: Int, group: String? = null, enabled: Boolean = true, skipped: Long? = null) =
        HaListOrder.Item(id, h * 60 + m, days, enabled, group, skipped)

    private val hoje = "trabalho_20261020_1330_teste"
    private val amanha = "trabalho_20261021_1330_teste"

    /** Caso do print de 20/10: remédios, grupo de hoje (2 já tocaram) e grupo de amanhã. */
    private fun caso() = listOf(
        item(1, 18, 0, tueWed), // Quetiapina
        item(2, 21, 0, tueWed), // Litium noite
        item(3, 9, 0, HaSkipLogic.TOMORROW), // Litium manhã
        item(10, 13, 0, HaSkipLogic.TODAY, hoje, enabled = false),
        item(11, 13, 10, HaSkipLogic.TODAY, hoje, enabled = false),
        item(12, 13, 20, HaSkipLogic.TODAY, hoje),
        item(13, 13, 30, HaSkipLogic.TODAY, hoje),
        item(20, 13, 0, HaSkipLogic.TOMORROW, amanha),
        item(21, 13, 10, HaSkipLogic.TOMORROW, amanha),
        item(22, 13, 20, HaSkipLogic.TOMORROW, amanha),
        item(23, 13, 30, HaSkipLogic.TOMORROW, amanha),
        item(30, 7, 0, HaSkipLogic.TODAY), // manual desligado
    ).let { l -> l.map { if (it.alarmId == 30) it.copy(enabled = false) else it } }

    @Test
    fun ordena_por_proximo_toque_com_grupo_em_bloco() {
        val now = at(20, 13, 15)
        val order = HaListOrder.order(caso(), setOf(hoje, amanha), now, tz)
        assertEquals(listOf(10, 11, 12, 13, 1, 2, 3, 20, 21, 22, 23, 30), order)
    }

    @Test
    fun grupo_encerrado_vai_para_o_fim() {
        val items = caso().map { if (it.group == hoje) it.copy(enabled = false) else it }
        val order = HaListOrder.order(items, setOf(hoje, amanha), at(20, 13, 40), tz)
        assertEquals(listOf(1, 2, 3, 20, 21, 22, 23, 10, 11, 12, 13, 30), order)
    }

    @Test
    fun grupo_que_cruza_meia_noite_fica_junto_e_em_ordem() {
        val g = "trabalho_20261021_0010_noturno"
        val items = listOf(
            item(1, 0, 10, HaSkipLogic.TOMORROW, g), item(2, 23, 40, HaSkipLogic.TODAY, g),
            item(3, 0, 0, HaSkipLogic.TOMORROW, g), item(4, 23, 50, HaSkipLogic.TODAY, g),
            item(5, 23, 45, tueWed),
        )
        assertEquals(listOf(2, 4, 3, 1, 5), HaListOrder.order(items, setOf(g), at(20, 22, 0), tz))
    }

    @Test
    fun pulado_continua_no_lugar_do_proprio_horario() {
        val items = listOf(item(1, 9, 0, HaSkipLogic.TODAY, skipped = at(20, 9, 0)), item(2, 10, 0, HaSkipLogic.TODAY))
        assertEquals(listOf(1, 2), HaListOrder.order(items, emptySet(), at(20, 8, 0), tz))
    }

    @Test
    fun botao_do_grupo() {
        val now = at(20, 13, 15)
        val membros = caso().filter { it.group == hoje }
        assertEquals(HaListOrder.GroupButton.OFF, HaListOrder.groupButton(membros, now, tz))
        val comPulo = membros.map { if (it.alarmId == 12) it.copy(skippedAt = at(20, 13, 20)) else it }
        assertEquals(HaListOrder.GroupButton.ON, HaListOrder.groupButton(comPulo, now, tz))
        val encerrado = membros.map { it.copy(enabled = false) }
        assertEquals(HaListOrder.GroupButton.NONE, HaListOrder.groupButton(encerrado, now, tz))
        // Pulo vencido não conta: volta a "Desligar o grupo".
        assertEquals(HaListOrder.GroupButton.OFF, HaListOrder.groupButton(comPulo, at(20, 13, 25), tz))
    }

    @Test
    fun tocado_so_dentro_de_grupo() {
        val g = setOf(hoje)
        assertTrue(HaListOrder.isRung(item(1, 13, 0, -1, hoje, enabled = false), g))
        assertFalse(HaListOrder.isRung(item(1, 13, 0, -1, hoje), g))
        assertFalse(HaListOrder.isRung(item(1, 13, 0, -1, null, enabled = false), g))
    }
}
