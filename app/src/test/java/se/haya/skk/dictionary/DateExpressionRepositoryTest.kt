package se.haya.skk.dictionary

import android.content.Context
import java.util.UUID
import se.haya.skk.core.dictionary.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30])
class DateExpressionRepositoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val databases = mutableListOf<String>()
    private fun databaseName(): String = "date-${UUID.randomUUID()}.db".also(databases::add)
    @After fun removeDatabases() { databases.forEach(context::deleteDatabase) }

    @Test fun `システムの式を抑止しても同じ本文の文字列は検索と補完に残る`() {
        val literal = SkkDictionaryCandidate("(skk-current-date)")
        val dynamic = literal.copy(dateExpression = true)
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            val source = repository.importSystem("date-system", "日付試験", SkkDictionaryDocument(listOf(SkkDictionaryEntry("datefixture", listOf(literal, dynamic))), SkkDictionaryEncoding.UTF8))
            repository.deleteCandidate(DeleteCandidateRequest(0, listOf(
                StoredCandidateOriginRef(source.id, source.generation, CandidateOriginKind.STORED_SYSTEM, "datefixture", dynamic.text, null, true),
            )), emptyMap())
            assertEquals(listOf(literal.text), repository.lookup("datefixture").asComposite().lookup(se.haya.skk.core.DictionaryQuery("datefixture")).map { it.text })
            assertEquals(listOf("datefixture"), repository.complete(se.haya.skk.core.CompletionQuery("date"), repository.dictionaryRevision()))
            assertTrue(repository.listCandidateSuppressions().suppressions.single().key.dateExpression)
            repository.deleteCandidate(DeleteCandidateRequest(1, listOf(
                StoredCandidateOriginRef(source.id, source.generation, CandidateOriginKind.STORED_SYSTEM, "datefixture", literal.text, null),
            )), emptyMap())
            assertEquals(setOf(false, true), repository.listCandidateSuppressions().suppressions.map { it.key.dateExpression }.toSet())
            val bytes = java.io.ByteArrayOutputStream()
            repository.writeCompleteBackup(bytes, emptyList(), "date-fixture")
            ValidatedDictionaryBackup.prepare(context, bytes.toByteArray().inputStream()).use { staged ->
                SQLiteDictionaryRepository(context, databaseName()).use { target ->
                    target.restoreComplete(staged, target.dictionaryRevision())
                    assertEquals(setOf(false, true), target.listCandidateSuppressions().suppressions.map { it.key.dateExpression }.toSet())
                }
            }
        }
    }

    @Test fun `日付式は予測の上限を消費せず通常候補を返す`() {
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            val entries = (0..15).map { SkkDictionaryEntry("datefixture%02d".format(it), listOf(SkkDictionaryCandidate("(skk-current-date)", dateExpression = true))) } +
                SkkDictionaryEntry("datefixturelast", listOf(SkkDictionaryCandidate("通常候補")))
            repository.replacePersonal(SkkDictionaryDocument(entries, SkkDictionaryEncoding.UTF8), 0)
            val result = repository.predict(se.haya.skk.core.PredictionQuery("datefixture", workLimit = 8), repository.dictionaryRevision())
                as se.haya.skk.core.PredictionSearchResult.Ready
            assertEquals(listOf("通常候補"), result.items.map { it.committedText })
        }
    }

    @Test fun `版6の式に似た文字列と順位と注釈と抑止と履歴を版7で保持する`() {
        val name = databaseName()
        context.getDatabasePath(name).parentFile!!.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE dictionary_sources(source_id TEXT PRIMARY KEY,source_name TEXT,source_kind INTEGER,generation INTEGER,enabled INTEGER,source_order INTEGER,origin_url TEXT)")
            db.execSQL("INSERT INTO dictionary_sources VALUES('personal','移行試験',0,2,1,0,NULL)")
            db.execSQL("CREATE TABLE dictionary_candidates(source_id TEXT,entry_key TEXT,ordinal INTEGER,candidate_text TEXT,annotation TEXT,okuri_condition TEXT,entry_section TEXT,entry_rank INTEGER,annotation_is_personal INTEGER,PRIMARY KEY(source_id,entry_key,ordinal))")
            db.execSQL("INSERT INTO dictionary_candidates VALUES('personal','datefixture',0,'(skk-current-date)','注釈',NULL,'okuri-nasi',9,1)")
            db.execSQL("CREATE TABLE candidate_suppressions(source_id TEXT,entry_key TEXT,candidate_text TEXT,okuri_condition TEXT,PRIMARY KEY(source_id,entry_key,candidate_text,okuri_condition))")
            db.execSQL("INSERT INTO candidate_suppressions VALUES('gone','datefixture','(skk-current-date)','')")
            db.execSQL("CREATE TABLE dictionary_source_versions(source_id TEXT PRIMARY KEY,last_generation INTEGER)")
            db.execSQL("INSERT INTO dictionary_source_versions VALUES('personal',2)")
            db.execSQL("CREATE TABLE dictionary_metadata(singleton INTEGER PRIMARY KEY,revision INTEGER,allow_fallback INTEGER,usage_sequence INTEGER)")
            db.execSQL("INSERT INTO dictionary_metadata VALUES(1,0,1,4)")
            db.execSQL("CREATE TABLE candidate_usage(entry_key TEXT,template_text TEXT,okuri_condition TEXT,committed_text TEXT,last_used_sequence INTEGER,PRIMARY KEY(entry_key,template_text,okuri_condition,committed_text))")
            db.execSQL("INSERT INTO candidate_usage VALUES('datefixture','(skk-current-date)','','日付',4)")
            db.version = 6
        }
        SQLiteDictionaryRepository(context, name).use { repository ->
            val candidate = repository.lookup("datefixture").personal!!.candidates("datefixture").single()
            assertFalse(candidate.dateExpression)
            assertTrue(candidate.annotationIsPersonal)
            assertEquals("注釈", candidate.annotation)
            assertFalse(repository.listCandidateSuppressions().suppressions.single().key.dateExpression)
            assertEquals(4L, repository.predictionUsage(se.haya.skk.core.PredictionHistoryTarget("datefixture", candidate.text, null, "日付"), repository.dictionaryRevision()))
            repository.promotePersonalCandidate("datefixture", candidate.copy(dateExpression = true))
            assertEquals(listOf(true, false), repository.lookup("datefixture").personal!!.candidates("datefixture").map { it.dateExpression })
        }
    }

    @Test fun `式だけを削除しても同じ本文の文字列と履歴を保持する`() {
        val literal = SkkDictionaryCandidate("(skk-current-date)")
        val dynamic = literal.copy(dateExpression = true)
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            repository.replacePersonal(SkkDictionaryDocument(listOf(SkkDictionaryEntry("datefixture", listOf(literal, dynamic))), SkkDictionaryEncoding.UTF8), 0)
            val literalHistory = se.haya.skk.core.PredictionHistoryTarget("datefixture", literal.text, null, "日付")
            val dynamicHistory = literalHistory.copy(dateExpression = true)
            repository.promotePersonalCandidate("datefixture", literal, literalHistory)
            repository.promotePersonalCandidate("datefixture", dynamic, dynamicHistory)
            val generation = repository.listSources().first().generation
            repository.deleteCandidate(DeleteCandidateRequest(generation, listOf(
                StoredCandidateOriginRef("personal", generation, CandidateOriginKind.PERSONAL, "datefixture", dynamic.text, null, true),
            )), emptyMap())
            assertEquals(listOf(literal), repository.lookup("datefixture").personal!!.candidates("datefixture"))
            assertNotNull(repository.predictionUsage(literalHistory, repository.dictionaryRevision()))
            assertNull(repository.predictionUsage(dynamicHistory, repository.dictionaryRevision()))
        }
    }

    @Test fun `完全バックアップで式と文字列と別々の使用履歴を復元する`() {
        val literal = SkkDictionaryCandidate("(skk-current-date)")
        val dynamic = literal.copy(dateExpression = true)
        val bytes = java.io.ByteArrayOutputStream()
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            repository.replacePersonal(SkkDictionaryDocument(listOf(SkkDictionaryEntry("datefixture", listOf(literal, dynamic))), SkkDictionaryEncoding.UTF8), 0)
            repository.promotePersonalCandidate("datefixture", literal,
                se.haya.skk.core.PredictionHistoryTarget("datefixture", literal.text, null, "日付"))
            repository.promotePersonalCandidate("datefixture", dynamic,
                se.haya.skk.core.PredictionHistoryTarget("datefixture", dynamic.text, null, "日付", true))
            repository.writeCompleteBackup(bytes, emptyList(), "date-fixture")
        }
        ValidatedDictionaryBackup.prepare(context, bytes.toByteArray().inputStream()).use { staged ->
            SQLiteDictionaryRepository(context, databaseName()).use { target ->
                target.restoreComplete(staged, target.dictionaryRevision())
                assertEquals(listOf(dynamic, literal), target.lookup("datefixture").personal!!.candidates("datefixture"))
                for (flag in listOf(false, true)) assertNotNull(target.predictionUsage(
                    se.haya.skk.core.PredictionHistoryTarget("datefixture", literal.text, null, "日付", flag), target.dictionaryRevision()))
            }
        }
    }

    @Test fun `同じ本文の日付式と文字列を保存して学習しても式を保持する`() {
        val name = databaseName()
        val literal = SkkDictionaryCandidate("(skk-current-date)")
        val dynamic = literal.copy(dateExpression = true)
        SQLiteDictionaryRepository(context, name).use { repository ->
            repository.replacePersonal(SkkDictionaryDocument(listOf(SkkDictionaryEntry("datefixture", listOf(literal, dynamic))), SkkDictionaryEncoding.UTF8), 0)
            repository.promotePersonalCandidate("datefixture", dynamic)
        }
        SQLiteDictionaryRepository(context, name).use { repository ->
            val candidates = repository.lookup("datefixture").personal!!.candidates("datefixture")
            assertEquals(listOf(true, false), candidates.map { it.dateExpression })
            assertTrue(candidates.all { it.text == "(skk-current-date)" })
            assertEquals(candidates, SkkDictionaryCodec.parse(repository.exportPersonal()).entries.single().candidates)
        }
    }

}
