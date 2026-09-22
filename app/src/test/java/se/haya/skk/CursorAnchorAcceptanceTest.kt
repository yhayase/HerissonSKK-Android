package se.haya.skk

import android.graphics.Matrix
import android.view.inputmethod.CursorAnchorInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CursorAnchorAcceptanceTest {
    @Test fun `選択と未確定本文が一致した現在の通知だけを受理する`() {
        val info = CursorAnchorInfo.Builder().setMatrix(Matrix()).setSelectionRange(3, 3)
            .setComposingText(1, "にほ").build()
        assertTrue(CursorAnchorAcceptance.matches(info, 3 to 3, 1 to "にほ"))
        assertFalse(CursorAnchorAcceptance.matches(info, 4 to 4, 1 to "にほん"))
        assertFalse(CursorAnchorAcceptance.matches(info, 3 to 3, 1 to "かき"))
        assertFalse(CursorAnchorAcceptance.matches(info, 3 to 3, null))
        assertFalse(CursorAnchorAcceptance.matches(info, 3 to 3, 0 to "にほ"))
    }

    @Test fun `未確定本文がないときも選択位置が一致すれば使える`() {
        val info = CursorAnchorInfo.Builder().setMatrix(Matrix()).setSelectionRange(3, 3).build()
        assertTrue(CursorAnchorAcceptance.matches(info, 3 to 3, null))
        assertFalse(CursorAnchorAcceptance.matches(null, 3 to 3, null))
    }
}
