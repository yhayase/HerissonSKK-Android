package se.haya.skk

import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.MotionEvent
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.haya.skk.core.CandidateView
import se.haya.skk.core.DictionaryCandidate
import se.haya.skk.core.DynamicCompletionView
import se.haya.skk.core.RegistrationLevelView
import se.haya.skk.core.RegistrationView

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class RegistrationEditorViewTest {
    @Test fun `物理候補を本文カーソルへ一体表示する`() {
        val candidate = CandidateView(DictionaryCandidate("候補"), "候補", 0, 1)
        val value = RegistrationPresentation.from(registration(
            body = "前後", cursor = 1, innerComposing = "こうほ", innerCursor = 3,
            innerCandidate = candidate), touch = false)

        assertEquals("前▼候補後", value.text)
        assertEquals(4, value.cursor)
        assertEquals(1, value.composingStart)
        assertEquals(4, value.composingEnd)
        assertFalse(value.canEdit)
        assertFalse(value.canRegister)
    }

    @Test fun `タッチ候補は読みを表示し候補本文を混ぜない`() {
        val candidate = CandidateView(DictionaryCandidate("変換結果"), "変換結果", 0, 1)
        val value = RegistrationPresentation.from(registration(
            body = "本文", cursor = 1, innerComposing = "よみ", innerCursor = 1,
            innerCandidate = candidate), touch = true)

        assertEquals("本▼よみ文", value.text)
        assertEquals(3, value.cursor)
        assertFalse(value.text.contains("変換結果"))
    }

    @Test fun `空の読みもマーカーを表示し登録と本文タップを無効にする`() {
        val value = RegistrationPresentation.from(registration(
            body = "本文", cursor = 2, innerComposing = "", innerCursor = 0), touch = false)
        assertEquals("本文▽", value.text)
        assertFalse(value.canEdit)
        assertFalse(value.canRegister)
    }

    @Test fun `待機中の子音には読みに入ったことを示すマーカーを付けない`() {
        val value = RegistrationPresentation.from(registration(
            body = "本文", cursor = 2, innerComposing = "k", innerCursor = null), touch = false)
        assertEquals("本文k", value.text)
        assertFalse(value.canRegister)
    }

    @Test fun `物理補完はカーソル後方だけへ薄い提案として挿入する`() {
        val value = RegistrationPresentation.from(registration(
            body = "前後", cursor = 1, innerComposing = "かな", innerCursor = 2), touch = false,
            completion = DynamicCompletionView("かな", "しい"))
        assertEquals("前▽かなしい後", value.text)
        assertEquals(4, value.cursor)
        assertEquals(4, value.completionStart)
        assertEquals(6, value.completionEnd)
        assertEquals("前▽かな後", value.text.removeRange(value.completionStart!!, value.completionEnd!!))
    }

    @Test fun `確定本文だけなら空でない時だけ登録できる`() {
        val clean = RegistrationPresentation.from(registration(body = "本文", cursor = 1), false)
        assertTrue(clean.canEdit)
        assertTrue(clean.canRegister)
        assertFalse(RegistrationPresentation.from(registration(body = "", cursor = 0), false).canRegister)
    }

    @Test fun `単一編集面は複数行全文とカーソルと直近の親だけを表示する`() {
        val context = RuntimeEnvironment.getApplication()
        val surface = CandidateStatusView(context).apply { physicalPresentation = true }
        val value = RegistrationPresentation.from(registration(
            // Robolectric は幅による TextView の折返しを再現しないため、JVM では改行保持を検証します。
            depth = 3, reading = "現在", body = "long registration body\n".repeat(40), cursor = 120,
            hierarchy = listOf(
                RegistrationLevelView(1, "祖先", false),
                RegistrationLevelView(2, "親", false),
                RegistrationLevelView(3, "現在", true))), false)
        surface.show(CandidateStatusPresentation("", registration = value))
        surface.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
        surface.layout(0, 0, surface.measuredWidth, surface.measuredHeight)

        assertEquals("単語登録：現在", surface.findViewById<TextView>(R.id.registration_title).text)
        assertEquals("親：親", surface.findViewById<TextView>(R.id.registration_parent).text)
        assertFalse(surface.findViewById<TextView>(R.id.registration_parent).text.contains("祖先"))
        val editor = surface.findViewById<RegistrationTextView>(R.id.registration_editor)
        assertFalse(EditText::class.java.isInstance(editor))
        assertEquals(Int.MAX_VALUE, editor.maxLines)
        assertNull(editor.ellipsize)
        assertTrue(editor.lineCount > 1)
        assertEquals(120, editor.cursor)
    }

    @Test fun `変換範囲を強調し保存中は登録操作を無効にする`() {
        val view = RegistrationEditorView(RuntimeEnvironment.getApplication())
        view.show(RegistrationPresentation("よみ", null, "前▽読み後", 4, 1, 4, null, null,
            canEdit = false, canRegister = false, saving = true))
        val spans = (view.editorView.text as Spanned).getSpans(0, view.editorView.text.length,
            BackgroundColorSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals("登録中…", view.registerButton.text)
        assertFalse(view.registerButton.isEnabled)
        assertFalse(view.cancelButton.isEnabled)
    }

    @Test fun `移動イベントを欠く大きな指移動でも本文カーソルを動かさない`() {
        val view = RegistrationEditorView(RuntimeEnvironment.getApplication())
        val moved = mutableListOf<Int>()
        view.setCallbacks({}, {}, moved::add)
        view.show(RegistrationPresentation("よみ", null, "confirmed body", 4,
            null, null, null, null, canEdit = true, canRegister = true, saving = false))
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val downTime = 10L
        view.editorView.dispatchTouchEvent(MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, 4f, 4f, 0))
        view.editorView.dispatchTouchEvent(MotionEvent.obtain(
            downTime, downTime + 20, MotionEvent.ACTION_UP, 240f, 4f, 0))
        assertTrue(moved.isEmpty())
    }

    private fun registration(
        depth: Int = 1,
        reading: String = "よみ",
        body: String,
        cursor: Int,
        innerComposing: String? = null,
        innerCursor: Int? = null,
        innerCandidate: CandidateView? = null,
        hierarchy: List<RegistrationLevelView> = listOf(RegistrationLevelView(depth, reading, true)),
    ) = RegistrationView(depth, reading, body, cursor, innerComposing, innerCursor,
        innerCandidate, saving = false, hierarchy = hierarchy)
}
