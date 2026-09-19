package jp.hayase.skk.dictionary

import java.util.LinkedHashMap
import jp.hayase.skk.core.BasicSkkDictionary
import jp.hayase.skk.core.CompletionQuery
import jp.hayase.skk.core.DictionaryCandidate
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.dictionary.DeferredDictionaryReadException
import jp.hayase.skk.core.dictionary.DictionaryReadScope
import jp.hayase.skk.core.dictionary.DictionaryUnavailableException
import jp.hayase.skk.core.dictionary.DictionaryUnavailableReason
import jp.hayase.skk.core.dictionary.SkkDictionarySource

/** 空検索も件数へ含め、直近の検索結果だけを保持する上限付きキャッシュです。 */
internal data class DictionaryCacheStats(val entries: Int, val candidateRecords: Int, val retainedChars: Long) {
    companion object {
        const val MAX_ENTRIES = 128
        const val MAX_CANDIDATE_RECORDS = 4_096
        const val MAX_RETAINED_CHARS = 524_288L
    }
}

/** SQLite を正本とし、主スレッドのキャッシュミスは読み込み要求として返します。 */
internal class SQLiteSkkDictionary(
    private val repository: SQLiteDictionaryRepository,
    private val metadata: DictionaryMetadata,
    private val fallbackSystems: List<SkkDictionarySource>,
) : BasicSkkDictionary {
    private data class Key(val reading: String? = null, val completion: CompletionQuery? = null)
    private data class ScopedKey(val owner: Any, val key: Key)
    private val scopeOwner = Any()
    private data class Cached(val value: Any?, val records: Int, val chars: Long, val error: RuntimeException? = null)
    private val cache = LinkedHashMap<Key, Cached>(16, 0.75f, true)
    private var records = 0
    private var chars = 0L

    val stats: DictionaryCacheStats
        @Synchronized get() = DictionaryCacheStats(cache.size, records, chars)

    override fun lookup(query: DictionaryQuery): List<DictionaryCandidate> {
        val snapshot = get(Key(reading = query.readingKey)) {
            val value = repository.lookup(query.readingKey, metadata.revision)
            var count = 0
            var size = query.readingKey.length.toLong()
            (listOfNotNull(value.personal) + value.systems).forEach { source ->
                size += source.id.length
                source.entriesForBackup().forEach { entry ->
                    size += entry.key.length
                    entry.candidates.forEach { candidate ->
                        count++
                        size += candidate.text.length + (candidate.annotation?.length ?: 0) +
                            (candidate.okuriCondition?.length ?: 0)
                    }
                }
            }
            value.suppressions.forEach { suppression ->
                count++
                size += suppression.key.let { it.sourceId.length + it.entryKey.length + it.templateText.length +
                    (it.okuriCondition?.length ?: 0) }
            }
            Cached(value.asComposite(fallbackSystems), count, size)
        } as BasicSkkDictionary
        return snapshot.lookup(query)
    }

    @Suppress("UNCHECKED_CAST")
    override fun complete(query: CompletionQuery): List<String> = get(Key(completion = query)) {
        val result = repository.complete(query, metadata.revision, fallbackSystems)
        Cached(result, result.size, query.prefix.length.toLong() + result.sumOf { it.length.toLong() })
    } as List<String>

    private fun get(key: Key, read: () -> Cached): Any {
        if (keyChars(key) > DictionaryCacheStats.MAX_RETAINED_CHARS) {
            throw DictionaryUnavailableException(DictionaryUnavailableReason.FAILED)
        }
        val scope = DictionaryReadScope.current
        val scopedKey = ScopedKey(scopeOwner, key)
        (scope?.get(scopedKey) as? Cached)?.let { return unpack(it) }
        synchronized(this) { cache[key] }?.let {
            scope?.put(scopedKey, it)
            return unpack(it)
        }
        throw DeferredDictionaryReadException {
            // I/O 中にはキャッシュや管理器のモニターを保持しません。
            val loaded = try { read() } catch (failure: RuntimeException) {
                Cached(null, 0, keyChars(key), failure)
            }
            // 再実行中の #4 などは同じ操作の読込結果を固定します。操作終了時に必ず解放します。
            // 大きな一見出しも検索できますが、長寿命の LRU へは入れません。
            scope?.put(scopedKey, loaded)
            synchronized(this) {
                if (loaded.records > DictionaryCacheStats.MAX_CANDIDATE_RECORDS ||
                    loaded.chars > DictionaryCacheStats.MAX_RETAINED_CHARS) return@synchronized
                cache.remove(key)?.let { records -= it.records; chars -= it.chars }
                while (cache.isNotEmpty() && (cache.size >= DictionaryCacheStats.MAX_ENTRIES ||
                    records + loaded.records > DictionaryCacheStats.MAX_CANDIDATE_RECORDS ||
                    chars + loaded.chars > DictionaryCacheStats.MAX_RETAINED_CHARS)) {
                    val oldest = cache.entries.iterator().next()
                    records -= oldest.value.records
                    chars -= oldest.value.chars
                    cache.remove(oldest.key)
                }
                cache[key] = loaded
                records += loaded.records
                chars += loaded.chars
            }
        }
    }

    private fun keyChars(key: Key): Long = (key.reading?.length ?: key.completion!!.prefix.length).toLong()

    private fun unpack(value: Cached): Any {
        value.error?.let { failure ->
            if (failure is jp.hayase.skk.core.CompletionException || failure is DictionaryUnavailableException) throw failure
            throw DictionaryUnavailableException(DictionaryUnavailableReason.FAILED)
        }
        return checkNotNull(value.value)
    }
}
