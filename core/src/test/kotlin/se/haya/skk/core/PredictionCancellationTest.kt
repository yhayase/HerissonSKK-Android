package se.haya.skk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PredictionCancellationTest {
    @Test fun `中止は登録済み処理を一度だけ呼び解除済み処理を呼ばない`() {
        val cancellation = PredictionCancellation()
        var called = 0
        cancellation.onCancel { error("他の通知を妨げません") }
        cancellation.onCancel { called++ }
        cancellation.onCancel { called += 100 }.close()
        cancellation.cancel()
        cancellation.cancel()
        assertEquals(1, called)
        cancellation.onCancel { called++ }.close()
        assertEquals(2, called)
        assertThrows(PredictionCancelledException::class.java) { cancellation.throwIfCancelled() }
    }

    @Test fun `スコープを入れ子にしても中止後に前のスコープとスレッドを復元する`() {
        val outer = PredictionCancellation()
        val inner = PredictionCancellation()
        outer.run {
            assertSame(outer, PredictionCancellation.current)
            assertThrows(PredictionCancelledException::class.java) {
                inner.run {
                    assertSame(inner, PredictionCancellation.current)
                    inner.cancel()
                    PredictionCancellation.check()
                }
            }
            assertSame(outer, PredictionCancellation.current)
            PredictionCancellation.check()
        }
        assertNull(PredictionCancellation.current)
    }
}
