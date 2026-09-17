package jp.hayase.skk.input

import android.view.KeyEvent

/** 接頭キーの後、次の修飾キー以外の物理キーを一回だけ入力先へ渡します。 */
class QuoteNextKey {
    private data class Press(val device: Int, val code: Int, val time: Long)
    private var pending = false
    private var prefix: Press? = null
    private val quoted = mutableSetOf<Press>()

    fun begin(event: KeyEvent) {
        pending = true
        prefix = Press(event.deviceId, event.keyCode, event.downTime)
    }
    fun reset() { pending = false; prefix = null; quoted.clear() }

    fun consumeDown(event: KeyEvent): Boolean {
        if (!pending || KeyEvent.isModifierKey(event.keyCode)) return false
        val press = Press(event.deviceId, event.keyCode, event.downTime)
        if (press == prefix) return false
        pending = false
        prefix = null
        quoted += press
        return true
    }

    fun quotedDown(event: KeyEvent): Boolean =
        Press(event.deviceId, event.keyCode, event.downTime) in quoted

    fun quotedUp(event: KeyEvent): Boolean =
        quoted.remove(Press(event.deviceId, event.keyCode, event.downTime))
}
