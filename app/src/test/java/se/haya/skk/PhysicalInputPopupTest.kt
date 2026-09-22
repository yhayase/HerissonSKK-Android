package se.haya.skk

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.inputmethod.CursorAnchorInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class PhysicalInputPopupTest {
    private val viewport = Rect(0, 24, 320, 616)

    @Test fun `候補は下を優先して空きがなければ上へ置く`() {
        assertEquals(Rect(40, 120, 240, 220), PhysicalInputPopup.place(RectF(40f, 100f, 40f, 120f), viewport, 200, 100))
        assertEquals(Rect(40, 470, 240, 570), PhysicalInputPopup.place(RectF(40f, 570f, 40f, 590f), viewport, 200, 100))
    }

    @Test fun `上下に空きがなくても候補を縮めず画面内で重ねる`() {
        assertEquals(Rect(0, 116, 320, 616), PhysicalInputPopup.place(RectF(40f, 300f, 40f, 320f), viewport, 320, 500))
        assertEquals(Rect(0, 24, 200, 124), PhysicalInputPopup.place(null, viewport, 200, 100))
    }

    @Test fun `未確定本文がなくても現在の可視カーソルを使える`() {
        val info = CursorAnchorInfo.Builder().setMatrix(Matrix())
            .setSelectionRange(0, 0)
            .setInsertionMarkerLocation(40f, 100f, 110f, 120f, CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION)
            .build()
        assertEquals(RectF(40f, 100f, 40f, 120f), PhysicalInputPopup.anchor(info, viewport))
        assertNull(PhysicalInputPopup.anchor(null, viewport))
    }

    @Test fun `見えないカーソルや画面外の座標は固定位置へ戻す`() {
        val info = CursorAnchorInfo.Builder().setMatrix(Matrix()).setSelectionRange(0, 0)
            .setInsertionMarkerLocation(40f, 100f, 110f, 120f, CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION).build()
        assertNull(PhysicalInputPopup.anchor(info, viewport))
    }
}
