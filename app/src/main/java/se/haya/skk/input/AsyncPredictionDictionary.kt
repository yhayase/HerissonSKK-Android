package se.haya.skk.input

import java.io.Closeable
import java.util.LinkedHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import se.haya.skk.core.BasicSkkDictionary
import se.haya.skk.core.PredictionCancellation
import se.haya.skk.core.PredictionCancelledException
import se.haya.skk.core.PredictionQuery
import se.haya.skk.core.PredictionSearchFailure
import se.haya.skk.core.PredictionSearchResult
import se.haya.skk.core.rankPredictionCandidates
import se.haya.skk.core.predictionCandidateRetainedChars
import se.haya.skk.core.dictionary.DeferredDictionaryReadException
import se.haya.skk.core.dictionary.DictionaryReadScope

/** 非同期予測要求が属する入力欄、入力状態、辞書公開版です。 */
internal data class AsyncPredictionRevision(
    val session: Long,
    val input: Long,
    val dictionary: Long,
)

internal data class AsyncPredictionCacheStats(
    val entries: Int,
    val retainedChars: Long,
)

/**
 * 通常の辞書操作を変更せず、前方一致予測だけを単一のバックグラウンド処理へ移します。
 *
 * 読込中は確定空と区別できる PENDING を返します。完了通知は現在も同じ入力版と問い合わせを
 * 要求している場合だけ配送し、呼出側が文字入力を再実行せず表示状態だけを更新します。
 */
internal class AsyncPredictionDictionary(
    private val delegate: BasicSkkDictionary,
    private val backgroundExecutor: Executor,
    private val callbackExecutor: Executor,
    private val currentRevision: () -> AsyncPredictionRevision,
    private val onReady: (AsyncPredictionRevision, PredictionQuery) -> Unit,
) : BasicSkkDictionary by delegate, Closeable {
    private data class CacheKey(val dictionaryRevision: Long, val query: PredictionQuery)
    private data class Demand(val revision: AsyncPredictionRevision, val query: PredictionQuery) {
        val key = CacheKey(revision.dictionary, query)
    }
    private data class Cached(val result: PredictionSearchResult, val chars: Long)

    private val cache = LinkedHashMap<CacheKey, Cached>(16, 0.75f, true)
    private var retainedChars = 0L
    private var latestDemand: Demand? = null
    private data class Work(val demand: Demand, val cancellation: PredictionCancellation)
    private var running: Work? = null
    private var workerRunning = false
    private var active = true

    internal val cacheStats: AsyncPredictionCacheStats
        @Synchronized get() = AsyncPredictionCacheStats(cache.size, retainedChars)

    override fun predict(query: PredictionQuery): PredictionSearchResult {
        val demand = Demand(currentRevision(), query)
        var startWorker = false
        val obsolete = synchronized(this) {
            if (!active) return unavailable()
            latestDemand = demand
            running?.takeIf { it.demand != demand }?.cancellation
        }
        obsolete?.cancel()
        synchronized(this) {
            if (!active) return unavailable()
            cache[demand.key]?.let { return it.result }
            if (!workerRunning) {
                workerRunning = true
                startWorker = true
            }
        }
        if (startWorker) {
            try {
                backgroundExecutor.execute(::drain)
            } catch (_: RejectedExecutionException) {
                synchronized(this) {
                    workerRunning = false
                    if (active && latestDemand == demand) put(demand.key, unavailable())
                }
                return unavailable()
            }
        }
        // 同期実行する試験用Executorでは、executeから戻る前に読込が完了する場合があります。
        return synchronized(this) {
            if (!active) unavailable()
            else cache[demand.key]?.result ?: pending()
        }
    }

    /** 入力の進行だけで予測が不要になった場合も、進行中の I/O を中止します。 */
    fun invalidateRevision() {
        val obsolete = synchronized(this) {
            latestDemand = null
            running?.cancellation
        }
        obsolete?.cancel()
    }

    private fun drain() {
        while (true) {
            val work = synchronized(this) {
                val latest = latestDemand
                if (!active || latest == null || latest.revision != currentRevision() ||
                    cache.containsKey(latest.key)) {
                    workerRunning = false
                    return
                }
                Work(latest, PredictionCancellation()).also { running = it }
            }
            val result = try {
                work.cancellation.run { load(work.demand.query) }
            } catch (_: PredictionCancelledException) {
                null
            }
            var notify: Demand? = null
            val continueRunning = synchronized(this) {
                val current = currentRevision()
                running = null
                val latest = latestDemand
                if (active && result != null && latest == work.demand && current == latest.revision) {
                    put(latest.key, result)
                    notify = latest
                }
                val needsAnother = active && latest != null && current == latest.revision &&
                    !cache.containsKey(latest.key) && (result == null || latest != work.demand)
                workerRunning = needsAnother
                needsAnother
            }
            notify?.let(::postReady)
            if (!continueRunning) return
        }
    }

    private fun load(query: PredictionQuery): PredictionSearchResult {
        val scope = DictionaryReadScope()
        return try {
            repeat(MAX_DEFERRED_RETRIES) {
                PredictionCancellation.check()
                try {
                    val result = scope.run { loadAllPrefixes(query) }
                    return if (result == pending()) unavailable() else result
                } catch (pending: DeferredDictionaryReadException) {
                    PredictionCancellation.check()
                    pending.load()
                    PredictionCancellation.check()
                }
            }
            unavailable()
        } catch (cancelled: PredictionCancelledException) {
            throw cancelled
        } catch (_: RuntimeException) {
            PredictionCancellation.check()
            unavailable()
        } finally {
            scope.close()
        }
    }

    private fun loadAllPrefixes(query: PredictionQuery): PredictionSearchResult {
        PredictionCancellation.check()
        val distinctPrefixes = query.prefixes.distinct()
        if (distinctPrefixes.size > PredictionQuery.MAX_PREFIXES) {
            return PredictionSearchResult.Indeterminate(PredictionSearchFailure.INVALID_INPUT)
        }
        val prefixes = distinctPrefixes.filter { candidate ->
            distinctPrefixes.none { prefix -> prefix != candidate && candidate.startsWith(prefix) }
        }
        val values = mutableListOf<se.haya.skk.core.PredictionCandidate>()
        var hasMore = false
        var retainedChars = 0L
        if (query.workLimit < prefixes.size) {
            return PredictionSearchResult.Indeterminate(PredictionSearchFailure.WORK_LIMIT)
        }
        val perPrefixWork = (query.workLimit / prefixes.size.coerceAtLeast(1)).coerceAtLeast(1)
        val perPrefixChars = (query.totalResultCharsLimit / prefixes.size.coerceAtLeast(1)).coerceAtLeast(1)
        for (prefix in prefixes) {
            PredictionCancellation.check()
            when (val result = delegate.predict(query.copy(
                prefix = prefix,
                alternativePrefixes = emptyList(),
                workLimit = perPrefixWork,
                totalResultCharsLimit = perPrefixChars,
            ))) {
                is PredictionSearchResult.Ready -> {
                    result.items.forEach { item ->
                        retainedChars += predictionCandidateRetainedChars(item)
                    }
                    if (retainedChars > query.totalResultCharsLimit) {
                        return PredictionSearchResult.Indeterminate(PredictionSearchFailure.RESULT_LIMIT)
                    }
                    values += result.items
                    hasMore = hasMore || result.hasMore
                }
                PredictionSearchResult.ConfirmedEmpty -> Unit
                is PredictionSearchResult.Indeterminate -> return result
            }
        }
        PredictionCancellation.check()
        return when (val ranked = rankPredictionCandidates(values, query.limit)) {
            is PredictionSearchResult.Ready -> ranked.copy(hasMore = ranked.hasMore || hasMore)
            else -> ranked
        }
    }

    private fun postReady(demand: Demand) {
        try {
            callbackExecutor.execute {
                val current = currentRevision()
                val valid = synchronized(this) {
                    active && latestDemand == demand && current == demand.revision &&
                        cache.containsKey(demand.key)
                }
                if (valid) onReady(demand.revision, demand.query)
            }
        } catch (_: RejectedExecutionException) {
            // 終了中の配送拒否は、次の入力または辞書更新による再照会へ委ねます。
        }
    }

    @Synchronized
    private fun put(key: CacheKey, result: PredictionSearchResult) {
        val bounded = if (retainedChars(key, result) > MAX_RETAINED_CHARS) {
            PredictionSearchResult.Indeterminate(PredictionSearchFailure.RESULT_LIMIT)
        } else result
        val chars = retainedChars(key, bounded)
        cache.remove(key)?.let { retainedChars -= it.chars }
        while (cache.isNotEmpty() &&
            (cache.size >= MAX_ENTRIES || retainedChars + chars > MAX_RETAINED_CHARS)) {
            val oldest = cache.entries.iterator().next()
            retainedChars -= oldest.value.chars
            cache.remove(oldest.key)
        }
        cache[key] = Cached(bounded, chars)
        retainedChars += chars
    }

    private fun retainedChars(key: CacheKey, result: PredictionSearchResult): Long {
        var chars = key.query.prefixes.sumOf { it.length.toLong() }
        if (result is PredictionSearchResult.Ready) result.items.forEach { item ->
            chars += predictionCandidateRetainedChars(item)
        }
        return chars
    }

    override fun close() {
        val obsolete = synchronized(this) {
            active = false
            latestDemand = null
            cache.clear()
            retainedChars = 0
            running?.cancellation
        }
        obsolete?.cancel()
    }

    private fun pending() = PredictionSearchResult.Indeterminate(PredictionSearchFailure.PENDING)
    private fun unavailable() = PredictionSearchResult.Indeterminate(PredictionSearchFailure.UNAVAILABLE)

    private companion object {
        const val MAX_DEFERRED_RETRIES = 256
        const val MAX_ENTRIES = 8
        const val MAX_RETAINED_CHARS = 512L * 1024
    }
}
