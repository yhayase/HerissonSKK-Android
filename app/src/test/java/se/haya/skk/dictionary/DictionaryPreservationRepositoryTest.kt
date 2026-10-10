package se.haya.skk.dictionary

import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.haya.skk.core.DictionaryQuery
import se.haya.skk.core.dictionary.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30])
class DictionaryPreservationRepositoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val names = mutableListOf<String>()
    private fun name() = "preservation-${UUID.randomUUID()}.db".also(names::add)
    @After fun cleanup() { names.forEach(context::deleteDatabase) }

    private fun document() = SkkDictionaryCodec.parseText("""
        ;; okuri-ari entries.
        zz-preservation-app-middlek /独立保存先頭候補/[に/独立保存後続候補/]/
        ;; okuri-nasi entries.
        zz-preservation-app-zulu /(concat "独立保存一行目\n独立保存二行目");*(concat "独立保存個人注釈\r追記")/
        zz-preservation-app-alpha /独立保存後続候補;*独立保存個人注釈/
    """.trimIndent())

    @Test fun `保存と再起動で区分 順序 個人注釈と改行を保持する`() {
        val expected = document()
        val dbName = name()
        SQLiteDictionaryRepository(context, dbName).use { it.replacePersonal(expected) }
        SQLiteDictionaryRepository(context, dbName).use { repository ->
            assertArrayEquals(SkkDictionaryCodec.encodeUtf8(expected), repository.exportPersonal())
            assertEquals(expected.entries, repository.loadSnapshot().personal!!.entriesForBackup().toList())
            val candidate = repository.lookup("zz-preservation-app-zulu").asComposite()
                .lookup(DictionaryQuery("zz-preservation-app-zulu")).single()
            assertEquals("独立保存一行目\n独立保存二行目", candidate.text)
            assertEquals("独立保存個人注釈\r追記", candidate.annotation)
            assertTrue(candidate.annotationIsPersonal)
        }
    }

    @Test fun `完全バックアップと復元でも区分 順序 注釈と改行を保持する`() {
        val expected = document()
        val bytes = SQLiteDictionaryRepository(context, name()).use { repository ->
            repository.replacePersonal(expected)
            ByteArrayOutputStream().also { repository.writeCompleteBackup(it, emptyList(), "独立保存試験") }.toByteArray()
        }
        ValidatedDictionaryBackup.prepare(context, bytes.inputStream()).use { validated ->
            SQLiteDictionaryRepository(context, name()).use { restored ->
                restored.restoreComplete(validated, restored.dictionaryRevision())
                assertArrayEquals(SkkDictionaryCodec.encodeUtf8(expected), restored.exportPersonal())
            }
        }
    }

    @Test fun `マージは取込見出しを先頭にして既存注釈の属性を補う`() {
        SQLiteDictionaryRepository(context, name()).use { repository ->
            repository.replacePersonal(SkkDictionaryCodec.parseText("""
                zz-preservation-app-zulu /独立保存先頭候補/
                zz-preservation-app-alpha /独立保存後続候補;*独立保存個人注釈/
            """.trimIndent()))
            repository.mergePersonal(SkkDictionaryCodec.parseText("""
                zz-preservation-app-alpha /独立保存後続候補/
                zz-preservation-app-middle /独立保存先頭候補/
            """.trimIndent()))
            val entries = SkkDictionaryCodec.parse(repository.exportPersonal()).entries
            assertEquals(listOf("zz-preservation-app-alpha", "zz-preservation-app-middle", "zz-preservation-app-zulu"), entries.map { it.key })
            assertEquals("独立保存個人注釈", entries.first().candidates.single().annotation)
            assertTrue(entries.first().candidates.single().annotationIsPersonal)
        }
    }

    @Test fun `学習した見出しを先頭に移し注釈属性を維持する`() {
        SQLiteDictionaryRepository(context, name()).use { repository ->
            repository.replacePersonal(SkkDictionaryCodec.parseText("""
                zz-preservation-app-zulu /独立保存先頭候補/
                zz-preservation-app-alpha /独立保存後続候補;*独立保存個人注釈/
            """.trimIndent()))
            repository.promotePersonalCandidate("zz-preservation-app-alpha", SkkDictionaryCandidate("独立保存後続候補"))
            val entries = SkkDictionaryCodec.parse(repository.exportPersonal()).entries
            assertEquals(listOf("zz-preservation-app-alpha", "zz-preservation-app-zulu"), entries.map { it.key })
            assertTrue(entries.first().candidates.single().annotationIsPersonal)
        }
    }
    @Test fun `旧版1から5を移行して決定的な区分と順序と注釈属性を持つ`() {
        for (version in 1..5) {
            val dbName = name()
            context.getDatabasePath(dbName).parentFile!!.mkdirs()
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { db ->
                db.execSQL("CREATE TABLE dictionary_sources(source_id TEXT PRIMARY KEY,source_name TEXT,source_kind INTEGER,generation INTEGER,enabled INTEGER,source_order INTEGER${if (version >= 4) ",origin_url TEXT" else ""})")
                db.execSQL("INSERT INTO dictionary_sources(source_id,source_name,source_kind,generation,enabled,source_order) VALUES('personal','独自移行',0,2,1,0)")
                db.execSQL("CREATE TABLE dictionary_candidates(source_id TEXT,entry_key TEXT,ordinal INTEGER,candidate_text TEXT,annotation TEXT,okuri_condition TEXT,PRIMARY KEY(source_id,entry_key,ordinal))")
                db.execSQL("INSERT INTO dictionary_candidates VALUES('personal','zfixture',0,'独自移行後続','独自旧注釈',NULL)")
                db.execSQL("INSERT INTO dictionary_candidates VALUES('personal','afixture',0,'独自移行先頭',NULL,NULL)")
                if (version >= 2) {
                    db.execSQL("CREATE TABLE candidate_suppressions(source_id TEXT,entry_key TEXT,candidate_text TEXT,okuri_condition TEXT,PRIMARY KEY(source_id,entry_key,candidate_text,okuri_condition))")
                    db.execSQL("CREATE TABLE dictionary_source_versions(source_id TEXT PRIMARY KEY,last_generation INTEGER)")
                    db.execSQL("INSERT INTO dictionary_source_versions VALUES('personal',2)")
                }
                if (version >= 3) {
                    db.execSQL("CREATE TABLE dictionary_metadata(singleton INTEGER PRIMARY KEY,revision INTEGER,allow_fallback INTEGER${if (version >= 5) ",usage_sequence INTEGER NOT NULL DEFAULT 0" else ""})")
                    db.execSQL("INSERT INTO dictionary_metadata(singleton,revision,allow_fallback) VALUES(1,0,1)")
                }
                if (version >= 5) db.execSQL("CREATE TABLE candidate_usage(entry_key TEXT,template_text TEXT,okuri_condition TEXT,committed_text TEXT,last_used_sequence INTEGER,PRIMARY KEY(entry_key,template_text,okuri_condition,committed_text))")
                db.version = version
            }
            SQLiteDictionaryRepository(context, dbName).use { repository ->
                val entries = SkkDictionaryCodec.parse(repository.exportPersonal()).entries
                assertEquals(listOf("afixture", "zfixture"), entries.map { it.key })
                assertTrue(entries.all { it.section == SkkDictionarySection.OKURI_NASI })
                assertFalse(entries.last().candidates.single().annotationIsPersonal)
                assertEquals("独自旧注釈", entries.last().candidates.single().annotation)
                repository.promotePersonalCandidate("zfixture", SkkDictionaryCandidate("独自移行追加"))
                assertEquals("zfixture", SkkDictionaryCodec.parse(repository.exportPersonal()).entries.first().key)
            }
        }
    }

    @Test fun `学習順位のオーバーフローは変更全体を取り消す`() {
        val dbName = name()
        SQLiteDictionaryRepository(context, dbName).use { repository ->
            repository.replacePersonal(SkkDictionaryCodec.parseText("fixture-overflow /独自最大候補/"))
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, 0).use { db ->
                db.execSQL("UPDATE dictionary_candidates SET entry_rank=?", arrayOf(Long.MAX_VALUE))
            }
            val previous = repository.exportPersonal()
            val revision = repository.dictionaryRevision()
            assertThrows(ArithmeticException::class.java) {
                repository.promotePersonalCandidate("fixture-overflow", SkkDictionaryCandidate("独自失敗候補"))
            }
            assertArrayEquals(previous, repository.exportPersonal())
            assertEquals(revision, repository.dictionaryRevision())
        }
    }

    @Test fun `候補削除で見出しの区分と順位と残る注釈を変えない`() {
        SQLiteDictionaryRepository(context, name()).use { repository ->
            repository.replacePersonal(SkkDictionaryCodec.parseText(";; okuri-ari entries.\nzfixture-delete /独自削除先頭/\nafixture-delete /独自削除対象/独自削除残存;*独自削除注/"))
            repository.deleteCandidate(DeleteCandidateRequest(1, listOf(
                StoredCandidateOriginRef("personal", 1, CandidateOriginKind.PERSONAL, "afixture-delete", "独自削除対象", null),
            )), emptyMap())
            val entries = SkkDictionaryCodec.parse(repository.exportPersonal()).entries
            assertEquals(listOf("zfixture-delete", "afixture-delete"), entries.map { it.key })
            assertTrue(entries.all { it.section == SkkDictionarySection.OKURI_ARI })
            assertEquals("独自削除残存", entries.last().candidates.single().text)
            assertTrue(entries.last().candidates.single().annotationIsPersonal)
        }
    }

    @Test fun `旧版1から3バックアップを保存でき区分順序の既定値を持つ`() {
        for (version in 1..3) {
            val records = listOf(
                BackupRecord.Header(COMPLETE_DICTIONARY_BACKUP_FORMAT, version, "fixture-legacy"),
                BackupRecord.Source("personal", "独自旧保存", BackupSourceKind.PERSONAL, 0, true, 0),
                BackupRecord.Candidate("personal", "afixture-old", 0, "独自旧保存一", null, null),
                BackupRecord.Candidate("personal", "zfixture-old", 0, "独自旧保存二", "独自旧保存注", null),
                BackupRecord.SourceVersion("personal", 0),
                BackupRecord.End(1, 2, 0, 1),
            )
            val output = ByteArrayOutputStream()
            CompleteDictionaryBackupCodec().write(records.asSequence(), output)
            ValidatedDictionaryBackup.prepare(context, output.toByteArray().inputStream()).use { validated ->
                SQLiteDictionaryRepository(context, name()).use { repository ->
                    repository.restoreComplete(validated, repository.dictionaryRevision())
                    val entries = SkkDictionaryCodec.parse(repository.exportPersonal()).entries
                    assertEquals(listOf("afixture-old", "zfixture-old"), entries.map { it.key })
                    assertTrue(entries.all { it.section == SkkDictionarySection.OKURI_NASI })
                    assertFalse(entries.last().candidates.single().annotationIsPersonal)
                    repository.promotePersonalCandidate("zfixture-old", SkkDictionaryCandidate("独自旧保存二"))
                    assertEquals("zfixture-old", SkkDictionaryCodec.parse(repository.exportPersonal()).entries.first().key)
                }
            }
        }
    }

    @Test fun `明示した送りなし区分を補完と予測にも反映する`() {
        SQLiteDictionaryRepository(context, name()).use { repository ->
            repository.replacePersonal(SkkDictionaryCodec.parseText(";; okuri-nasi entries.\nあふぃくすk /独自区分予測;*独自区分注/"))
            assertEquals(listOf("あふぃくすk"), repository.complete(se.haya.skk.core.CompletionQuery("あ"), repository.dictionaryRevision()))
            val prediction = repository.predict(se.haya.skk.core.PredictionQuery("あ"), repository.dictionaryRevision())
            val candidate = (prediction as se.haya.skk.core.PredictionSearchResult.Ready).items.single().candidate
            assertEquals("独自区分予測", candidate.text)
            assertEquals(SkkDictionarySection.OKURI_NASI, candidate.entrySection)
            assertTrue(candidate.annotationIsPersonal)
        }
    }

    @Test fun `意味のない個人注釈属性をマージで永続化しない`() {
        SQLiteDictionaryRepository(context, name()).use { repository ->
            repository.replacePersonal(SkkDictionaryCodec.parseText("fixture-merge-boundary /独自既存注釈;*独自既存注/"))
            val before = repository.exportPersonal()
            val revision = repository.dictionaryRevision()
            val invalid = SkkDictionaryDocument(listOf(SkkDictionaryEntry("fixture-merge-boundary", listOf(
                SkkDictionaryCandidate("独自不正注釈", annotationIsPersonal = true),
            ))), SkkDictionaryEncoding.UTF8)
            assertThrows(SkkDictionaryFormatException::class.java) { repository.mergePersonal(invalid) }
            assertArrayEquals(before, repository.exportPersonal())
            assertEquals(revision, repository.dictionaryRevision())
        }
    }

    private fun invalidPersonalAnnotation() = SkkDictionaryDocument(listOf(SkkDictionaryEntry("fixture-validation-boundary", listOf(
        SkkDictionaryCandidate("独自不正注釈属性", annotationIsPersonal = true),
    ))), SkkDictionaryEncoding.UTF8)

    @Test fun `意味のない個人注釈属性を個人辞書置換で永続化しない`() {
        SQLiteDictionaryRepository(context, name()).use { repository ->
            repository.replacePersonal(SkkDictionaryCodec.parseText("fixture-validation-boundary /独自置換前/"))
            val before = repository.exportPersonal()
            val revision = repository.dictionaryRevision()
            assertThrows(SkkDictionaryFormatException::class.java) { repository.replacePersonal(invalidPersonalAnnotation()) }
            assertArrayEquals(before, repository.exportPersonal())
            assertEquals(revision, repository.dictionaryRevision())
        }
    }

    @Test fun `意味のない個人注釈属性をシステム辞書取込で永続化しない`() {
        SQLiteDictionaryRepository(context, name()).use { repository ->
            val revision = repository.dictionaryRevision()
            assertThrows(SkkDictionaryFormatException::class.java) { repository.importSystem("fixture-invalid-system", "独自不正システム", invalidPersonalAnnotation()) }
            assertEquals(listOf("personal"), repository.listSources().map { it.id })
            assertEquals(revision, repository.dictionaryRevision())
        }
    }

}
