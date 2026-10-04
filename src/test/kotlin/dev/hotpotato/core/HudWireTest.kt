package dev.hotpotato.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Те же эталонные байты, что и в тестах мода HotPotato-HUD (HudProtocolTest).
 * Если формат изменится только с одной стороны, упадёт один из тестов.
 */
class HudWireTest {

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun stateMatchesModReferenceBytes() {
        val bytes = HudWire.state(HotPotatoGame.Phase.ROUND, 2, 300, 540, 3, 4, true, listOf("Steve"))
        assertEquals("010102000000020000012c0000021c00000003000000040101000553746576" + "65", bytes.toHex())
    }

    @Test
    fun passedMatchesModReferenceBytes() {
        assertEquals("010201000553746576650004416c657801", HudWire.passed("Steve", "Alex", true).toHex())
    }

    @Test
    fun hideMatchesModReferenceBytes() {
        assertEquals("010205", HudWire.hide().toHex())
    }

    @Test
    fun phaseOrderMatchesProtocol() {
        // Номер фазы передаётся по сети: порядок менять нельзя.
        assertEquals(
            listOf("WAITING", "COUNTDOWN", "ROUND", "INTERMISSION", "FINISHED"),
            HotPotatoGame.Phase.entries.map { it.name },
        )
    }
}
