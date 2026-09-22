package se.haya.skk

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class RedesignedCandidateViewTest {
    @Test fun `候補本文は省略せず注釈の長押しでは確定しない`() {
        val view = CandidateStatusView(RuntimeEnvironment.getApplication())
        val body = "候補本文".repeat(80)
        val target = CandidateTapTarget(1, 2, 0, Any(), 0)
        var commits = 0
        view.setOnCandidateTapListener { commits++ }
        val presentation = CandidateStatusPresentation("", menuItems = listOf(CandidateMenuItem('a', body, "注釈全文", target)))
        view.show(presentation)
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val text = descendants(view).filterIsInstance<TextView>().single { it.text.toString() == body }
        assertNull(text.ellipsize)
        assertTrue(text.maxLines > 1)
        val tile = descendants(view).single { it.contentDescription?.toString() == "$body、注釈: 注釈全文" }
        assertTrue(tile.performLongClick())
        assertEquals(0, commits)
        assertTrue(view.isDetailOpen)
        view.show(presentation)
        assertTrue("同じ候補の座標更新で注釈を閉じません", view.isDetailOpen)
        view.closeDetail()
        tile.performClick()
        assertEquals(1, commits)
    }

    @Test fun `描画世代だけの更新は注釈を保ち候補と参照元の変更では閉じる`() {
        val annotation = "長い注釈".repeat(2000)
        val candidate = Any()
        val initial = CandidateTapTarget(1, 1, 0, candidate, 0)
        val redrawn = CandidateTapTarget(1, 2, 0, candidate, 0)
        val replacements = listOf(
            CandidateTapTarget(2, 3, 0, candidate, 0) to annotation,
            CandidateTapTarget(1, 3, 0, Any(), 0) to annotation,
            CandidateTapTarget(1, 3, 1, candidate, 0) to annotation,
            CandidateTapTarget(1, 3, 0, candidate, 1) to annotation,
            CandidateTapTarget(1, 3, 0, candidate, 0, CandidateTargetKind.PREDICTION) to annotation,
            CandidateTapTarget(1, 3, 0, candidate, 0) to "別の注釈",
        )
        for (physical in listOf(false, true)) for ((replacement, nextAnnotation) in replacements) {
            val view = CandidateStatusView(RuntimeEnvironment.getApplication()).apply {
                physicalPresentation = physical
            }
            fun show(target: CandidateTapTarget, note: String) = view.show(
                CandidateStatusPresentation("",
                    detailIdentity = if (physical) CandidateDetailIdentity(0, "本文", annotation) else null,
                    detailSections = if (physical) listOf(CandidateDetailSection("注釈", annotation)) else emptyList(),
                    menuItems = listOf(CandidateMenuItem('a', "本文", note, target))))
            show(initial, annotation)
            descendants(view).single { it.isLongClickable && it.contentDescription != null }.performLongClick()
            view.findViewById<View>(R.id.candidate_detail_next).performClick()
            val page = view.findViewById<TextView>(R.id.candidate_detail_text).text.toString()
            show(redrawn, annotation)
            assertTrue("描画世代だけで注釈を閉じません", view.isDetailOpen)
            assertEquals(page, view.findViewById<TextView>(R.id.candidate_detail_text).text.toString())
            assertNotEquals("確定用の描画世代の比較は維持します", initial, redrawn)
            show(replacement, nextAnnotation)
            assertFalse("別の注釈対象へページ位置を持ち越しません", view.isDetailOpen)
        }
    }

    @Test fun `幅で連続候補を区切り巨大な一件も除外しない`() {
        val view = CandidateStatusView(RuntimeEnvironment.getApplication())
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
        assertEquals(1, view.pageCapacity(listOf("長い本文".repeat(100), "次"), 0))
        assertTrue(view.pageCapacity(listOf("a", "b", "c"), 0) >= 2)
    }

    @Test @Config(qualifiers = "w640dp-h800dp")
    fun `物理候補の件数に任意の二行上限を設けない`() {
        val view = CandidateStatusView(RuntimeEnvironment.getApplication())
        view.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
        assertTrue(view.visibleMenuCapacity(physical = true) > 2)
    }

    @Test fun `物理表示の巨大本文にも選択ラベルを表示する`() {
        val view = CandidateStatusView(RuntimeEnvironment.getApplication())
        val body = "候".repeat(4096)
        val presentation = CandidateStatusPresentation("", menuItems = listOf(
            CandidateMenuItem('a', body, target = CandidateTapTarget(1, 1, 0, Any(), 0))))
        view.physicalPresentation = true
        view.show(presentation)
        assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.toString() == "a:" })
        assertEquals(body, descendants(view).filterIsInstance<LongCandidateBodyView>().single().let { list ->
            (0 until list.count).joinToString("") { list.adapter.getItem(it).toString() }
        })
        view.physicalPresentation = false
        view.show(presentation)
        assertFalse(descendants(view).filterIsInstance<TextView>().any { it.text.toString() == "a:" })
    }

    @Test fun `長文の表示窓を連結するとサロゲート境界を含め全文に戻る`() {
        val text = "a".repeat(2047) + "😀" + "b".repeat(5000)
        val count = (text.length + LongCandidateBodyView.CHUNK_SIZE - 1) / LongCandidateBodyView.CHUNK_SIZE
        assertEquals(text, (0 until count).joinToString("") { LongCandidateBodyView.chunk(text, it) })
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
