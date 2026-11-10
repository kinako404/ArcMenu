package com.fentai.arcmenu.paper.input

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SyntheticTeleportLedgerTest {
    private val player = UUID.randomUUID()

    @Test
    fun `legacy accept is followed by one consumed PosRot reply`() {
        val ledger = SyntheticTeleportLedger()
        ledger.pending(player) += -1
        assertTrue(ledger.accept(player, -1, rotationInAccept = false))
        assertTrue(ledger.claimResponse(player))
        assertFalse(ledger.claimResponse(player))
    }

    @Test
    fun `accept carrying rotation leaves real PosRot packets untouched`() {
        val ledger = SyntheticTeleportLedger()
        repeat(600) { ledger.pending(player) += -1 - it }
        repeat(600) { assertTrue(ledger.accept(player, -1 - it, rotationInAccept = true)) }
        assertFalse(ledger.claimResponse(player))
    }

    @Test
    fun `unknown ids and forgotten players are not consumed`() {
        val ledger = SyntheticTeleportLedger()
        ledger.pending(player) += -5
        assertFalse(ledger.accept(player, 7, rotationInAccept = false))
        assertTrue(ledger.accept(player, -5, rotationInAccept = false))
        ledger.pending(player) += -6
        ledger.forget(player)
        assertFalse(ledger.claimResponse(player))
        assertFalse(ledger.accept(player, -6, rotationInAccept = false))
        assertTrue(ledger.pending(player).isEmpty())
    }
}
