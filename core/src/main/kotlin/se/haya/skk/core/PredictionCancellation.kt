package se.haya.skk.core

/** 中止した予測を検索失敗や確定空として扱わないための例外です。 */
class PredictionCancelledException : RuntimeException("予測検索を中止しました")

/** 実行スレッドを割り込まず、予測処理と下位 I/O に中止を伝えます。 */
class PredictionCancellation {
    @Volatile private var cancelled = false
    private val callbacks = linkedSetOf<() -> Unit>()

    fun cancel() {
        val pending = synchronized(this) {
            if (cancelled) return
            cancelled = true
            callbacks.toList().also { callbacks.clear() }
        }
        // 下位 I/O のロックを取得する処理は、このスコープのロック外で呼びます。
        pending.forEach { callback -> runCatching(callback) }
    }

    fun throwIfCancelled() {
        if (cancelled) throw PredictionCancelledException()
    }

    fun onCancel(callback: () -> Unit): AutoCloseable {
        val callNow = synchronized(this) {
            if (cancelled) true else { callbacks.add(callback); false }
        }
        if (callNow) callback()
        return AutoCloseable { synchronized(this) { callbacks.remove(callback) } }
    }

    fun <T> run(block: () -> T): T {
        val previous = local.get()
        local.set(this)
        return try {
            throwIfCancelled()
            block().also { throwIfCancelled() }
        } finally {
            if (previous == null) local.remove() else local.set(previous)
        }
    }

    companion object {
        private val local = ThreadLocal<PredictionCancellation>()
        val current: PredictionCancellation? get() = local.get()
        fun check() { current?.throwIfCancelled() }
    }
}
