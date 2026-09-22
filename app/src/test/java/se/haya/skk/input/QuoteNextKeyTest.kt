package se.haya.skk.input

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class QuoteNextKeyTest {
    @Test fun `修飾キーは待機を消費せず次の物理キーだけを通す`() {
        val quote = QuoteNextKey()
        val prefix = KeyEvent(90, 90, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q, 0)
        quote.begin(prefix)
        val ctrl = KeyEvent(100, 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0)
        val next = KeyEvent(110, 110, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N, 0)
        assertFalse(quote.consumeDown(prefix))
        assertFalse(quote.consumeDown(ctrl))
        assertTrue(quote.consumeDown(next))
        assertTrue(quote.quotedDown(next))
        assertTrue(quote.quotedDown(KeyEvent.changeAction(next, KeyEvent.ACTION_DOWN)))
        assertFalse(quote.consumeDown(next))
        assertTrue(quote.quotedUp(KeyEvent.changeAction(next, KeyEvent.ACTION_UP)))
        assertFalse(quote.quotedUp(KeyEvent.changeAction(next, KeyEvent.ACTION_UP)))
        quote.begin(prefix)
        quote.reset()
        assertFalse(quote.consumeDown(KeyEvent(120, 120, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0)))
    }

    @Test fun `世代をまたぐキーアップを新しい入力欄へ渡さない`() {
        val ledger = KeyPressLedger()
        assertFalse(ledger.down(1, KeyEvent.KEYCODE_N, 100, 1, 0, true) { false })
        assertTrue(ledger.down(1, KeyEvent.KEYCODE_N, 100, 2, 1, true) { false })
        assertTrue(ledger.up(1, KeyEvent.KEYCODE_N, 100, 2))
        assertFalse(ledger.up(1, KeyEvent.KEYCODE_N, 100, 2))
    }
}
