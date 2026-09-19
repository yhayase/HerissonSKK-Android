package jp.hayase.skk.input

import jp.hayase.skk.core.BasicSkkAction
import jp.hayase.skk.core.BasicSkkDictionary
import jp.hayase.skk.core.BasicSkkEngine
import jp.hayase.skk.core.DictionaryCandidate
import jp.hayase.skk.core.RegistrationPolicy
import org.junit.Assert.*
import org.junit.Test

class KeyPressLedgerTest {
    @Test fun heldEnterIsConsumedUntilKeyUp() {
        val ledger = KeyPressLedger()
        var calls = 0
        assertTrue(ledger.down(1, 66, 10, 1, 0, false) { calls++; true })
        assertTrue(ledger.down(1, 66, 10, 1, 1, false) { calls++; false })
        assertEquals(1, calls)
        assertTrue(ledger.up(1, 66, 10))
        assertFalse(ledger.down(1, 66, 20, 1, 0, false) { false })
        assertFalse(ledger.up(1, 66, 20))
    }

    @Test fun sessionSwitchStopsRepeatsButConsumesMatchingKeyUp() {
        val ledger = KeyPressLedger()
        var calls = 0
        ledger.down(1, 29, 10, 1, 0, true) { calls++; true }
        assertTrue(ledger.down(1, 29, 10, 2, 1, true) { calls++; true })
        assertEquals(1, calls)
        assertTrue(ledger.up(1, 29, 10))
    }

    @Test fun unhandledPressRemainsUnhandledEvenIfModeChanges() {
        val ledger = KeyPressLedger()
        ledger.down(1, 29, 10, 1, 0, true) { false }
        assertFalse(ledger.down(1, 29, 10, 1, 1, true) { error("再解釈しません") })
        assertFalse(ledger.up(1, 29, 10))
    }

    @Test fun matchingDeviceAndTimestampAreRequired() {
        val ledger = KeyPressLedger()
        ledger.down(1, 29, 10, 1, 0, true) { true }
        assertFalse(ledger.up(2, 29, 10))
        assertFalse(ledger.up(1, 29, 9))
        assertTrue(ledger.up(1, 29, 10))
    }

    @Test fun repeatablePressRunsAgainWithinSession() {
        val ledger = KeyPressLedger()
        var calls = 0
        ledger.down(1, 67, 10, 1, 0, true) { calls++; true }
        ledger.down(1, 67, 10, 1, 1, true) { calls++; true }
        assertEquals(2, calls)
    }

    @Test fun `繰り返し変換は同じセッションの候補を続けて進める`() {
        val engine = BasicSkkEngine(
            BasicSkkDictionary { listOf(DictionaryCandidate("候補1"), DictionaryCandidate("候補2"), DictionaryCandidate("候補3")) },
            RegistrationPolicy(),
        )
        val ledger = KeyPressLedger()
        engine.dispatch(BasicSkkAction.Text("Ka "))

        assertTrue(ledger.down(1, 62, 10, 1, 0, true) {
            engine.dispatch(BasicSkkAction.ConvertNext).handled
        })
        assertTrue(ledger.down(1, 62, 10, 1, 1, true) {
            engine.dispatch(BasicSkkAction.ConvertNext).handled
        })

        assertEquals("候補3", engine.currentView.candidate?.selected?.text)
        assertTrue(ledger.up(1, 62, 10))
    }
}
