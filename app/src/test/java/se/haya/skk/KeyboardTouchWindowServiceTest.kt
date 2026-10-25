package se.haya.skk

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import se.haya.skk.input.KeyboardTouchWindowCallback

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class KeyboardTouchWindowServiceTest {
    @Test fun `入力ビューの再作成ではコールバックを重ねず破棄で元に戻す`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        val window = service.window.window!!
        val original = window.callback
        service.onCreateInputView()
        val callback = window.callback as KeyboardTouchWindowCallback
        ReflectionHelpers.setField(callback, "ownsStream", true)
        service.onCreateInputView()
        assertSame(callback, window.callback)
        assertFalse(ReflectionHelpers.getField(callback, "ownsStream"))
        controller.destroy()
        assertSame(original, window.callback)
    }

    @Test fun `入力終了と非表示で操作の所有を解除する`() {
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        try {
            service.onCreateInputView()
            val callback = service.window.window!!.callback as KeyboardTouchWindowCallback
            val boundaries = listOf<() -> Unit>(
                { service.onFinishInput() }, { service.onUnbindInput() },
                { service.onWindowHidden() }, { service.onFinishInputView(false) },
                { service.onStartInput(null, false) },
            )
            boundaries.forEach { boundary ->
                ReflectionHelpers.setField(callback, "ownsStream", true)
                boundary()
                assertFalse(ReflectionHelpers.getField(callback, "ownsStream"))
                assertSame(callback, service.window.window!!.callback)
            }
        } finally { controller.destroy() }
    }
}
