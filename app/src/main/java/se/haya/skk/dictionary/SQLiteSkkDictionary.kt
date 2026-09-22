package se.haya.skk.dictionary

import java.util.LinkedHashMap
import se.haya.skk.core.BasicSkkDictionary
import se.haya.skk.core.CompletionQuery
import se.haya.skk.core.DictionaryCandidate
import se.haya.skk.core.DictionaryQuery
import se.haya.skk.core.PredictionHistoryTarget
import se.haya.skk.core.PredictionQuery
import se.haya.skk.core.PredictionSearchResult
import se.haya.skk.core.dictionary.DeferredDictionaryReadException
import se.haya.skk.core.dictionary.DictionaryReadScope
import se.haya.skk.core.dictionary.DictionaryUnavailableException
import se.haya.skk.core.dictionary.DictionaryUnavailableReason
import se.haya.skk.core.dictionary.SkkDictionarySource

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
    private data class Key(
        val reading: String? = null,
        val completion: CompletionQuery? = null,
        val prediction: PredictionQuery? = null,
        val usage: PredictionHistoryTarget? = null,
    )
    private data class ScopedKey(val owner: Any, val key: Key)
    private data class UsageValue(val sequence: Long?)
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

    override fun predict(query: PredictionQuery): PredictionSearchResult = get(Key(prediction = query)) {
        val result = repository.predict(query, metadata.revision, fallbackSystems)
        val items = (result as? PredictionSearchResult.Ready)?.items.orEmpty()
        Cached(result, items.size, query.prefix.length.toLong() + items.sumOf {
            it.committedText.length.toLong() + (it.candidate.annotation?.length ?: 0)
        })
    } as PredictionSearchResult

    override fun predictionUsage(target: PredictionHistoryTarget): Long? = get(Key(usage = target)) {
        Cached(UsageValue(repository.predictionUsage(target, metadata.revision)), 1,
            (target.readingKey.length + target.templateText.length + target.committedText.length).toLong())
    }.let { (it as UsageValue).sequence }

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

    private fun keyChars(key: Key): Long = when {
        key.reading != null -> key.reading.length.toLong()
        key.completion != null -> key.completion.prefix.length.toLong()
        key.prediction != null -> key.prediction.prefix.length.toLong()
        else -> key.usage!!.let { (it.readingKey.length + it.templateText.length + it.committedText.length).toLong() }
    }

    private fun unpack(value: Cached): Any {
        value.error?.let { failure ->
            if (failure is se.haya.skk.core.CompletionException || failure is DictionaryUnavailableException) throw failure
            throw DictionaryUnavailableException(DictionaryUnavailableReason.FAILED)
        }
        return checkNotNull(value.value)
    }
}
