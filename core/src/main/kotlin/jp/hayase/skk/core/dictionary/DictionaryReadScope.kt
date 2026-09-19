package jp.hayase.skk.core.dictionary

/** 一操作の再試行中だけ検索結果を保持し、常設キャッシュの追い出しから保護します。 */
class DictionaryReadScope : AutoCloseable {
    private val values = mutableMapOf<Any, Any>()
    private var closed = false

    @Synchronized fun get(key: Any): Any? = values[key]

    @Synchronized fun put(key: Any, value: Any) {
        if (!closed) values[key] = value
    }

    fun <T> run(block: () -> T): T {
        val previous = active.get()
        active.set(this)
        return try { block() } finally { active.set(previous) }
    }

    @Synchronized override fun close() {
        closed = true
        values.clear()
    }

    companion object {
        private val active = ThreadLocal<DictionaryReadScope?>()
        val current: DictionaryReadScope? get() = active.get()
    }
}
