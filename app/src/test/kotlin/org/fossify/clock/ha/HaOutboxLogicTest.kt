package org.fossify.clock.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HaOutboxLogicTest {
    private val h = HaOutboxLogic.hash("alarmeha_atual")
    private val now = 1_800_000_000_000L
    private fun n(at: Long, seq: Int, ev: String, hash: String = h) = HaOutboxLogic.fileName(at, seq, ev, hash)

    @Test
    fun envia_em_ordem_de_criacao_e_nao_descarta_por_tentativas() {
        val names = listOf(n(now - 3000, 3, "soneca"), n(now - 5000, 1, "tocou"), n(now - 4000, 2, "alterado"))
        val plan = HaOutboxLogic.plan(names, now, h)
        assertEquals(listOf("tocou", "alterado", "soneca"), plan.send.map { HaOutboxLogic.parse(it).event })
        assertTrue(plan.drop.isEmpty())
    }

    @Test
    fun guarda_ate_24h_e_descarta_depois() {
        val keep = n(now - HaOutboxLogic.MAX_AGE_MS + 60_000, 1, "tocou")
        val old = n(now - HaOutboxLogic.MAX_AGE_MS - 60_000, 2, "tocou")
        val plan = HaOutboxLogic.plan(listOf(keep, old), now, h)
        assertEquals(listOf(keep), plan.send)
        assertEquals(listOf(old), plan.drop)
    }

    @Test
    fun descarta_fila_do_webhook_antigo() {
        val antigo = n(now - 1000, 1, "lista", HaOutboxLogic.hash("alarmeha_velho"))
        val plan = HaOutboxLogic.plan(listOf(antigo, n(now, 2, "tocou")), now, h)
        assertEquals(listOf(antigo), plan.drop)
    }

    @Test
    fun arquivo_da_versao_antiga_continua_sendo_enviado() {
        val legado = "${now - 1000}-6f1c2a8e-uuid.json"
        val plan = HaOutboxLogic.plan(listOf(legado), now, h)
        assertEquals(listOf(legado), plan.send)
    }

    @Test
    fun lista_nova_substitui_listas_pendentes_e_so_elas() {
        val l1 = n(now - 3000, 1, "lista"); val t = n(now - 2000, 2, "tocou"); val l2 = n(now - 1000, 3, "lista")
        assertEquals(listOf(l1, l2), HaOutboxLogic.superseded(listOf(l1, t, l2), "lista"))
        assertTrue(HaOutboxLogic.superseded(listOf(l1, t, l2), "tocou").isEmpty())
    }
}
