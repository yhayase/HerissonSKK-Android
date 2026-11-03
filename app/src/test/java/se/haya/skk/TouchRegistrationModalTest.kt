package se.haya.skk

import android.graphics.Rect
import android.view.View
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.util.ReflectionHelpers
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.input.EditorSession
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], qualifiers = "w480dp-h800dp-mdpi")
class TouchRegistrationModalTest {
    @Test fun `登録窓は安全な上端から実測の文字面上端までを覆う`() {
        assertEquals(Rect(0, 24, 480, 420), TouchRegistrationModal.bounds(Rect(0, 24, 480, 776), 420))
        assertEquals(Rect(0, 24, 480, 776), TouchRegistrationModal.bounds(Rect(0, 24, 480, 776), 900))
        assertTrue(TouchRegistrationModal.bounds(Rect(0, 24, 480, 776), 12).isEmpty)
    }

    @Test fun `短い表示高でも長い本文の下に取消と登録を固定する`() {
        val modal = TouchRegistrationModal(RuntimeEnvironment.getApplication()) { }
        val editor = modal.content.registrationEditor
        val footer = modal.footer as LinearLayout
        assertSame(editor.cancelButton, footer.getChildAt(0))
        assertSame(editor.registerButton, footer.getChildAt(1))
        assertSame(modal.panel, footer.parent)
        assertFalse(footer.parent === modal.content.getChildAt(0))
        modal.content.show(CandidateStatusPresentation("候補の説明".repeat(80),
            registration = RegistrationPresentation("よみ", "親", "登録本文\n".repeat(100),
                20, null, null, null, null, true, true, false)))
        fun layout(height: Int) {
            modal.root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            modal.root.layout(0, 0, 480, height)
            assertEquals(height, modal.panel.bottom)
            assertEquals(modal.panel.height, footer.bottom)
            assertTrue(modal.content.bottom <= footer.top)
            assertTrue(editor.cancelButton.height >= 48)
            assertTrue(editor.registerButton.height >= 48)
            assertTrue(footer.top >= 0)
        }
        layout(450)
        layout(120)
        assertTrue(modal.content.height <= 40)
    }

    @Test fun `操作欄だけを表示できる高さで本文を領域外へ押し出さない`() {
        val modal = TouchRegistrationModal(RuntimeEnvironment.getApplication()) { }
        modal.content.show(CandidateStatusPresentation("", registration = RegistrationPresentation(
            "よみ", null, "本文", 2, null, null, null, null, true, true, false)))
        modal.root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        modal.root.layout(0, 0, 480, 80)
        assertEquals(0, modal.content.height)
        assertEquals(68, modal.footer.height)
        assertEquals(80, modal.panel.height)
    }
    @Test fun `登録窓は入力先のフォーカスを保ち文字キーを窓の外へ残す`() {
        withModal { _, modal, _, strip, field ->
            assertTrue(modal.isShowing)
            assertTrue(field.hasFocus())
            val params = modal.root.layoutParams as WindowManager.LayoutParams
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG, params.type)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            val location = IntArray(2).also(strip::getLocationOnScreen)
            assertEquals(location[1] + strip.height, params.y + params.height)
            assertTrue(modal.root.isClickable)
        }
    }

    @Test fun `戻るの対応する指離しだけが現在の登録枠を一段取り消す`() {
        withModal { service, modal, session, _, _ ->
            val expected = session.view.registration!!
            assertEquals(2, expected.depth)
            val down = KeyEvent(10, 10, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
            assertTrue(service.onKeyDown(KeyEvent.KEYCODE_BACK, down))
            assertSame(expected, session.view.registration)
            assertTrue(service.onKeyUp(KeyEvent.KEYCODE_BACK, KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
            assertEquals(1, session.view.registration?.depth)
            assertFalse(modal.isShowing)
        }
    }

    @Test fun `戻る押下後に登録枠が変わると古い指離しを次の枠へ適用しない`() {
        withModal { service, _, session, _, _ ->
            val down = KeyEvent(20, 20, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
            assertTrue(service.onKeyDown(KeyEvent.KEYCODE_BACK, down))
            assertTrue(session.cancelRegistration(session.view.registration!!))
            val parent = session.view.registration
            assertTrue(service.onKeyUp(KeyEvent.KEYCODE_BACK, KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
            assertSame(parent, session.view.registration)
        }
    }

    @Test fun `入力画面を閉じると登録窓と注釈を破棄する`() {
        withModal { service, modal, session, _, _ ->
            service.onFinishInputView(false)
            assertFalse(modal.isShowing)
            assertTrue(session.active)
            assertEquals(2, session.view.registration?.depth)
        }
    }

    @Test fun `保存中の戻るは登録を取り消さず入力先にも渡さない`() {
        withModal(nested = false) { service, _, session, _, _ ->
            session.handleTouch(BasicSkkAction.Text("本文", interpretCommands = false))
            assertTrue(session.saveRegistration(session.view.registration!!))
            val saving = session.view.registration!!
            assertTrue(saving.saving)
            ReflectionHelpers.setField(service, "renderedRegistration", saving)
            val down = KeyEvent(30, 30, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
            assertTrue(service.onKeyDown(KeyEvent.KEYCODE_BACK, down))
            assertTrue(service.onKeyUp(KeyEvent.KEYCODE_BACK, KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
            assertSame(saving, session.view.registration)
        }
    }

    @Test fun `古い登録窓の取消と登録は次の登録枠へ適用しない`() {
        withModal { service, modal, session, _, _ ->
            val old = session.view.registration!!
            ReflectionHelpers.callInstanceMethod<Unit>(service, "bindRegistrationCallbacks",
                ReflectionHelpers.ClassParameter.from(CandidateStatusView::class.java, modal.content),
                ReflectionHelpers.ClassParameter.from(se.haya.skk.core.RegistrationView::class.java, old))
            assertTrue(session.cancelRegistration(old))
            val parent = session.view.registration
            modal.content.registrationEditor.cancelButton.performClick()
            modal.content.registrationEditor.registerButton.performClick()
            assertSame(parent, session.view.registration)
            assertFalse(parent!!.saving)
        }
    }

    @Test fun `登録操作の無効状態を薄く表示し再度有効になると戻す`() {
        withModal { _, modal, session, strip, _ ->
            val editor = modal.content.registrationEditor
            val initial = RegistrationPresentation.from(session.view.registration!!, true)
            fun show(canRegister: Boolean, saving: Boolean) {
                modal.show(strip.parent as View, strip, CandidateStatusPresentation("",
                    registration = initial.copy(canRegister = canRegister, saving = saving)), "あ")
                assertEquals(if (canRegister && !saving) 1f else 0.38f, editor.registerButton.alpha)
                assertEquals(if (saving) 0.38f else 1f, editor.cancelButton.alpha)
            }
            show(canRegister = false, saving = false)
            show(canRegister = true, saving = false)
            show(canRegister = false, saving = true)
            show(canRegister = true, saving = false)
        }
    }

    @Test fun `実寸106dpでも長い見出しと親を越えて編集中の一行を表示する`() {
        withModal { _, modal, session, strip, _ ->
            val host = strip.parent as View
            val viewport = PhysicalInputPopup.viewport(host)
            val hostLocation = IntArray(2).also(host::getLocationOnScreen)
            val bottom = viewport.top + 106 - hostLocation[1]
            strip.layout(0, bottom - 96, 480, bottom)
            val initial = RegistrationPresentation.from(session.view.registration!!, true)
            for (text in listOf("", "長い本文\n".repeat(80))) {
                val value = initial.copy(titleReading = "長い読み".repeat(80), parentReading = "親の読み".repeat(80),
                    text = text, cursor = text.length)
                modal.show(host, strip, CandidateStatusPresentation("", registration = value), "あ")
                assertEquals(106, (modal.root.layoutParams as WindowManager.LayoutParams).height)
                modal.root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(106, View.MeasureSpec.EXACTLY))
                modal.root.layout(0, 0, 480, 106)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                val editor = modal.content.registrationEditor.editorView
                val visible = Rect()
                assertTrue("編集欄を画面へ表示します", editor.getGlobalVisibleRect(visible))
                assertTrue("少なくとも本文一行を表示します", visible.height() >= editor.lineHeight)
                val caret = editor.caretRect()
                val location = IntArray(2).also(editor::getLocationOnScreen)
                caret.offset(location[0], location[1])
                assertTrue("現在のカーソル行を表示します", visible.contains(caret))
                assertEquals(48, modal.footer.height)
                assertEquals(48, modal.content.registrationEditor.cancelButton.height)
                assertEquals(48, modal.content.registrationEditor.registerButton.height)
                assertTrue(modal.footer.getGlobalVisibleRect(Rect()))
            }
        }
    }

    @Test fun `左側の切り欠き安全領域を窓位置へ一度だけ反映して操作欄を画面へ収める`() {
        val safe = TouchRegistrationModal.safeViewport(Rect(0, 0, 2400, 1080),
            Rect(136, 0, 2400, 1080), Rect(136, 0, 0, 0))
        assertEquals(Rect(136, 0, 2400, 1080), safe)
        val box = TouchRegistrationModal.bounds(safe, 106)
        val params = TouchRegistrationModal.layoutParams(box, android.os.Binder())
        assertEquals(136, params.x)
        assertEquals(2264, params.width)
        assertEquals(2400, params.x + params.width)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, params.layoutInDisplayCutoutMode)
            assertEquals(0, params.fitInsetsTypes)
        }
        val modal = TouchRegistrationModal(RuntimeEnvironment.getApplication()) { }
        modal.configureAvailableHeight(box.height())
        modal.content.show(CandidateStatusPresentation("", registration = RegistrationPresentation(
            "よみ", null, "本文", 2, null, null, null, null, true, true, false)))
        modal.root.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY))
        modal.root.layout(0, 0, params.width, params.height)
        val footer = modal.footer
        val editor = modal.content.registrationEditor
        for (button in listOf(editor.cancelButton, editor.registerButton)) {
            val left = params.x + modal.panel.left + footer.left + button.left
            val right = params.x + modal.panel.left + footer.left + button.right
            assertTrue(left >= 136)
            assertTrue(right <= 2400)
            assertEquals(48, button.height)
        }
    }

    @Test fun `切り欠き情報がない場合も可視フレームの絶対座標を保つ`() {
        assertEquals(Rect(20, 24, 460, 776), TouchRegistrationModal.safeViewport(
            Rect(0, 0, 480, 800), Rect(20, 24, 460, 776), Rect()))
        assertEquals(Rect(0, 24, 480, 776), TouchRegistrationModal.safeViewport(
            Rect(0, 0, 480, 800), Rect(), Rect(0, 24, 0, 24)))
    }

    private fun withModal(nested: Boolean = true, block: (SkkInputMethodService, TouchRegistrationModal, EditorSession, View, android.widget.EditText) -> Unit) {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val host = FrameLayout(activity.get())
        val field = android.widget.EditText(activity.get())
        host.addView(field, FrameLayout.LayoutParams(480, 60))
        val strip = View(activity.get())
        host.addView(strip, FrameLayout.LayoutParams(480, 96).apply { topMargin = 320 })
        activity.get().setContentView(host)
        host.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 480, 800)
        field.requestFocus()
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        val connection = BaseInputConnection(field, true)
        val session = EditorSession(7, connection, false, true, 0, 0,
            registrationSaver = { _, _ -> })
        session.handle(BasicSkkAction.Text(if (nested) "Mitorokupamyu Goropazu " else "Mitorokupamyu "))
        val registration = session.view.registration!!
        ReflectionHelpers.setField(service, "session", session)
        ReflectionHelpers.setField(service, "renderedRegistration", registration)
        val modal = TouchRegistrationModal(service) { }
        ReflectionHelpers.setField(service, "touchRegistrationModal", modal)
        modal.show(host, strip, CandidateStatusPresentation("", registration = RegistrationPresentation.from(registration, true)), "あ")
        try { block(service, modal, session, strip, field) }
        finally {
            modal.dismiss()
            controller.destroy()
            activity.destroy()
        }
    }

}
