package jp.hayase.skk

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 実 Android の TextView 測定と描画で、巨大候補の通常表示と全文ページを確認します。 */
@RunWith(AndroidJUnit4::class)
class CandidateStatusAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

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

    private fun createView(candidate: String, annotation: String): CandidateStatusView = onMain {
        CandidateStatusView(narrowLargeFontContext()).also { view ->
            val candidatePreview = CandidateTextBounds.preview(candidate).text
            val annotationPreview = CandidateTextBounds.preview(annotation).text
            view.show(CandidateStatusPresentation(
                text = "候補: $candidatePreview\n注釈: $annotationPreview",
                detailIdentity = CandidateDetailIdentity(0, candidate, annotation),
                detailSections = listOf(
                    CandidateDetailSection("候補本文", candidate),
                    CandidateDetailSection("注釈", annotation),
                ),
            ))
        }
    }

    private fun openAndLayOut(view: CandidateStatusView) {
        onMain {
            layout(view)
            view.findViewById<View>(R.id.candidate_full_detail).performClick()
            layout(view)
        }
        assertTrue(view.isDetailOpen)
        assertHeightBounded(view)
    }

    private fun layout(view: CandidateStatusView) {
        val density = view.resources.displayMetrics.density
        val width = (240 * density).toInt()
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

    private fun narrowLargeFontContext(): Context {
        val base = instrumentation.targetContext
        val configuration = Configuration(base.resources.configuration).apply {
            fontScale = 2f
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
