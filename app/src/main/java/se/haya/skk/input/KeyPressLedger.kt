package se.haya.skk.input

/** 押下時の処理結果をキーアップまで保持します。セッションをまたぐ長押しも再入力しません。 */
class KeyPressLedger {
    private data class Key(val device: Int, val code: Int)
    private data class Press(val time: Long, val generation: Long, val handled: Boolean)
    private val presses = mutableMapOf<Key, Press>()

    fun down(device: Int, code: Int, time: Long, generation: Long, repeat: Int,
             repeatable: Boolean, handle: () -> Boolean): Boolean {
        val key = Key(device, code)
        val previous = presses[key]
        if (repeat > 0 && previous?.time == time) {
            if (previous.generation != generation) return true
            if (previous.handled && previous.generation == generation && repeatable) handle()
            return previous.handled
        }
        val handled = handle()
        presses[key] = Press(time, generation, handled)
        return handled
    }

    fun up(device: Int, code: Int, time: Long, generation: Long? = null): Boolean {
        val key = Key(device, code)
        val press = presses[key] ?: return false
        if (press.time != time) return false
        presses.remove(key)
        return press.handled || generation != null && press.generation != generation
    }

    fun removeDevice(device: Int) { presses.keys.removeAll { it.device == device } }
}
