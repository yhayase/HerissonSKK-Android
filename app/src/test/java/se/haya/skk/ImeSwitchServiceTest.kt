package se.haya.skk

import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import se.haya.skk.input.EditorSession
import se.haya.skk.input.QuoteNextKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class ImeSwitchServiceTest {
    @Test fun `CtrlSpaceはセッション未作成中と失敗後もIME切替として消費する`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        try {
            val withoutSession = ctrlSpace(10)
            assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE, withoutSession))
            assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SPACE,
                KeyEvent.changeAction(withoutSession, KeyEvent.ACTION_UP)))

            val session = EditorSession(1,
                BaseInputConnection(View(RuntimeEnvironment.getApplication()), true),
                false, false, 0, 0)
            ReflectionHelpers.setField(session, "failed", true)
            ReflectionHelpers.setField(service, "session", session)
            val failedSession = ctrlSpace(20)
            assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SPACE, failedSession))
            assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SPACE,
                KeyEvent.changeAction(failedSession, KeyEvent.ACTION_UP)))
        } finally {
            controller.destroy()
        }
    }

    @Test fun `引用待機中のCtrlSpaceはIME切替として消費しない`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        try {
            val quote = ReflectionHelpers.getField<QuoteNextKey>(service, "quoteNext")
            quote.begin(ctrlKey(30, KeyEvent.KEYCODE_Q))

            assertFalse(service.onKeyDown(KeyEvent.KEYCODE_SPACE, ctrlSpace(40)))
        } finally {
            controller.destroy()
        }
    }

    private fun ctrlSpace(time: Long) = ctrlKey(time, KeyEvent.KEYCODE_SPACE)

    private fun ctrlKey(time: Long, code: Int) = KeyEvent(
        time, time, KeyEvent.ACTION_DOWN, code, 0, KeyEvent.META_CTRL_ON,
        KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
    )
}
