package jp.hayase.skk

import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CandidateStatusViewTest {
    @Test fun `通常表示は書記素境界で制限し巨大な一文字をレイアウトしない`() {
        val family = "👩‍💻"
        val source = family.repeat(120) + "末尾"
        val preview = CandidateTextBounds.preview(source)
        assertTrue(preview.truncated)
        assertEquals(family.repeat(CandidateTextBounds.NORMAL_CLUSTERS), preview.text)
        assertValidUtf16(preview.text)

        val giant = "a" + "\u0301".repeat(CandidateTextBounds.NORMAL_SCAN_UTF16 * 2)
        val giantPreview = CandidateTextBounds.preview(giant)
        assertTrue(giantPreview.truncated)
        assertEquals(CandidateTextBounds.LONG_GRAPHEME_PLACEHOLDER, giantPreview.text)
        assertTrue(giantPreview.text.length < 64)

        val oneMiB = "候".repeat(1_048_576)
        val oneMiBPreview = CandidateTextBounds.preview(oneMiB)
        assertEquals(CandidateTextBounds.NORMAL_CLUSTERS, oneMiBPreview.text.length)
        assertTrue(oneMiBPreview.truncated)
        assertTrue(CandidateTextBounds.page(oneMiB, 0).text.length <=
            CandidateTextBounds.DETAIL_SCAN_UTF16)

        // Unicode 15.1 で追加された ZWJ 列も、端末 OS の Unicode 版に依存せず一文字として扱います。
        val headShakingHorizontally = "🙂‍↔️"
        val recent = CandidateTextBounds.preview(headShakingHorizontally.repeat(120))
        assertEquals(headShakingHorizontally.repeat(CandidateTextBounds.NORMAL_CLUSTERS), recent.text)
    }

    @Test fun `全文ページは通常書記素を保ち巨大書記素だけ明示してコードポイント境界で分割する`() {
        val ordinary = ("候補👩‍💻注釈e\u0301").repeat(900)
        val ordinaryPages = pages(ordinary)
        assertEquals(ordinary, ordinaryPages.joinToString("") { it.text })
        assertTrue(ordinaryPages.none { it.splitLongGrapheme })
        ordinaryPages.forEach { assertValidUtf16(it.text) }

        val giant = "x" + "\u0301".repeat(CandidateTextBounds.DETAIL_SCAN_UTF16 * 2) + "終"
        val giantPages = pages(giant)
        assertEquals(giant, giantPages.joinToString("") { it.text })
        assertTrue(giantPages.any { it.splitLongGrapheme })
        giantPages.forEach { assertValidUtf16(it.text) }
    }

    @Test fun `カーソル直前の巨大書記素は全文を通常表示へ展開しない`() {
        val giant = "a" + "\u0301".repeat(65_535)
        val preview = CandidateTextBounds.previewBeforeCursor(giant, giant.length, 48)
        assertEquals(CandidateTextBounds.LONG_GRAPHEME_PLACEHOLDER, preview.text)
        assertTrue(preview.truncated)

        val ordinary = "先頭" + "👩🏽‍💻".repeat(70)
        val tail = CandidateTextBounds.previewBeforeCursor(ordinary, ordinary.length, 48)
        assertEquals("👩🏽‍💻".repeat(48), tail.text)
        assertTrue(tail.truncated)
    }

    @Test fun `候補変更で全文を閉じ未修飾左右だけがページ操作になる`() {
        val context = RuntimeEnvironment.getApplication()
        val view = CandidateStatusView(context)
        val first = "候補".repeat(3000)
        val identity = CandidateDetailIdentity(0, first, null)
        view.show(CandidateStatusPresentation("状態", identity,
            listOf(CandidateDetailSection("候補本文", first))))
        view.findViewById<View>(R.id.candidate_full_detail).performClick()
        assertTrue(view.isDetailOpen)
        val initial = view.findViewById<TextView>(R.id.candidate_detail_text).text.toString()

        assertTrue(view.handleDetailPaging(key(KeyEvent.KEYCODE_DPAD_RIGHT)))
        val advanced = view.findViewById<TextView>(R.id.candidate_detail_text).text.toString()
        assertTrue(initial != advanced)
        assertFalse(view.handleDetailPaging(key(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON)))
        assertEquals(advanced, view.findViewById<TextView>(R.id.candidate_detail_text).text.toString())

        view.show(CandidateStatusPresentation("次", CandidateDetailIdentity(1, "別候補", null),
            listOf(CandidateDetailSection("候補本文", "別候補"))))
        assertFalse(view.isDetailOpen)
    }

    @Test fun `同じ候補識別子でも全文参照が変われば古いページ位置を破棄する`() {
        val view = CandidateStatusView(RuntimeEnvironment.getApplication())
        val selected = "候補"
        val identity = CandidateDetailIdentity(0, selected, null)
        val long = "本文".repeat(4000)
        view.show(CandidateStatusPresentation("状態", identity,
            listOf(CandidateDetailSection("候補本文", long))))
        view.findViewById<View>(R.id.candidate_full_detail).performClick()
        repeat(3) { assertTrue(view.handleDetailPaging(key(KeyEvent.KEYCODE_DPAD_RIGHT))) }
        assertTrue(view.isDetailOpen)

        val short = "短い"
        view.show(CandidateStatusPresentation("状態", identity,
            listOf(CandidateDetailSection("候補本文", short))))
        assertFalse(view.isDetailOpen)
        view.findViewById<View>(R.id.candidate_full_detail).performClick()
        assertTrue(view.findViewById<TextView>(R.id.candidate_detail_text).text.toString().contains(short))
        assertTrue(view.handleDetailPaging(key(KeyEvent.KEYCODE_DPAD_RIGHT)))
    }

    @Test fun `狭い実測幅と大きい文字でも候補領域全体を画面高の四割以内にする`() {
        val context = RuntimeEnvironment.getApplication()
        context.resources.configuration.fontScale = 2f
        val view = CandidateStatusView(context)
        view.show(CandidateStatusPresentation("長い状態\n".repeat(200),
            CandidateDetailIdentity(0, "候補".repeat(2000), "注釈"), listOf(
                CandidateDetailSection("候補本文", "候補".repeat(2000)),
                CandidateDetailSection("注釈", "注釈"),
            )))
        view.findViewById<View>(R.id.candidate_full_detail).performClick()
        val density = context.resources.displayMetrics.density
        val width = (240 * density).toInt()
        val height = (640 * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST),
        )
        assertEquals(208f, view.availableContentWidthDp(), 1f)
        assertTrue(view.measuredHeight <= (context.resources.configuration.screenHeightDp * density * .4f).toInt())
        val innerWidth = width - (32 * density).toInt()
        for (id in listOf(R.id.candidate_detail_previous, R.id.candidate_detail_next,
            R.id.candidate_detail_close)) {
            val button = view.findViewById<Button>(id)
            assertTrue("操作ボタンが狭幅の内側を超えています: ${button.measuredWidth} > $innerWidth",
                button.measuredWidth <= innerWidth)
        }
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.scrollTo(0, 100)
        view.show(CandidateStatusPresentation("次の候補", CandidateDetailIdentity(1, "別", null),
            listOf(CandidateDetailSection("候補本文", "別"))))
        assertEquals(0, view.scrollY)
    }

    private fun pages(value: String): List<DetailPage> {
        val result = mutableListOf<DetailPage>()
        var offset = 0
        while (offset < value.length) {
            val page = CandidateTextBounds.page(value, offset)
            assertTrue(page.nextOffset > offset)
            result += page
            offset = page.nextOffset
        }
        return result
    }

    private fun key(code: Int, meta: Int = 0) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta)

    private fun assertValidUtf16(value: String) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (Character.isHighSurrogate(char)) {
                assertTrue(index + 1 < value.length && Character.isLowSurrogate(value[index + 1]))
                index += 2
            } else {
                assertFalse(Character.isLowSurrogate(char))
                index++
            }
        }
    }
}
