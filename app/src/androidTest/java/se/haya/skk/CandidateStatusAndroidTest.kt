package se.haya.skk

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 実 Android の TextView 測定と描画で、巨大候補の通常表示と全文ページを確認します。 */
@RunWith(AndroidJUnit4::class)
class CandidateStatusAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun `物理候補は大文字設定でも実際に収まる最大行数を表示する`() = onMain {
        for (fontScale in listOf(1f, 2f)) {
            val context = narrowLargeFontContext(fontScale)
            val density = context.resources.displayMetrics.density
            val width = (240 * density).toInt()
            val height = (640 * density).toInt()
            val candidates = List(16) { "候補" to null }
            val predicted = CandidateStatusView(context).physicalPageCapacity(
                candidates, 0, candidates.size, width, height,
            )
            val measuredHeights = (1..candidates.size).map { count ->
                val view = CandidateStatusView(context).apply {
                    physicalPresentation = true
                    show(CandidateStatusPresentation("", menuItems = List(count) {
                        CandidateMenuItem('a', "候補")
                    }))
                }
                layout(view)
                // ScrollView 自体の制限高ではなく、全行を含む内容の高さで判定します。
                view.getChildAt(0).measuredHeight
            }
            val actual = measuredHeights.indexOfLast { it <= (height * .4f).toInt() } + 1
            assertEquals("文字倍率 $fontScale の件数が実測と一致しません: 内容高=$measuredHeights", actual, predicted)
        }
    }

    @Test fun `一MiBの候補と注釈は狭幅大文字でも有界にページ表示する`() {
        val candidate = "候".repeat(1_048_576)
        val annotation = "注".repeat(1_048_576)
        val candidatePreview = CandidateTextBounds.preview(candidate)
        val annotationPreview = CandidateTextBounds.preview(annotation)
        assertTrue(candidatePreview.truncated)
        assertTrue(annotationPreview.truncated)
        assertTrue(candidatePreview.text.length < 1_000)
        assertTrue(annotationPreview.text.length < 1_000)
        assertValidUtf16(candidatePreview.text)
        assertValidUtf16(annotationPreview.text)

        val firstPage = CandidateTextBounds.page(candidate, 0)
        assertTrue(firstPage.nextOffset in 1 until candidate.length)
        assertTrue(firstPage.text.length < 10_000)
        assertValidUtf16(firstPage.text)

        val view = createView(candidate, annotation)
        openAndLayOut(view)
        val detail = view.findViewById<TextView>(R.id.candidate_detail_text)
        val initial = detail.text.toString()
        assertTrue(initial.length < 10_000)
        assertValidUtf16(initial)
        draw(view)
        assertNoHorizontalOverflow(view)

        onMain {
            view.findViewById<View>(R.id.candidate_detail_next).performClick()
            layout(view)
        }
        assertNotEquals(initial, detail.text.toString())
        assertValidUtf16(detail.text.toString())
        draw(view)
        assertNoHorizontalOverflow(view)
    }

    @Test fun `巨大な結合書記素は描画を有界にしコードポイント境界で全文を進める`() {
        val giant = "a" + "\u0301".repeat(1_048_576) + "終"
        val preview = CandidateTextBounds.preview(giant)
        assertTrue(preview.truncated)
        assertTrue(preview.text.length < 1_000)
        assertValidUtf16(preview.text)
        val firstPage = CandidateTextBounds.page(giant, 0)
        assertTrue(firstPage.splitLongGrapheme)
        assertTrue(firstPage.nextOffset in 1 until giant.length)
        assertValidUtf16(firstPage.text)

        val view = createView(giant, giant)
        openAndLayOut(view)
        val detail = view.findViewById<TextView>(R.id.candidate_detail_text)
        val initial = detail.text.toString()
        assertTrue(initial.contains("コードポイント境界"))
        assertTrue(initial.length < 10_000)
        assertValidUtf16(initial)

        onMain {
            view.findViewById<View>(R.id.candidate_detail_next).performClick()
            layout(view)
        }
        assertNotEquals(initial, detail.text.toString())
        assertValidUtf16(detail.text.toString())
        draw(view)
        assertNoHorizontalOverflow(view)
    }

    @Test fun `横長画面の大きい文字では件数を減らし本文を折り返す`() {
        val body = "候補".repeat(60)
        val annotation = "注釈".repeat(60)
        val view = createView(body, annotation)
        onMain { layout(view) }
        assertTrue(view.pageCapacity(listOf(body, "別候補"), 0) == 1)
        val main = descendants(view).filterIsInstance<TextView>().single { it.text.toString() == body }
        assertTrue(main.ellipsize == null && main.maxLines > 1)
        val note = descendants(view).filterIsInstance<TextView>().first {
            it.text.toString().startsWith("注釈") && it !== main
        }
        assertTrue(note.textSize < main.textSize)
        assertTrue(note.maxLines == 1)
        draw(view)
        assertHeightBounded(view)
    }

    @Test fun `長押しで注釈を開き本文を省略せず全文ボタンを出さない`() {
        val body = "あ".repeat(12)
        val view = createView(body, "注".repeat(16))
        onMain { layout(view) }
        assertTrue(view.findViewById<View>(R.id.candidate_full_detail).visibility == View.GONE)
        openAndLayOut(view)
        assertTrue(view.isDetailOpen)
        onMain { view.closeDetail(); layout(view) }
        assertFalse(view.isDetailOpen)
        assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.toString() == body })
    }

    @Test fun `巨大本文の最後までスクロールして参照できる`() {
        val body = "候".repeat(1_048_576) + "末尾"
        val view = createView(body, "注釈")
        onMain { layout(view) }
        val list = descendants(view).filterIsInstance<LongCandidateBodyView>().single()
        onMain { list.setSelection(list.count - 1); layout(view) }
        assertTrue("末尾の表示窓へ移動できません", list.lastVisiblePosition == list.count - 1)
        assertTrue(descendants(list).filterIsInstance<TextView>().any { it.text.endsWith("末尾") })
        draw(view)
    }

    private fun createView(candidate: String, annotation: String): CandidateStatusView = onMain {
        CandidateStatusView(narrowLargeFontContext()).also { view ->
            view.show(CandidateStatusPresentation(
                text = "",
                menuItems = listOf(CandidateMenuItem(' ', candidate, annotation,
                    CandidateTapTarget(1, 1, 0, Any(), 0))),
            ))
        }
    }

    private fun openAndLayOut(view: CandidateStatusView) {
        onMain {
            layout(view)
            descendants(view).first { it.isLongClickable && it.contentDescription != null }.performLongClick()
            layout(view)
        }
        assertTrue(view.isDetailOpen)
        assertHeightBounded(view)
    }

    private fun layout(view: CandidateStatusView, widthDp: Int = 240) {
        val density = view.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (640 * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun draw(view: CandidateStatusView) = onMain {
        val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertHeightBounded(view: CandidateStatusView) {
        val limit = (640 * view.resources.displayMetrics.density * .4f).toInt()
        assertTrue("候補領域が画面高の四割を超えています", view.measuredHeight <= limit)
    }

    private fun assertNoHorizontalOverflow(view: CandidateStatusView) {
        descendants(view).filter { it.visibility == View.VISIBLE }.forEach { child ->
            assertTrue("横方向に表示領域を超えています: ${child.javaClass.name}",
                child.left >= 0 && child.right <= view.width)
        }
        listOf(R.id.candidate_detail_previous, R.id.candidate_detail_next, R.id.candidate_detail_close)
            .map { view.findViewById<View>(it) }
            .forEach { control ->
                assertTrue("操作ボタンの幅がありません", control.width > 0)
                assertTrue("操作ボタンを操作できません", control.isClickable)
            }
    }

    private fun narrowLargeFontContext(fontScale: Float = 2f): Context {
        val base = instrumentation.targetContext
        val configuration = Configuration(base.resources.configuration).apply {
            this.fontScale = fontScale
            screenWidthDp = 240
            screenHeightDp = 640
        }
        return base.createConfigurationContext(configuration)
    }

    private fun descendants(view: View): List<View> = when (view) {
        is ViewGroup -> listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else -> listOf(view)
    }

    private fun assertValidUtf16(value: String) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (Character.isHighSurrogate(character)) {
                assertTrue(index + 1 < value.length && Character.isLowSurrogate(value[index + 1]))
                index += 2
            } else {
                assertFalse(Character.isLowSurrogate(character))
                index++
            }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                result = block()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
        return checkNotNull(result)
    }
}
