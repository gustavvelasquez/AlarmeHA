package org.fossify.clock.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 0.13.0: aviso de alarmes perdidos e aviso grande com a tela ligada. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HaMissedTest {
    private fun item(id: Int, m: Int) = HaMissed.Item(id, m, "Lembrete $id")

    @Test
    fun junta_horarios_em_portugues() {
        assertEquals("", HaMissed.joinTimes(emptyList(), " e "))
        assertEquals("03:33", HaMissed.joinTimes(listOf("03:33"), " e "))
        assertEquals("03:33 e 03:43", HaMissed.joinTimes(listOf("03:33", "03:43"), " e "))
        assertEquals("03:33, 03:43 e 04:03", HaMissed.joinTimes(listOf("03:33", "03:43", "04:03"), " e "))
    }

    @Test
    fun acumula_na_ordem_e_limita() {
        var list = emptyList<HaMissed.Item>()
        list = HaMissed.append(list, item(1, 213))
        list = HaMissed.append(list, item(2, 223))
        assertEquals(listOf(1, 2), list.map { it.alarmId })
        // O mesmo alarme perdido de novo (outro dia) conta de novo.
        list = HaMissed.append(list, item(1, 213))
        assertEquals(3, list.size)
        repeat(HaMissed.MAX_ITEMS + 5) { list = HaMissed.append(list, item(100 + it, it)) }
        assertEquals(HaMissed.MAX_ITEMS, list.size)
        assertEquals(100 + HaMissed.MAX_ITEMS + 4, list.last().alarmId)
    }

    @Test
    fun grava_e_le_e_tolera_lixo() {
        val list = listOf(item(1, 213), HaMissed.Item(2, 223, ""))
        assertEquals(list, HaMissed.decode(HaMissed.encode(list)))
        assertEquals(emptyList<HaMissed.Item>(), HaMissed.decode(null))
        assertEquals(emptyList<HaMissed.Item>(), HaMissed.decode("{quebrado"))
    }

    @Test
    fun aviso_grande_so_com_tela_ligada_e_desbloqueada() {
        assertTrue(HaHeadsUp.shouldUse(interactive = true, locked = false))
        assertFalse(HaHeadsUp.shouldUse(interactive = true, locked = true))
        assertFalse(HaHeadsUp.shouldUse(interactive = false, locked = true))
        assertFalse(HaHeadsUp.shouldUse(interactive = false, locked = false))
    }
}
