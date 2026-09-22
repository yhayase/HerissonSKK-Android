package se.haya.skk.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.UUID
import se.haya.skk.core.DictionaryQuery
import se.haya.skk.core.dictionary.SkkDictionaryCodec
import se.haya.skk.core.dictionary.SkkDictionaryDocument
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CandidateDeletionRepositoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val databases = mutableListOf<String>()

    @After fun removeDatabases() {
        databases.forEach(context::deleteDatabase)
    }

    @Test fun `表示候補の全保存由来を一括削除抑止し個人世代を一度進める`() {
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            repository.replacePersonal(document("かk /[く/書/]/[け/書/]/"), 0)
            repository.importSystem("system", "辞書", document("かk /[く/書/]/"))
            val origins = listOf(
                origin("personal", 1, CandidateOriginKind.PERSONAL, "かk", "書", "く"),
                origin("personal", 1, CandidateOriginKind.PERSONAL, "かk", "書", "け"),
                origin("system", 1, CandidateOriginKind.STORED_SYSTEM, "かk", "書", "く"),
                origin("builtin", 7, CandidateOriginKind.IMMUTABLE_SYSTEM, "かk", "書", "く"),
            )

            val result = repository.deleteCandidate(DeleteCandidateRequest(1, origins), mapOf("builtin" to 7L))

            assertEquals(2L, result.generation)
            val exported = repository.exportPersonalWithMetadata()
            assertTrue(SkkDictionaryCodec.parse(exported.bytes).entries.isEmpty())
            assertEquals(2, exported.excludedSuppressionCount)
            assertTrue(exported.bytes.contentEquals(repository.exportPersonal()))
            assertEquals(
                listOf("builtin", "system"),
                repository.listCandidateSuppressions().suppressions.map { it.key.sourceId },
            )
            assertEquals(2L, repository.listCandidateSuppressions().personalGeneration)
        }
    }

    @Test fun `復元は完全一致する一件だけを消し個人世代を進める`() {
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            repository.importSystem("system", "辞書", document("かな /候補/別候補/"))
            repository.deleteCandidate(
                DeleteCandidateRequest(0, listOf(origin("system", 1, CandidateOriginKind.STORED_SYSTEM, "かな", "候補"))),
                emptyMap(),
            )
            val key = repository.listCandidateSuppressions().suppressions.single().key

            val restored = repository.restoreCandidateSuppression(key, 1)

            assertEquals(2L, restored.generation)
            assertTrue(repository.listCandidateSuppressions().suppressions.isEmpty())
            assertThrows(CandidateSuppressionMissingException::class.java) {
                repository.restoreCandidateSuppression(key, 2)
            }
            assertEquals(2L, repository.listCandidateSuppressions().personalGeneration)
        }
    }

    @Test fun `削除と復元は全故障点で候補抑止世代を一緒に戻す`() {
        for (point in DictionaryWritePoint.entries) {
            var failure: DictionaryWritePoint? = null
            SQLiteDictionaryRepository(context, databaseName()) { reached ->
                if (reached == failure) throw InjectedWriteFailure()
            }.use { repository ->
                repository.replacePersonal(document("かな /個人/"), 0)
                repository.importSystem("system", "辞書", document("かな /システム/"))
                val request = DeleteCandidateRequest(1, listOf(
                    origin("personal", 1, CandidateOriginKind.PERSONAL, "かな", "個人"),
                    origin("system", 1, CandidateOriginKind.STORED_SYSTEM, "かな", "システム"),
                ))
                failure = point
                assertThrows(InjectedWriteFailure::class.java) {
                    repository.deleteCandidate(request, emptyMap())
                }
                assertEquals(listOf("個人", "システム"), lookupTexts(repository, "かな"))
                assertTrue(repository.listCandidateSuppressions().suppressions.isEmpty())
                assertEquals(1L, repository.listCandidateSuppressions().personalGeneration)
            }
        }

        for (point in DictionaryWritePoint.entries) {
            var failure: DictionaryWritePoint? = null
            SQLiteDictionaryRepository(context, databaseName()) { reached ->
                if (reached == failure) throw InjectedWriteFailure()
            }.use { repository ->
                repository.importSystem("system", "辞書", document("かな /システム/"))
                repository.deleteCandidate(
                    DeleteCandidateRequest(0, listOf(
                        origin("system", 1, CandidateOriginKind.STORED_SYSTEM, "かな", "システム"),
                    )),
                    emptyMap(),
                )
                val before = repository.listCandidateSuppressions()
                failure = point
                assertThrows(InjectedWriteFailure::class.java) {
                    repository.restoreCandidateSuppression(before.suppressions.single().key, before.personalGeneration)
                }
                assertEquals(before.suppressions, repository.listCandidateSuppressions().suppressions)
                assertEquals(before.personalGeneration, repository.listCandidateSuppressions().personalGeneration)
            }
        }
    }

    @Test fun `削除再追加した同じIDは世代を再利用せず古い選択を拒否する`() {
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            repository.importSystem("system", "旧辞書", document("かな /候補/"))
            val old = origin("system", 1, CandidateOriginKind.STORED_SYSTEM, "かな", "候補")
            repository.removeSystem("system", 1)
            val added = repository.importSystem("system", "新辞書", document("かな /候補/"))

            assertEquals(2L, added.generation)
            assertThrows(CandidateOriginMismatchException::class.java) {
                repository.deleteCandidate(DeleteCandidateRequest(0, listOf(old)), emptyMap())
            }
            assertTrue(repository.listCandidateSuppressions().suppressions.isEmpty())
        }
    }

    @Test fun `同じIDの辞書更新後も抑止を保持する`() {
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            repository.importSystem("system", "辞書", document("かな /候補/"))
            repository.deleteCandidate(
                DeleteCandidateRequest(0, listOf(
                    origin("system", 1, CandidateOriginKind.STORED_SYSTEM, "かな", "候補"),
                )),
                emptyMap(),
            )

            repository.importSystem("system", "更新辞書", document("かな /候補/追加/"), 1)

            val suppression = repository.loadSnapshot().suppressions.single().key
            assertEquals("system", suppression.sourceId)
            assertEquals("かな", suppression.entryKey)
            assertEquals("候補", suppression.templateText)
        }
    }

    @Test fun `未知の組み込み辞書と種別ID世代候補の不一致を拒否する`() {
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            repository.replacePersonal(document("かな /個人/"), 0)
            repository.importSystem("system", "辞書", document("かな /候補/"))
            val invalid = listOf(
                origin("unknown", 1, CandidateOriginKind.IMMUTABLE_SYSTEM, "かな", "候補") to emptyMap(),
                origin("builtin", 2, CandidateOriginKind.IMMUTABLE_SYSTEM, "かな", "候補") to mapOf("builtin" to 1L),
                origin("system", 1, CandidateOriginKind.PERSONAL, "かな", "候補") to emptyMap(),
                origin("personal", 1, CandidateOriginKind.STORED_SYSTEM, "かな", "個人") to emptyMap(),
                origin("system", 0, CandidateOriginKind.STORED_SYSTEM, "かな", "候補") to emptyMap(),
                origin("system", 1, CandidateOriginKind.STORED_SYSTEM, "かな", "不在") to emptyMap(),
                origin("system", 1, CandidateOriginKind.IMMUTABLE_SYSTEM, "かな", "候補") to mapOf("system" to 1L),
            )
            invalid.forEach { (target, approved) ->
                assertThrows(CandidateOriginMismatchException::class.java) {
                    repository.deleteCandidate(DeleteCandidateRequest(1, listOf(target)), approved)
                }
            }
            assertEquals(listOf("個人", "候補"), lookupTexts(repository, "かな"))
            assertTrue(repository.listCandidateSuppressions().suppressions.isEmpty())
        }
    }

    @Test fun `空対象と重複対象と方針拒否は永続状態を変えない`() {
        assertThrows(IllegalArgumentException::class.java) { DeleteCandidateRequest(0, emptyList()) }
        val target = origin("builtin", 1, CandidateOriginKind.IMMUTABLE_SYSTEM, "かな", "候補")
        assertThrows(IllegalArgumentException::class.java) {
            DeleteCandidateRequest(0, listOf(target, target))
        }
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            assertThrows(PersonalDataPolicyRejectedException::class.java) {
                repository.deleteCandidate(DeleteCandidateRequest(0, listOf(target)), mapOf("builtin" to 1L)) { false }
            }
            assertEquals(0L, repository.listCandidateSuppressions().personalGeneration)
            assertTrue(repository.listCandidateSuppressions().suppressions.isEmpty())
        }
    }

    @Test fun `既存抑止への再要求は成功扱いにせず世代を変えない`() {
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            repository.importSystem("system", "辞書", document("かな /候補/"))
            val target = origin("system", 1, CandidateOriginKind.STORED_SYSTEM, "かな", "候補")
            repository.deleteCandidate(DeleteCandidateRequest(0, listOf(target)), emptyMap())

            assertThrows(CandidateOriginMismatchException::class.java) {
                repository.deleteCandidate(DeleteCandidateRequest(1, listOf(target)), emptyMap())
            }
            assertEquals(1L, repository.listCandidateSuppressions().personalGeneration)
            assertEquals(1, repository.listCandidateSuppressions().suppressions.size)
        }
    }

    @Test fun `キー検索と全件スナップショットは同じ読取内の抑止を返す`() {
        SQLiteDictionaryRepository(context, databaseName()).use { repository ->
            repository.importSystem("system", "辞書", document("かな /候補/\nほか /別候補/"))
            repository.deleteCandidate(
                DeleteCandidateRequest(0, listOf(
                    origin("system", 1, CandidateOriginKind.STORED_SYSTEM, "かな", "候補"),
                    origin("system", 1, CandidateOriginKind.STORED_SYSTEM, "ほか", "別候補"),
                )),
                emptyMap(),
            )

            assertEquals(listOf("かな"), repository.lookup("かな").suppressions.map { it.key.entryKey })
            assertEquals(listOf("かな", "ほか"), repository.loadSnapshot().suppressions.map { it.key.entryKey })
        }
    }

    @Test fun `v1からの更新は内容順序有効状態世代を保つ`() {
        val name = databaseName()
        createVersion1Database(name)

        SQLiteDictionaryRepository(context, name).use { repository ->
            val sources = repository.listSources()
            assertEquals(listOf("personal", "system"), sources.map { it.id })
            assertEquals(listOf(4L, 9L), sources.map { it.generation })
            assertFalse(sources.last().enabled)
            assertEquals(listOf("先", "後"), SkkDictionaryCodec.parse(repository.exportPersonal())
                .entries.single().candidates.map { it.text })
            assertTrue(repository.listCandidateSuppressions().suppressions.isEmpty())

            val updated = repository.replacePersonal(document("かな /更新/"), 4)
            assertEquals(5L, updated.generation)
        }
    }

    private fun createVersion1Database(name: String) {
        val database = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        database.execSQL("CREATE TABLE dictionary_sources (source_id TEXT PRIMARY KEY NOT NULL, source_name TEXT NOT NULL, source_kind INTEGER NOT NULL, generation INTEGER NOT NULL, enabled INTEGER NOT NULL, source_order INTEGER NOT NULL)")
        database.execSQL("CREATE TABLE dictionary_candidates (source_id TEXT NOT NULL, entry_key TEXT NOT NULL, ordinal INTEGER NOT NULL, candidate_text TEXT NOT NULL, annotation TEXT, okuri_condition TEXT, PRIMARY KEY (source_id, entry_key, ordinal), FOREIGN KEY (source_id) REFERENCES dictionary_sources(source_id) ON DELETE CASCADE)")
        database.execSQL("CREATE INDEX candidates_key_lookup ON dictionary_candidates(entry_key, source_id, ordinal)")
        database.execSQL("INSERT INTO dictionary_sources VALUES ('personal', '個人辞書', 0, 4, 1, 0)")
        database.execSQL("INSERT INTO dictionary_sources VALUES ('system', '旧辞書', 1, 9, 0, 3)")
        database.execSQL("INSERT INTO dictionary_candidates VALUES ('personal', 'かな', 0, '先', NULL, NULL)")
        database.execSQL("INSERT INTO dictionary_candidates VALUES ('personal', 'かな', 1, '後', NULL, NULL)")
        database.execSQL("INSERT INTO dictionary_candidates VALUES ('system', 'かな', 0, '非表示', NULL, NULL)")
        database.version = 1
        database.close()
    }

    private fun origin(
        sourceId: String,
        generation: Long,
        kind: CandidateOriginKind,
        key: String,
        text: String,
        okuri: String? = null,
    ) = StoredCandidateOriginRef(sourceId, generation, kind, key, text, okuri)

    private fun document(text: String): SkkDictionaryDocument = SkkDictionaryCodec.parseText(text)

    private fun lookupTexts(repository: SQLiteDictionaryRepository, key: String): List<String> =
        repository.lookup(key).asComposite().lookup(DictionaryQuery(key)).map { it.text }

    private fun databaseName(): String = "candidate-deletion-${UUID.randomUUID()}.db".also(databases::add)

    private class InjectedWriteFailure : RuntimeException()
}
