package org.fossify.clock.ha

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaOverlayLogicTest {
    @Test
    fun cartaoProprioSoComTelaEmUsoEPermissao() {
        assertTrue(HaOverlay.shouldShow(inUse = true, canDraw = true))
        assertFalse(HaOverlay.shouldShow(inUse = true, canDraw = false)) // fica o aviso da 0.13.0
        assertFalse(HaOverlay.shouldShow(inUse = false, canDraw = true)) // tela cheia
        assertFalse(HaOverlay.shouldShow(inUse = false, canDraw = false))
    }

    @Test
    fun usoDoCelularDecideEntreAvisoETelaCheia() {
        assertTrue(HaHeadsUp.shouldUse(interactive = true, locked = false))
        assertFalse(HaHeadsUp.shouldUse(interactive = true, locked = true))
        assertFalse(HaHeadsUp.shouldUse(interactive = false, locked = false))
    }
}
