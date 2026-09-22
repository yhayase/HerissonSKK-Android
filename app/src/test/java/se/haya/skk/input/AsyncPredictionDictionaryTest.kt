package se.haya.skk.input

import java.util.concurrent.Executor
import se.haya.skk.core.BasicSkkDictionary
import se.haya.skk.core.DictionaryCandidate
import se.haya.skk.core.DictionaryQuery
import se.haya.skk.core.PredictionCandidate
import se.haya.skk.core.PredictionHistoryTarget
import se.haya.skk.core.PredictionQuery
import se.haya.skk.core.PredictionSearchFailure
import se.haya.skk.core.PredictionSearchResult
import se.haya.skk.core.dictionary.DeferredDictionaryReadException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncPredictionDictionaryTest {
    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() { tasks.removeFirst().run() }
        fun runAll() { while (tasks.isNotEmpty()) runNext() }
    }

    @Test fun `連続する問い合わせを一つの作業へまとめ通常検索は委譲する`() {
        val background = QueueExecutor()
        val callbacks = QueueExecutor()
        var revision = AsyncPredictionRevision(7, 1, 3)
        val searched = mutableListOf<PredictionQuery>()
        val notified = mutableListOf<Pair<AsyncPredictionRevision, PredictionQuery>>()
        val delegate = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = listOf(DictionaryCandidate("通常"))
            override fun predict(query: PredictionQuery): PredictionSearchResult {
                searched += query
                return ready(query.prefix)
            }
        }
        val dictionary = AsyncPredictionDictionary(delegate, background, callbacks, { revision }) { version, query ->
            notified += version to query
        }

        assertPending(dictionary.predict(PredictionQuery("か")))
        revision = revision.copy(input = 2)
        assertPending(dictionary.predict(PredictionQuery("かな")))
        revision = revision.copy(input = 3)
        assertPending(dictionary.predict(PredictionQuery("かなう")))
        assertEquals(1, background.size)
        assertEquals("通常", dictionary.lookup(DictionaryQuery("通常")).single().text)

        background.runNext()
        assertEquals(listOf(PredictionQuery("かなう")), searched)
        assertEquals(1, callbacks.size)
        callbacks.runNext()
        assertEquals(listOf(revision to PredictionQuery("かなう")), notified)
        assertTrue(dictionary.predict(PredictionQuery("かなう")) is PredictionSearchResult.Ready)
    }

    @Test fun `完了空は遅延読込の完了後だけ公開する`() {
        val background = QueueExecutor()
        val callbacks = QueueExecutor()
        val revision = AsyncPredictionRevision(1, 1, 1)
        var loaded = false
        var notifications = 0
        val delegate = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun predict(query: PredictionQuery): PredictionSearchResult {
                if (!loaded) throw DeferredDictionaryReadException { loaded = true }
                return PredictionSearchResult.ConfirmedEmpty
            }
        }
        val dictionary = AsyncPredictionDictionary(delegate, background, callbacks, { revision }) { _, _ ->
            notifications++
        }

        assertPending(dictionary.predict(PredictionQuery("なし")))
        assertEquals(0, notifications)
        background.runNext()
        assertEquals(PredictionSearchResult.ConfirmedEmpty, dictionary.predict(PredictionQuery("なし")))
        assertEquals(0, notifications)
        callbacks.runNext()
        assertEquals(1, notifications)
    }

    @Test fun `一つの入力に属する複数の読みを全て検索してから順位と重複を解決する`() {
        val background = QueueExecutor()
        val callbacks = QueueExecutor()
        val revision = AsyncPredictionRevision(1, 1, 1)
        val searched = mutableListOf<String>()
        val delegate = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun predict(query: PredictionQuery): PredictionSearchResult {
                searched += query.prefix
                return ready(if (query.prefix == "ん") "共通" else query.prefix)
            }
        }
        val dictionary = AsyncPredictionDictionary(delegate, background, callbacks, { revision }) { _, _ -> }
        val query = PredictionQuery("な", alternativePrefixes = listOf("に", "ん"))

        assertPending(dictionary.predict(query))
        background.runNext()
        val result = dictionary.predict(query) as PredictionSearchResult.Ready
        assertEquals(listOf("な", "に", "ん"), searched)
        assertEquals(listOf("な", "に", "共通"), result.items.map { it.committedText })
    }

    @Test fun `短い接頭辞で検索できる分岐は重ねて予算を分割しない`() {
        val background = QueueExecutor()
        val callbacks = QueueExecutor()
        val revision = AsyncPredictionRevision(1, 1, 1)
        val searched = mutableListOf<String>()
        val delegate = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun predict(query: PredictionQuery): PredictionSearchResult {
                searched += query.prefix
                if (query.prefix == "てに" && query.workLimit < 2) {
                    return PredictionSearchResult.Indeterminate(PredictionSearchFailure.WORK_LIMIT)
                }
                return PredictionSearchResult.ConfirmedEmpty
            }
        }
        val dictionary = AsyncPredictionDictionary(delegate, background, callbacks, { revision }) { _, _ -> }
        val query = PredictionQuery(
            "てに", alternativePrefixes = listOf("てにゃ", "てにゅ", "てん", "てn"), workLimit = 6,
        )

        assertPending(dictionary.predict(query))
        background.runNext()

        assertEquals(listOf("てに", "てん", "てn"), searched)
        assertEquals(PredictionSearchResult.ConfirmedEmpty, dictionary.predict(query))
    }

    @Test fun `分岐を統合してから最新履歴の代表と全体順位を選び作業予算を分配する`() {
        val background = QueueExecutor()
        val callbacks = QueueExecutor()
        val revision = AsyncPredictionRevision(1, 1, 1)
        val budgets = mutableListOf<Pair<Int, Int>>()
        fun item(reading: String, text: String, sequence: Long) = PredictionCandidate(
            DictionaryCandidate(text), text,
            PredictionHistoryTarget(reading, text, null, text),
            lastUsedSequence = sequence,
        )
        val delegate = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun predict(query: PredictionQuery): PredictionSearchResult {
                budgets += query.workLimit to query.totalResultCharsLimit
                return PredictionSearchResult.Ready(if (query.prefix == "か") listOf(
                    item("か", "共通", 1), item("か", "別", 3),
                ) else listOf(item("き", "共通", 2)), false)
            }
        }
        val dictionary = AsyncPredictionDictionary(delegate, background, callbacks, { revision }) { _, _ -> }
        val query = PredictionQuery(
            "か", alternativePrefixes = listOf("き"), workLimit = 10, totalResultCharsLimit = 100,
        )

        assertPending(dictionary.predict(query))
        background.runNext()
        val result = dictionary.predict(query) as PredictionSearchResult.Ready
        assertEquals(listOf(5 to 50, 5 to 50), budgets)
        assertEquals(listOf("別", "共通"), result.items.map { it.committedText })
        assertEquals("き", result.items.last().historyTarget.readingKey)
    }

    @Test fun `接頭辞数より小さい作業予算は下位検索へ過剰配分しない`() {
        val background = QueueExecutor()
        val callbacks = QueueExecutor()
        val revision = AsyncPredictionRevision(1, 1, 1)
        var delegated = 0
        val delegate = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun predict(query: PredictionQuery): PredictionSearchResult {
                delegated += query.workLimit
                return PredictionSearchResult.ConfirmedEmpty
            }
        }
        val dictionary = AsyncPredictionDictionary(delegate, background, callbacks, { revision }) { _, _ -> }
        val query = PredictionQuery("か", alternativePrefixes = listOf("き"), workLimit = 1)

        assertPending(dictionary.predict(query))
        background.runNext()
        assertEquals(0, delegated)
        assertEquals(
            PredictionSearchResult.Indeterminate(PredictionSearchFailure.WORK_LIMIT),
            dictionary.predict(query),
        )
    }

    @Test fun `古い入力版の通知と古い辞書版の結果を採用しない`() {
        val background = QueueExecutor()
        val callbacks = QueueExecutor()
        var revision = AsyncPredictionRevision(1, 1, 1)
        var reads = 0
        var notifications = 0
        val delegate = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun predict(query: PredictionQuery): PredictionSearchResult {
                reads++
                return ready(query.prefix)
            }
        }
        val dictionary = AsyncPredictionDictionary(delegate, background, callbacks, { revision }) { _, _ ->
            notifications++
        }

        val first = PredictionQuery("ふるい")
        assertPending(dictionary.predict(first))
        background.runNext()
        revision = revision.copy(input = 2)
        callbacks.runAll()
        assertEquals(0, notifications)
        assertTrue(dictionary.predict(first) is PredictionSearchResult.Ready)

        val changed = PredictionQuery("じしょ")
        revision = revision.copy(input = 3)
        assertPending(dictionary.predict(changed))
        revision = revision.copy(dictionary = 2)
        background.runNext()
        assertEquals(0, callbacks.size)
        assertPending(dictionary.predict(changed))
        background.runNext()
        callbacks.runNext()
        assertEquals(1, notifications)
        assertEquals(3, reads)
    }

    @Test fun `同じ問い合わせを待つ最新入力版へ完了を通知する`() {
        val background = QueueExecutor()
        val callbacks = QueueExecutor()
        var revision = AsyncPredictionRevision(1, 1, 1)
        val notified = mutableListOf<AsyncPredictionRevision>()
        val delegate = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun predict(query: PredictionQuery) = ready(query.prefix)
        }
        val dictionary = AsyncPredictionDictionary(delegate, background, callbacks, { revision }) { version, _ ->
            notified += version
        }
        val query = PredictionQuery("おなじ")

        assertPending(dictionary.predict(query))
        revision = revision.copy(input = 2)
        assertPending(dictionary.predict(query))
        background.runNext()
        callbacks.runNext()

        assertEquals(listOf(revision), notified)
        assertTrue(dictionary.predict(query) is PredictionSearchResult.Ready)
    }

    @Test fun `終了後の作業と通知は無効になりキャッシュは有限に保つ`() {
        val background = QueueExecutor()
        val callbacks = QueueExecutor()
        var revision = AsyncPredictionRevision(1, 0, 1)
        var reads = 0
        val delegate = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun predict(query: PredictionQuery): PredictionSearchResult {
                reads++
                return ready(query.prefix + "長".repeat(1_000))
            }
        }
        val dictionary = AsyncPredictionDictionary(delegate, background, callbacks, { revision }) { _, _ -> }

        repeat(20) { index ->
            revision = revision.copy(input = index.toLong())
            assertPending(dictionary.predict(PredictionQuery("よみ$index")))
            background.runNext()
            callbacks.runAll()
        }
        assertTrue(dictionary.cacheStats.entries <= 8)
        assertTrue(dictionary.cacheStats.retainedChars <= 512L * 1024)
        assertEquals(20, reads)

        revision = revision.copy(input = 21)
        assertPending(dictionary.predict(PredictionQuery("終了")))
        dictionary.close()
        background.runAll()
        callbacks.runAll()
        assertEquals(20, reads)
        assertEquals(AsyncPredictionCacheStats(0, 0), dictionary.cacheStats)
        assertEquals(
            PredictionSearchResult.Indeterminate(PredictionSearchFailure.UNAVAILABLE),
            dictionary.predict(PredictionQuery("終了")),
        )
    }

    @Test fun `巨大結果と下位辞書の保留結果は一回で終端して再読込を繰り返さない`() {
        for ((returned, expected) in listOf(
            ready("巨".repeat(600_000)) to PredictionSearchFailure.RESULT_LIMIT,
            PredictionSearchResult.Indeterminate(PredictionSearchFailure.PENDING) to
                PredictionSearchFailure.UNAVAILABLE,
        )) {
            val background = QueueExecutor()
            val callbacks = QueueExecutor()
            val revision = AsyncPredictionRevision(1, 1, 1)
            var reads = 0
            val delegate = object : BasicSkkDictionary {
                override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
                override fun predict(query: PredictionQuery): PredictionSearchResult {
                    reads++
                    return returned
                }
            }
            val dictionary = AsyncPredictionDictionary(delegate, background, callbacks, { revision }) { _, _ -> }

            assertPending(dictionary.predict(PredictionQuery("きょ")))
            background.runNext()
            assertEquals(0, background.size)
            assertEquals(1, reads)
            assertEquals(
                PredictionSearchResult.Indeterminate(expected),
                dictionary.predict(PredictionQuery("きょ")),
            )
            callbacks.runAll()
            assertEquals(1, reads)
        }
    }

    private fun ready(text: String): PredictionSearchResult.Ready {
        val target = PredictionHistoryTarget(text, text, null, text)
        return PredictionSearchResult.Ready(listOf(
            PredictionCandidate(DictionaryCandidate(text), text, target),
        ), false)
    }

    private fun assertPending(result: PredictionSearchResult) {
        assertEquals(
            PredictionSearchResult.Indeterminate(PredictionSearchFailure.PENDING),
            result,
        )
    }
}
