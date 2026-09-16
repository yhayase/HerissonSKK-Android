package jp.hayase.skk.dictionary

import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.os.StrictMode
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import jp.hayase.skk.core.CompletionQuery
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.dictionary.SkkDictionaryCandidate
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.core.dictionary.SkkDictionaryDocument
import jp.hayase.skk.core.dictionary.SkkDictionaryEncoding
import jp.hayase.skk.core.dictionary.SkkDictionaryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 実 SQLite と [DictionaryManager] の公開済み不変スナップショットを測定します。
 *
 * 実 SKK 辞書の代表性を主張するものではありません。入力はこの試験内で固定 seed から生成した
 * 日本語読みの合成データで、debug Android instrumentation の観測値です。
 */
@RunWith(AndroidJUnit4::class)
class DictionaryPerformanceAndroidTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun sqliteSnapshotAndPublishedLookup() {
        val arguments = InstrumentationRegistry.getArguments()
        val entryCounts = parseCounts(arguments.getString("dictionaryPerformanceEntries") ?: "1000,100000")
        val samples = arguments.getString("dictionaryPerformanceSamples")?.let {
            parseBoundedInt(it, 1, 2_048)
        } ?: 256
        val reports = entryCounts.map { entryCount -> runFixture(entryCount, samples) }
        InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
            reports.forEachIndexed { index, line -> putString("dictionary_performance_$index", line) }
        })
    }

    private fun runFixture(entryCount: Int, samples: Int): String {
        val databaseName = "dictionary-performance-${UUID.randomUUID()}.db"
        val fixture = fixture(entryCount)
        val bytes = SkkDictionaryCodec.encodeUtf8(fixture.system)
        val repository = SQLiteDictionaryRepository(context, databaseName)
        val serial = Executors.newSingleThreadExecutor()
        val direct = Executor { command -> command.run() }
        val manager = DictionaryManager(repository, serial, direct, ownedExecutor = serial)
        var importNs = 0L
        var loadNs = 0L
        var publishNs = 0L
        var pssBeforeLoadKb = 0L
        var pssAfterLoadKb = 0L
        var pssAfterCloseKb = 0L
        lateinit var queryMeasurements: Pair<Pair<PathMeasurement, PathMeasurement>, Pair<PathMeasurement, PathMeasurement>>
        try {
            pssBeforeLoadKb = Debug.getPss()
            importNs = elapsed { repository.importSystem("synthetic", "合成性能辞書", fixture.system) }
            loadNs = elapsed { awaitLoad(manager) }
            pssAfterLoadKb = Debug.getPss()

            val previousPolicy = StrictMode.getThreadPolicy()
            queryMeasurements = try {
                // 公開済み経路が SQLite を再訪しないことを、利用可能な StrictMode の disk 検知でも確認します。
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().penaltyDeath().build())
                (measurePath(samples, {
                    manager.lookup(DictionaryQuery(fixture.probeKey)).map { it.text }
                }) { actual -> assertEquals(listOf("候補${fixture.probeIndex}", "別候補${fixture.probeIndex}"), actual) } to
                    measurePath(samples, {
                        manager.lookup(DictionaryQuery("おくr", "り")).map { it.text }
                    }) { actual -> assertEquals(listOf("送り候補"), actual) }) to
                    (measurePath(samples, {
                        manager.lookup(DictionaryQuery("だい123")).map { it.text }
                    }) { actual -> assertEquals(listOf("第123"), actual) } to
                    measurePath(samples, {
                        manager.complete(CompletionQuery("か", limit = 16))
                    }) { actual -> assertEquals(minOf(16, entryCount - 2), actual.size); assertTrue(actual.all { it.startsWith("か") }) })
            } finally {
                StrictMode.setThreadPolicy(previousPolicy)
            }

            publishNs = elapsed {
                val completed = CountDownLatch(1)
                var result: PersonalWriteResult? = null
                manager.savePersonalCandidate("がくしゅう", SkkDictionaryCandidate("学習済"), true) {
                    result = it
                    completed.countDown()
                }
                assertTrue("個人学習の公開が期限内に完了しません", completed.await(90, TimeUnit.SECONDS))
                assertEquals(PersonalWriteResult.Applied, result)
            }
            assertEquals(listOf("学習済"), manager.lookup(DictionaryQuery("がくしゅう")).map { it.text })

        } finally {
            manager.close()
            serial.shutdown()
            serial.awaitTermination(90, TimeUnit.SECONDS)
            pssAfterCloseKb = Debug.getPss()
            // close 後の PSS は GC や OS の回収を強制せず、参考値としてだけ報告します。
            check(databaseName.startsWith("dictionary-performance-"))
            context.deleteDatabase(databaseName)
        }
        return report(entryCount, bytes.size, sha256(bytes), importNs, loadNs, queryMeasurements,
            publishNs, pssBeforeLoadKb, pssAfterLoadKb, pssAfterCloseKb)
    }

    private fun awaitLoad(manager: DictionaryManager) {
        val completed = CountDownLatch(1)
        var status: DictionaryManagerStatus? = null
        manager.loadAsync {
            status = it
            completed.countDown()
        }
        assertTrue("辞書読み込みの公開が期限内に完了しません", completed.await(90, TimeUnit.SECONDS))
        assertTrue(status is DictionaryManagerStatus.Ready)
    }

    private fun fixture(entryCount: Int): Fixture {
        require(entryCount >= 4)
        val entries = ArrayList<SkkDictionaryEntry>(entryCount)
        entries += SkkDictionaryEntry("おくr", listOf(SkkDictionaryCandidate("送り候補", okuriCondition = "り")))
        entries += SkkDictionaryEntry("だい#", listOf(SkkDictionaryCandidate("第#0")))
        entries += SkkDictionaryEntry("かな", listOf(SkkDictionaryCandidate("仮名"), SkkDictionaryCandidate("かな", "複数候補")))
        repeat(entryCount - entries.size) { index ->
            val key = "か" + hiraganaKey(index + FIXTURE_SEED)
            val candidates = if (index == probeIndex(entryCount)) {
                listOf(SkkDictionaryCandidate("候補$index"), SkkDictionaryCandidate("別候補$index", "合成候補"))
            } else listOf(SkkDictionaryCandidate("語$index"))
            entries += SkkDictionaryEntry(key, candidates)
        }
        val probe = probeIndex(entryCount)
        return Fixture(
            SkkDictionaryDocument(entries, SkkDictionaryEncoding.UTF8),
            "か" + hiraganaKey(probe + FIXTURE_SEED), probe,
        )
    }

    private fun hiraganaKey(value: Int): String {
        var remaining = value
        val characters = CharArray(5)
        for (position in characters.indices.reversed()) {
            characters[position] = HIRAGANA[remaining % HIRAGANA.length]
            remaining /= HIRAGANA.length
        }
        return String(characters)
    }

    private fun <T> measurePath(samples: Int, query: () -> T, verify: (T) -> Unit): PathMeasurement {
        val first = elapsed { verify(query()) }
        val cached = List(samples) { elapsed { verify(query()) } }
        return PathMeasurement(first, cached)
    }

    private fun report(
        entries: Int, bytes: Int, sha256: String, importNs: Long?, loadNs: Long?,
        paths: Pair<Pair<PathMeasurement, PathMeasurement>, Pair<PathMeasurement, PathMeasurement>>,
        publishNs: Long?, pssBefore: Long?, pssAfterLoad: Long?, pssAfterClose: Long?,
    ): String {
        val line = buildString {
            append("DICTIONARY_PERFORMANCE ")
            append("seed=$FIXTURE_SEED entries=$entries fixture_bytes=$bytes fixture_sha256=$sha256 ")
            importNs?.let { append("import_ms=${millis(it)} ") }
            loadNs?.let { append("load_publish_ms=${millis(it)} ") }
            append(pathStats("normal", paths.first.first) + " ")
            append(pathStats("okuri", paths.first.second) + " ")
            append(pathStats("numeric", paths.second.first) + " ")
            append(pathStats("completion", paths.second.second) + " ")
            publishNs?.let { append("personal_learning_reload_publish_ms=${millis(it)} ") }
            pssBefore?.let { append("pss_before_load_kb=$it ") }
            pssAfterLoad?.let { append("pss_after_load_kb=$it ") }
            pssAfterClose?.let { append("pss_after_close_kb=$it ") }
        }.trim()
        Log.i(TAG, line)
        return line
    }

    private fun pathStats(name: String, measurement: PathMeasurement): String {
        val sorted = measurement.cached.sorted()
        fun percentile(percent: Int) = sorted[((sorted.size - 1) * percent + 99) / 100]
        return "${name}_first_ms=${millis(measurement.first)} ${name}_cached_n=${sorted.size} " +
            "${name}_cached_p50_ms=${millis(percentile(50))} " +
            "${name}_cached_p95_ms=${millis(percentile(95))} ${name}_cached_max_ms=${millis(sorted.last())}"
    }

    private fun elapsed(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return System.nanoTime() - started
    }

    private fun millis(nanos: Long): String = "%.3f".format(java.util.Locale.ROOT, nanos / 1_000_000.0)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun parseCounts(value: String): List<Int> {
        val parsed = value.split(',').map { parseBoundedInt(it.trim(), 4, 100_000) }
        require(parsed.size <= 4) { "測定件数は最大4件にします" }
        require(parsed.distinct().size == parsed.size) { "測定件数は重複できません" }
        return parsed
    }

    private fun parseBoundedInt(value: String, minimum: Int, maximum: Int): Int {
        val parsed = value.toIntOrNull() ?: throw IllegalArgumentException("測定件数は整数にします")
        require(parsed in minimum..maximum) { "測定件数は $minimum..$maximum にします" }
        return parsed
    }

    private data class Fixture(val system: SkkDictionaryDocument, val probeKey: String, val probeIndex: Int)
    private data class PathMeasurement(val first: Long, val cached: List<Long>)

    private companion object {
        const val TAG = "DictionaryPerformance"
        const val FIXTURE_SEED = 20260916
        const val HIRAGANA = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん"
        fun probeIndex(entryCount: Int): Int = minOf(entryCount / 2, entryCount - 4)
    }
}
