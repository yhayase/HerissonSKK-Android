package se.haya.skk.input

import android.view.MotionEvent
import android.view.Window

/** 文字面で始まった操作には、IMEのDialogによる座標制限前のイベントを渡します。 */
internal class KeyboardTouchWindowCallback private constructor(
    private val window: Window,
    private val original: Window.Callback,
    private val startsOnCharacterArea: (Float, Float) -> Boolean,
) : Window.Callback by original {
    private var ownsStream = false
    private var disposed = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            ownsStream = !disposed && startsOnCharacterArea(event.rawX, event.rawY)
        }
        if (!ownsStream) return original.dispatchTouchEvent(event)
        return try {
            // DecorViewから直接ビュー階層へ進めるため、このコールバックには再入しません。
            // falseでも元のコールバックへ渡すと、同じ操作が二重配送されます。
            window.superDispatchTouchEvent(event)
        } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                reset()
            }
        }
    }

    fun reset() {
        ownsStream = false
    }

    fun isInstalledOn(target: Window): Boolean = !disposed && window === target && target.callback === this

    fun dispose() {
        reset()
        disposed = true
        if (window.callback === this) window.callback = original
    }

    companion object {
        fun install(window: Window, startsOnCharacterArea: (Float, Float) -> Boolean): KeyboardTouchWindowCallback {
            return KeyboardTouchWindowCallback(window, checkNotNull(window.callback), startsOnCharacterArea).also {
                window.callback = it
            }
        }
    }
}
