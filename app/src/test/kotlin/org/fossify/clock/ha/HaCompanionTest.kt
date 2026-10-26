package org.fossify.clock.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaCompanionTest {
    @Test
    fun sem_companion_nao_instalado() {
        assertFalse(HaCompanion.anyInstalled { false })
        assertFalse(HaCompanion.anyInstalled { it == "com.outro.app" })
    }

    @Test
    fun versao_completa_ou_minimal_contam() {
        assertTrue(HaCompanion.anyInstalled { it == "io.homeassistant.companion.android" })
        assertTrue(HaCompanion.anyInstalled { it == "io.homeassistant.companion.android.minimal" })
    }

    @Test
    fun loja_tenta_play_store_e_depois_navegador() {
        assertEquals(
            listOf(
                "market://details?id=io.homeassistant.companion.android",
                "https://play.google.com/store/apps/details?id=io.homeassistant.companion.android"
            ),
            HaCompanion.storeUris()
        )
    }
}
