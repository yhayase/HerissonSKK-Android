package se.haya.skk.dictionary

import android.content.Context
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.haya.skk.core.PredictionCancellation
import se.haya.skk.core.PredictionCancelledException
import se.haya.skk.core.PredictionQuery
import se.haya.skk.core.PredictionSearchFailure
import se.haya.skk.core.PredictionSearchResult
import se.haya.skk.core.dictionary.SkkDictionaryCandidate
import se.haya.skk.core.dictionary.SkkDictionaryDocument
import se.haya.skk.core.dictionary.SkkDictionaryEncoding
import se.haya.skk.core.dictionary.SkkDictionaryEntry
import se.haya.skk.core.dictionary.SkkDictionarySource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SQLitePredictionPagingTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val names = mutableListOf<String>()

    @After fun removeDatabases() { names.forEach(context::deleteDatabase) }

    @Test fun `小さいページでは後方の巨大注釈と候補本文を読まない`() {
        withRepository { repository, _ ->
            repository.importSystem("s", "試験", document(
                entry("あ1", "一"), entry("あ2", "二"), entry("あ3", "三"),
                SkkDictionaryEntry("あ4", listOf(SkkDictionaryCandidate("四", "注".repeat(2_000)))),
            ))
            val small = repository.predict(PredictionQuery("あ", limit = 2, workLimit = 10, totalResultCharsLimit = 500), repository.dictionaryRevision()) as PredictionSearchResult.Ready
            assertEquals(listOf("一", "二"), small.items.map { it.committedText })
            assertTrue(small.hasMore)
            assertEquals(PredictionSearchResult.Indeterminate(PredictionSearchFailure.RESULT_LIMIT),
                repository.predict(PredictionQuery("あ", limit = 3, totalResultCharsLimit = 500), repository.dictionaryRevision()))
        }
    }

    @Test fun `読み順の後方にある履歴を先に確定して未訪問の本文を読まない`() {
        withRepository { repository, name ->
            repository.importSystem("s", "試験", document(
                SkkDictionaryEntry("あ0", listOf(SkkDictionaryCandidate("未使用", "注".repeat(2_000)))),
                entry("あ7", "七"), entry("あ8", "八"), entry("あ9", "九"),
            ))
            usage(name, "あ7", "七", 1)
            usage(name, "あ8", "八", 2)
            usage(name, "あ9", "九", 3)
            val result = repository.predict(PredictionQuery("あ", limit = 2, workLimit = 12, totalResultCharsLimit = 500), repository.dictionaryRevision()) as PredictionSearchResult.Ready
            assertEquals(listOf("九", "八"), result.items.map { it.committedText })
            assertTrue(result.hasMore)
        }
    }

    @Test fun `同じ履歴連番の塊を候補順まで確定する`() {
        withRepository { repository, name ->
            repository.importSystem("s", "試験", document(SkkDictionaryEntry("あ", listOf(
                SkkDictionaryCandidate("Z"), SkkDictionaryCandidate("A"), SkkDictionaryCandidate("M"),
            ))))
            listOf("A", "M", "Z").forEach { usage(name, "あ", it, 10) }
            val result = repository.predict(PredictionQuery("あ", limit = 1), repository.dictionaryRevision()) as PredictionSearchResult.Ready
            assertEquals(listOf("Z"), result.items.map { it.committedText })
            assertTrue(result.hasMore)
        }
    }

    @Test fun `古い履歴と抑止候補と読みをまたぐ重複では次ページを誤判定しない`() {
        withRepository { repository, name ->
            repository.importSystem("s", "試験", document(entry("あ1", "共通"), entry("あ2", "共通"), entry("あ3", "非表示")))
            usage(name, "あ0", "削除済み", 100)
            usage(name, "あ3", "非表示", 90)
            usage(name, "あ1", "共通", 80)
            usage(name, "あ2", "共通", 70)
            sql(name, "INSERT INTO candidate_suppressions(source_id,entry_key,candidate_text,okuri_condition,date_expression) VALUES ('s','あ3','非表示','',0)")
            val result = repository.predict(PredictionQuery("あ", limit = 1), repository.dictionaryRevision()) as PredictionSearchResult.Ready
            assertEquals(listOf("共通"), result.items.map { it.committedText })
            assertEquals("あ1", result.items.single().historyTarget.readingKey)
            assertFalse(result.hasMore)
        }
    }

    @Test fun `同じ読みの別候補を履歴の走査時に使用済みとして扱わない`() {
        withRepository { repository, name ->
            repository.importSystem("s", "試験", document(SkkDictionaryEntry("あ1", listOf(
                SkkDictionaryCandidate("未使用"), SkkDictionaryCandidate("使用済み"),
            )), entry("あ2", "最新")))
            usage(name, "あ1", "使用済み", 1)
            usage(name, "あ2", "最新", 2)
            val result = repository.predict(PredictionQuery("あ", limit = 2), repository.dictionaryRevision()) as PredictionSearchResult.Ready
            assertEquals(listOf("最新", "使用済み"), result.items.map { it.committedText })
            assertTrue(result.hasMore)
        }
    }

    @Test fun `保存辞書と代替辞書の全由来を一緒に解決する`() {
        withRepository { repository, name ->
            repository.importSystem("s", "試験", document(SkkDictionaryEntry("あ", listOf(SkkDictionaryCandidate("共通", "保存注釈")))))
            val fallback = SkkDictionarySource("fallback", 7, listOf(entry("あ", "共通"), entry("あ1", "代替")))
            usage(name, "あ", "共通", 1)
            val result = repository.predict(PredictionQuery("あ", limit = 1), repository.dictionaryRevision(), listOf(fallback)) as PredictionSearchResult.Ready
            assertEquals("保存注釈", result.items.single().candidate.annotation)
            assertEquals(listOf("s", "fallback"), result.items.single().candidate.selection!!.origins.map { it.dictionaryId })
            assertTrue(result.hasMore)
        }
    }

    @Test fun `古い履歴の走査も作業量上限に含める`() {
        withRepository { repository, name ->
            repeat(4) { usage(name, "あ$it", "削除済み", it.toLong()) }
            assertEquals(PredictionSearchResult.Indeterminate(PredictionSearchFailure.WORK_LIMIT),
                repository.predict(PredictionQuery("あ", limit = 1, workLimit = 2), repository.dictionaryRevision()))
        }
    }

    @Test fun `代替辞書と保存辞書の読みをコードポイント順で併合する`() {
        withRepository { repository, _ ->
            repository.importSystem("s", "試験", document(entry("あ\uD800\uDC00", "補助面"), entry("あ\uE000", "基本面")))
            val fallback = SkkDictionarySource("fallback", 0, listOf(entry("あ!", "先頭")))
            val result = repository.predict(PredictionQuery("あ", limit = 2), repository.dictionaryRevision(), listOf(fallback)) as PredictionSearchResult.Ready
            assertEquals(listOf("先頭", "基本面"), result.items.map { it.committedText })
            assertTrue(result.hasMore)
        }
    }

    @Test fun `履歴の検索は連番索引を使い一時ソートしない`() {
        withRepository { _, name ->
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
                database.rawQuery(
                    """EXPLAIN QUERY PLAN SELECT entry_key,template_text,okuri_condition,committed_text,last_used_sequence
                         FROM candidate_usage INDEXED BY prediction_usage_recency
                         WHERE date_expression=0 AND entry_key>=? AND entry_key<?
                         ORDER BY last_used_sequence DESC,entry_key COLLATE BINARY,committed_text LIMIT 50001""".trimIndent(),
                    arrayOf("あ", "ぃ"),
                ).use { cursor ->
                    val details = mutableListOf<String>()
                    while (cursor.moveToNext()) details += cursor.getString(3)
                    assertTrue(details.any { "prediction_usage_recency" in it })
                    assertFalse(details.any { "TEMP B-TREE" in it.uppercase() })
                }
            }
        }
    }

    @Test fun `異なる前方一致の履歴を多く保持していても対象だけを返す`() {
        withRepository { repository, name ->
            repository.importSystem("s", "試験", document(entry("あ1", "一"), entry("あ2", "二")))
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
                database.beginTransaction()
                try {
                    repeat(1_000) { index ->
                        database.execSQL("INSERT INTO candidate_usage(entry_key,template_text,okuri_condition,committed_text,last_used_sequence,date_expression) VALUES (?,?, '',?,?,0)",
                            arrayOf("い$index", "対象外", "対象外", index.toLong() + 10))
                    }
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
            usage(name, "あ2", "二", 1)
            val result = repository.predict(PredictionQuery("あ", limit = 1, workLimit = 10), repository.dictionaryRevision()) as PredictionSearchResult.Ready
            assertEquals(listOf("二"), result.items.map { it.committedText })
            assertTrue(result.hasMore)
        }
    }

    @Test fun `中止は確定空や検索失敗に変換しない`() {
        withRepository { repository, _ ->
            val cancellation = PredictionCancellation()
            cancellation.cancel()
            assertThrows(PredictionCancelledException::class.java) {
                cancellation.run { repository.predict(PredictionQuery("あ"), repository.dictionaryRevision()) }
            }
        }
    }

    private fun withRepository(block: (SQLiteDictionaryRepository, String) -> Unit) {
        val name = "prediction-paging-${UUID.randomUUID()}.db".also(names::add)
        SQLiteDictionaryRepository(context, name).use { repository ->
            repository.dictionaryRevision()
            block(repository, name)
        }
    }

    private fun usage(name: String, key: String, text: String, sequence: Long) {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("INSERT INTO candidate_usage(entry_key,template_text,okuri_condition,committed_text,last_used_sequence,date_expression) VALUES (?,?,?, ?,?,0)",
                arrayOf(key, text, "", text, sequence))
        }
    }

    private fun sql(name: String, sql: String) {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { it.execSQL(sql) }
    }

    private fun entry(key: String, text: String) = SkkDictionaryEntry(key, listOf(SkkDictionaryCandidate(text)))
    private fun document(vararg entries: SkkDictionaryEntry) = SkkDictionaryDocument(entries.toList(), SkkDictionaryEncoding.UTF8)
}
