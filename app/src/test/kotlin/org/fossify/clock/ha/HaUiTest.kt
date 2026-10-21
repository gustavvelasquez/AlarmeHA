package org.fossify.clock.ha

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HaUiTest {
    private val tz = TimeZone.getTimeZone("America/Sao_Paulo")
    private fun at(day: Int, h: Int, m: Int): Long = Calendar.getInstance(tz).apply {
        clear(); set(2026, Calendar.OCTOBER, day, h, m, 0)
    }.timeInMillis

    @Test
    fun cabecalho_em_horas_e_minutos() {
        val now = at(21, 8, 32)
        assertEquals("Alarme em 19 horas e 1 minuto", HaUi.headerTitle(at(22, 3, 33), now))
        assertEquals("Alarme em 1 hora", HaUi.headerTitle(at(21, 9, 32), now))
        assertEquals("Alarme em 5 minutos", HaUi.headerTitle(at(21, 8, 37), now))
        assertEquals("Alarme em 1 minuto", HaUi.headerTitle(now + 10_000, now))
        assertEquals("Alarme em 2 dias e 3 horas", HaUi.headerTitle(at(23, 11, 32), now))
        assertEquals("Todos os alarmes estão desativados", HaUi.headerTitle(null, now))
        assertEquals("qui., 22 de out., 03:33", HaUi.headerSubtitle(at(22, 3, 33), tz))
    }

    @Test
    fun letras_da_semana_comecam_no_domingo() {
        val qs = HaUi.weekLetters(8 or 16) // qui + sex
        assertEquals(listOf("D", "S", "T", "Q", "Q", "S", "S"), qs.map { it.first })
        assertEquals(listOf(false, false, false, false, true, true, false), qs.map { it.second })
        assertEquals(listOf(true, false, false, false, false, false, true), HaUi.weekLetters(64 or 32).map { it.second })
        assertEquals(List(7) { false }, HaUi.weekLetters(-1).map { it.second })
    }

    @Test
    fun nome_do_cartao_so_o_tipo_e_dica_so_se_repetir() {
        assertEquals("Trabalho", HaUi.baseName("Trabalho · Plantão · amanhã", "x"))
        assertEquals("Remédio", HaUi.baseName("Remédio", "tipo_remedio"))
        assertEquals("g1", HaUi.baseName(null, "g1"))
        val t = HaUi.cardTitles(listOf(
            HaUi.Card("a", "Trabalho · Plantão", at(21, 7, 0)),
            HaUi.Card("b", "Trabalho · Plantão · amanhã", at(22, 7, 0)),
            HaUi.Card("r", "Remédio", at(21, 9, 0)),
        ), tz)
        assertEquals("Trabalho" to "qua., 21 de out.", t["a"])
        assertEquals("Trabalho" to "qui., 22 de out.", t["b"])
        assertEquals("Remédio" to null, t["r"])
    }

    @Test
    fun grupos_antes_dos_soltos_mantendo_a_ordem() {
        assertEquals(listOf(2, 4, 1, 3, 5), HaUi.sections(listOf(1, 2, 3, 4, 5)) { it % 2 == 0 })
    }

    @Test
    fun hora_sempre_24h() {
        assertEquals("07:00", HaUi.hhmm(7 * 60))
        assertEquals("21:05", HaUi.hhmm(21 * 60 + 5))
        assertEquals("00:30", HaUi.hhmm(30))
    }

    @Test
    fun total_do_grupo() {
        assertEquals("1 alarme", HaUi.countDescription(1))
        assertEquals("4 alarmes", HaUi.countDescription(4))
    }

    @Test
    fun pulado_diz_quando_volta() {
        val now = at(21, 10, 0)
        val diario = HaListOrder.Item(1, 18 * 60, 127, true, "r", at(21, 18, 0))
        assertEquals("Volta qui., 22 de out.", HaUi.skippedLine(diario, now, tz))
        val unico = HaListOrder.Item(2, 18 * 60, -1, true, "r", at(21, 18, 0))
        assertEquals("Pulado", HaUi.skippedLine(unico, now, tz))
    }
}
