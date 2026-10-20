package org.fossify.clock.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HaSkipLogicTest {
    private val tz = TimeZone.getTimeZone("America/Sao_Paulo")

    /** 2026-10-20 é terça. */
    private fun at(day: Int, h: Int, m: Int): Long = Calendar.getInstance(tz).apply {
        clear(); set(2026, Calendar.OCTOBER, day, h, m, 0)
    }.timeInMillis

    private val tue = 1 shl 1
    private val wed = 1 shl 2
    private val tueWedThu = tue or wed or (1 shl 3)

    @Test
    fun recorrente_pula_so_aquela_vez_e_toca_no_proximo_dia() {
        val now = at(20, 6, 0)
        val target = HaSkipLogic.skipTarget(7 * 60 + 30, tueWedThu, now, tz)
        assertEquals(at(20, 7, 30), target)
        assertEquals(at(21, 7, 30), HaSkipLogic.effectiveNext(450, tueWedThu, target, now, tz))
        // Depois da ocorrência pulada, volta ao normal (sem precisar do HA).
        val later = at(20, 8, 0)
        assertFalse(HaSkipLogic.isSkipActive(target, later))
        assertEquals(at(21, 7, 30), HaSkipLogic.effectiveNext(450, tueWedThu, target, later, tz))
    }

    @Test
    fun recorrente_de_um_dia_so_pula_para_semana_seguinte() {
        val now = at(20, 6, 0)
        val target = HaSkipLogic.skipTarget(450, tue, now, tz)
        assertEquals(at(27, 7, 30), HaSkipLogic.effectiveNext(450, tue, target, now, tz))
    }

    @Test
    fun unico_pulado_nao_toca_ate_passar() {
        val now = at(20, 6, 0)
        val target = HaSkipLogic.skipTarget(450, HaSkipLogic.TODAY, now, tz)
        assertEquals(at(20, 7, 30), target)
        assertNull(HaSkipLogic.effectiveNext(450, HaSkipLogic.TODAY, target, now, tz))
        assertNull(HaSkipLogic.effectiveNext(450, HaSkipLogic.TODAY, target, at(20, 7, 29), tz))
    }

    @Test
    fun madrugada_desligar_as_23h_pula_o_0333_de_amanha() {
        val now = at(20, 23, 0)
        val t = 3 * 60 + 33
        assertEquals(at(21, 3, 33), HaSkipLogic.skipTarget(t, HaSkipLogic.TOMORROW, now, tz))
        val rec = HaSkipLogic.skipTarget(t, tueWedThu, now, tz)
        assertEquals(at(21, 3, 33), rec) // quarta 03:33, não terça
        assertEquals(at(22, 3, 33), HaSkipLogic.effectiveNext(t, tueWedThu, rec, now, tz))
        // Depois da meia-noite o pulo continua valendo para o 03:33 (e o único não toca).
        assertNull(HaSkipLogic.effectiveNext(t, HaSkipLogic.TOMORROW, at(21, 3, 33), at(21, 0, 30), tz))
    }

    @Test
    fun religar_desfaz_o_pulo() {
        val now = at(20, 6, 0)
        assertEquals(at(20, 7, 30), HaSkipLogic.effectiveNext(450, tueWedThu, null, now, tz))
        assertEquals(at(20, 7, 30), HaSkipLogic.effectiveNext(450, HaSkipLogic.TODAY, null, now, tz))
    }

    @Test
    fun editar_para_outro_horario_descarta_o_pulo() {
        assertTrue(HaSkipLogic.keepSkipAfterEdit(450, 450))
        assertFalse(HaSkipLogic.keepSkipAfterEdit(450, 460))
    }

    @Test
    fun desligar_grupo_pula_pendentes_e_nao_pula_soneca_nem_quem_ja_tocou() {
        val now = at(20, 7, 32) // o 1º (07:30, recorrente) já tocou; o 2º está em soneca
        val members = listOf(
            HaSkipLogic.Member(1, at(21, 7, 30), snoozed = false), // já tocou: próximo é amanhã
            HaSkipLogic.Member(2, at(21, 7, 40), snoozed = true),  // soneca: cancela, não pula amanhã
            HaSkipLogic.Member(3, at(20, 7, 50), snoozed = false),
            HaSkipLogic.Member(4, at(20, 8, 0), snoozed = false),
        )
        assertEquals(setOf(3, 4), HaSkipLogic.groupSkipTargets(members, now))
    }

    @Test
    fun desligar_grupo_antes_de_tudo_pula_todos() {
        val now = at(20, 23, 0)
        val members = (0..3).map { HaSkipLogic.Member(it, at(21, 3, 30 + it * 5), false) }
        assertEquals(setOf(0, 1, 2, 3), HaSkipLogic.groupSkipTargets(members, now))
    }

    @Test
    fun todo_grupo_conta_inclusive_de_um_alarme_sem_grupo_fica_solto() {
        val g = HaSkipLogic.groupsOf(mapOf(1 to "t_manha", 2 to "t_manha", 3 to "t_tarde", 4 to "t_tarde", 5 to null, 6 to "r"))
        assertEquals(setOf("t_manha", "t_tarde", "r"), g.keys)
        assertEquals(listOf(6), g["r"])
    }
}
