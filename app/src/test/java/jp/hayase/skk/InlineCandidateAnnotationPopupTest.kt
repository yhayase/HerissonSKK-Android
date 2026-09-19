package jp.hayase.skk

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
class InlineCandidateAnnotationPopupTest {
    private fun anchor(text: String = "日本", flags: Int = CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION,
                       x: Float = 100f, matrix: Matrix = Matrix()) = CursorAnchorInfo.Builder()
        .setComposingText(0, text).setSelectionRange(text.length, text.length)
        .setInsertionMarkerLocation(x, 40f, 55f, 60f, flags).setMatrix(matrix).build()

    @Test fun `座標は画面へ変換し候補と表示範囲が一致するときだけ使う`() {
        val viewport = Rect(0, 0, 400, 300)
        val matrix = Matrix().apply { setScale(2f, 2f); postTranslate(10f, 20f) }
        val bounds = InlineCandidateAnnotationPopup.anchorBounds(anchor(matrix = matrix), "日本", viewport)
        assertEquals(RectF(210f, 100f, 210f, 140f), bounds)
        assertNull(InlineCandidateAnnotationPopup.anchorBounds(anchor(), "二本", viewport))
        assertNull(InlineCandidateAnnotationPopup.anchorBounds(anchor(flags = 0), "日本", viewport))
        assertNull(InlineCandidateAnnotationPopup.anchorBounds(anchor(x = Float.NaN), "日本", viewport))
        assertNull(InlineCandidateAnnotationPopup.anchorBounds(anchor(x = 500f), "日本", viewport))
        assertNotNull(InlineCandidateAnnotationPopup.anchorBounds(anchor(text = "▼日本"), "日本", viewport))
    }

    @Test fun `注釈は候補末尾の右へ置き狭い場合は行を避けて画面内へ収める`() {
        val viewport = Rect(0, 0, 400, 300)
        val beside = InlineCandidateAnnotationPopup.place(RectF(100f, 40f, 100f, 60f), viewport, 80, 16, 4)!!
        assertEquals(Rect(104, 42, 184, 58), beside)
        val below = InlineCandidateAnnotationPopup.place(RectF(380f, 40f, 380f, 60f), viewport, 80, 16, 4)!!
        assertEquals(Rect(320, 64, 400, 80), below)
        val above = InlineCandidateAnnotationPopup.place(RectF(380f, 275f, 380f, 295f), viewport, 80, 16, 4)!!
        assertEquals(Rect(320, 255, 400, 271), above)
        assertNull(InlineCandidateAnnotationPopup.place(RectF(380f, 0f, 380f, 300f), viewport, 80, 16, 4))
    }
}
