package se.haya.skk.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.util.UUID
import se.haya.skk.core.DictionaryQuery
import se.haya.skk.core.PredictionHistoryTarget
import se.haya.skk.core.dictionary.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CompleteDictionaryRepositoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val names = mutableListOf<String>()
    private fun name() = "complete-${UUID.randomUUID()}.db".also(names::add)
    private fun document(value: String) = SkkDictionaryCodec.parseText(value)
    private fun export(repository: SQLiteDictionaryRepository, fallback: List<SkkDictionarySource> = emptyList()): ByteArray =
        ByteArrayOutputStream().also { repository.writeCompleteBackup(it, fallback, "試験") }.toByteArray()
    private fun records(bytes: ByteArray): List<BackupRecord> = buildList {
        CompleteDictionaryBackupCodec().validate(bytes.inputStream()) { add(it) }
    }
    @After fun cleanup() { names.forEach(context::deleteDatabase) }

    @Test fun `全件復元は無効辞書と台帳を保持し世代を進め組み込み補完を止める`() {
        val bytes = SQLiteDictionaryRepository(context, name()).use { source ->
            source.replacePersonal(document("かな /個人;注釈/"))
            source.importSystem("disabled", "無効辞書", document("かな /無効/"))
            source.setSourceEnabled("disabled", false)
            source.importSystem("deleted", "削除辞書", document("かな /削除/"))
            source.removeSystem("deleted", 1)
            export(source, listOf(SkkDictionarySource("builtin", 4, document("かな /組込/" ).entries)))
        }
        ValidatedDictionaryBackup.prepare(context, bytes.inputStream()).use { validated ->
            SQLiteDictionaryRepository(context, name()).use { target ->
                target.importSystem("old", "旧辞書", document("かな /旧/"))
                val before = target.dictionaryRevision()
                val first = target.restoreComplete(validated, before)
                assertEquals(before + 1, target.dictionaryRevision())
                assertEquals(5L, first.generations.getValue("builtin").generation)
                val snapshot = target.loadSnapshot()
                assertFalse(snapshot.allowFallback)
                assertTrue("disabled" in snapshot.storedSourceIds)
                assertEquals(listOf("個人", "組込"), snapshot.asComposite(listOf(SkkDictionarySource("new", 1, document("かな /追加/" ).entries))).lookup(DictionaryQuery("かな")).map { it.text })
                assertFalse(target.listSources().single { it.id == "disabled" }.enabled)
                val versions = records(export(target)).filterIsInstance<BackupRecord.SourceVersion>().associate { it.sourceId to it.lastGeneration }
                assertEquals(1L, versions["deleted"])
                assertEquals(1L, versions["old"])
                val second = target.restoreComplete(validated, target.dictionaryRevision())
                assertEquals(6L, second.generations.getValue("builtin").generation)
                assertThrows(StaleDictionaryGenerationException::class.java) { target.replacePersonal(document("かな /不正/"), first.generations.getValue("personal").generation) }
            }
        }
    }

    @Test fun `ネットワーク更新元を旧形式互換で完全バックアップし復元する`() {
        val bytes = SQLiteDictionaryRepository(context, name()).use { source ->
            source.importSystem(
                "network",
                "ネット辞書",
                document("かな /候補/"),
                originUrl = "https://example.com/SKK-JISYO.gz",
            )
            export(source)
        }
        ValidatedDictionaryBackup.prepare(context, bytes.inputStream()).use { validated ->
            assertEquals("https://example.com/SKK-JISYO.gz", validated.sources.single { it.id == "network" }.originUrl)
            SQLiteDictionaryRepository(context, name()).use { target ->
                target.restoreComplete(validated, target.dictionaryRevision())
                assertEquals(
                    "https://example.com/SKK-JISYO.gz",
                    target.listSources().single { it.id == "network" }.originUrl,
                )
            }
        }
    }

    @Test fun `無効な保存IDは検索キーに行がなくても組み込みを復活させない`() {
        SQLiteDictionaryRepository(context, name()).use { repository ->
            repository.importSystem("builtin", "保存済み", document("べつ /別/"))
            repository.setSourceEnabled("builtin", false)
            val fallback = listOf(SkkDictionarySource("builtin", 1, document("かな /組込/" ).entries))
            assertTrue(repository.lookup("かな").asComposite(fallback).lookup(DictionaryQuery("かな")).isEmpty())
            assertFalse(records(export(repository, fallback)).filterIsInstance<BackupRecord.Candidate>().any { it.text == "組込" })
        }
    }

    @Test fun `全書込操作でrevisionが進み古い確認から復元できない`() {
        SQLiteDictionaryRepository(context, name()).use { repository ->
            val bytes = export(repository)
            ValidatedDictionaryBackup.prepare(context, bytes.inputStream()).use { staged ->
                val initial = repository.dictionaryRevision()
                repository.importSystem("system", "辞書", document("かな /候補/"))
                repository.setSourceEnabled("system", false)
                repository.setSystemOrder(listOf("system"))
                assertEquals(initial + 3, repository.dictionaryRevision())
                val before = export(repository)
                assertThrows(StaleDictionaryRevisionException::class.java) { repository.restoreComplete(staged, initial) }
                assertArrayEquals(before, export(repository))
            }
        }
    }

    @Test fun `行変更中と公開直前の例外は再接続後も全件を巻き戻す`() {
        val bytes = SQLiteDictionaryRepository(context, name()).use { source ->
            source.replacePersonal(document("かな /新候補/次候補/")); export(source)
        }
        for (point in DictionaryWritePoint.entries) {
            val databaseName = name()
            var enabled = false
            val before: ByteArray
            val revision: Long
            ValidatedDictionaryBackup.prepare(context, bytes.inputStream()).use { staged ->
                SQLiteDictionaryRepository(context, databaseName, DictionaryWriteFailpoint {
                    if (enabled && it == point) error("試験用障害")
                }).use { target ->
                    target.replacePersonal(document("かな /保持/"))
                    before = export(target)
                    revision = target.dictionaryRevision()
                    enabled = true
                    assertThrows(IllegalStateException::class.java) { target.restoreComplete(staged, revision) }
                }
            }
            SQLiteDictionaryRepository(context, databaseName).use { reopened ->
                assertArrayEquals(before, export(reopened)); assertEquals(revision, reopened.dictionaryRevision())
                assertTrue(reopened.loadSnapshot().allowFallback)
            }
        }
    }

    @Test fun `候補同一性重複と入力close失敗では検証済みハンドルを作らない`() {
        val original = SQLiteDictionaryRepository(context, name()).use { repository ->
            repository.replacePersonal(document("かな /一/二/")); export(repository)
        }.toString(Charsets.UTF_8)
        assertThrows(CompleteDictionaryBackupException::class.java) {
            ValidatedDictionaryBackup.prepare(context, original.replace("\"text\":\"二\"", "\"text\":\"一\"").byteInputStream())
        }
        assertThrows(java.io.IOException::class.java) {
            ValidatedDictionaryBackup.prepare(context, object : java.io.ByteArrayInputStream(original.toByteArray()) {
                override fun close() { throw java.io.IOException("試験用close失敗") }
            })
        }
    }

    @Test fun `最大世代は復元全体を拒否して既存データを保持する`() {
        val bytes = SQLiteDictionaryRepository(context, name()).use { repository -> export(repository) }
            .toString(Charsets.UTF_8).replace("\"generation\":0", "\"generation\":${Long.MAX_VALUE}")
            .replace("\"lastGeneration\":0", "\"lastGeneration\":${Long.MAX_VALUE}").toByteArray()
        ValidatedDictionaryBackup.prepare(context, bytes.inputStream()).use { staged ->
            SQLiteDictionaryRepository(context, name()).use { target ->
                target.replacePersonal(document("かな /保持/"))
                val before = export(target)
                assertThrows(ArithmeticException::class.java) { target.restoreComplete(staged, target.dictionaryRevision()) }
                assertArrayEquals(before, export(target))
            }
        }
    }

    @Test fun `削除済みソースの孤立抑止をそのまま復元する`() {
        val sourceName = name()
        SQLiteDictionaryRepository(context, sourceName).use { it.loadSnapshot() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(sourceName).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO candidate_suppressions VALUES('gone','かな','抑止','')")
            db.execSQL("INSERT INTO dictionary_source_versions VALUES('gone',9)")
        }
        val bytes = SQLiteDictionaryRepository(context, sourceName).use { export(it) }
        ValidatedDictionaryBackup.prepare(context, bytes.inputStream()).use { staged ->
            SQLiteDictionaryRepository(context, name()).use { target ->
                target.restoreComplete(staged, target.dictionaryRevision())
                val actual = records(export(target))
                assertEquals(listOf(BackupRecord.Suppression("gone", "かな", "抑止", null)), actual.filterIsInstance<BackupRecord.Suppression>())
                assertTrue(BackupRecord.SourceVersion("gone", 9) in actual)
            }
        }
    }

    @Test fun `検証後のステージング変更を本番書込前に拒否する`() {
        SQLiteDictionaryRepository(context, name()).use { target ->
            val bytes = export(target)
            ValidatedDictionaryBackup.prepare(context, bytes.inputStream()).use { staged ->
                val field = ValidatedDictionaryBackup::class.java.getDeclaredField("file").apply { isAccessible = true }
                val file = field.get(staged) as java.io.File
                java.io.RandomAccessFile(file, "rw").use { it.seek(file.length() - 1); it.write(123) }
                assertThrows(IllegalStateException::class.java) { target.restoreComplete(staged, target.dictionaryRevision()) }
                assertArrayEquals(bytes, export(target))
            }
        }
    }

    @Test fun `実SQLite容量不足は全置換を再接続後も巻き戻す`() {
        val bytes = SQLiteDictionaryRepository(context, name()).use { source ->
            source.replacePersonal(SkkDictionaryDocument(listOf(SkkDictionaryEntry("かな", listOf(SkkDictionaryCandidate("大".repeat(100_000))))), SkkDictionaryEncoding.UTF8))
            export(source)
        }
        val targetName = name()
        val before = SQLiteDictionaryRepository(context, targetName).use { target ->
            target.replacePersonal(document("かな /保持/")); export(target)
        }
        ValidatedDictionaryBackup.prepare(context, bytes.inputStream()).use { staged ->
            SQLiteDictionaryRepository(context, targetName, DictionaryWriteFailpoint { }, DictionaryDatabaseConfigurator { db ->
                val pages = db.rawQuery("PRAGMA page_count", null).use { it.moveToFirst(); it.getLong(0) }
                db.rawQuery("PRAGMA max_page_count=$pages", null).use { check(it.moveToFirst()) }
            }).use { target ->
                assertThrows(android.database.sqlite.SQLiteFullException::class.java) { target.restoreComplete(staged, target.dictionaryRevision()) }
            }
        }
        SQLiteDictionaryRepository(context, targetName).use { target -> assertArrayEquals(before, export(target)) }
    }

    @Test fun `v2移行は候補抑止台帳と無効順序を再接続後も保持する`() {
        val databaseName = name()
        createVersion2Fixture(databaseName)

        SQLiteDictionaryRepository(context, databaseName).use { repository ->
            assertEquals(0L, repository.dictionaryRevision())
            assertTrue(repository.loadSnapshot().allowFallback)
            assertEquals(
                listOf("personal" to 0, "disabled" to 0, "ordered" to 1),
                repository.listSources().map { it.id to it.order },
            )
            assertFalse(repository.listSources().single { it.id == "disabled" }.enabled)
            assertEquals(
                listOf(CandidateSuppressionKey("disabled", "かk", "送り候補", "く")),
                repository.listCandidateSuppressions().suppressions.map { it.key },
            )
            val candidates = records(export(repository)).filterIsInstance<BackupRecord.Candidate>()
            assertTrue(BackupRecord.Candidate("ordered", "かな", 0, "通常候補", "通常注釈", null) in candidates)
            assertTrue(BackupRecord.Candidate("disabled", "かk", 0, "送り候補", "抑止注釈", "く") in candidates)
            assertTrue(BackupRecord.SourceVersion("removed", 7) in records(export(repository)))

            assertEquals(8L, repository.importSystem("removed", "再取込辞書", document("かな /再取込/")).generation)
            assertEquals(1L, repository.dictionaryRevision())
        }

        SQLiteDictionaryRepository(context, databaseName).use { repository ->
            assertEquals(1L, repository.dictionaryRevision())
            assertTrue(repository.loadSnapshot().allowFallback)
            assertFalse(repository.listSources().single { it.id == "disabled" }.enabled)
            assertEquals(listOf("disabled", "ordered", "removed"), repository.listSources()
                .filter { it.kind == DictionarySourceKind.SYSTEM }.map { it.id })
            assertEquals(8L, records(export(repository)).filterIsInstance<BackupRecord.SourceVersion>()
                .single { it.sourceId == "removed" }.lastGeneration)
        }
    }

    private fun createVersion2Fixture(databaseName: String) {
        SQLiteDictionaryRepository(context, databaseName).use { repository ->
            repository.importSystem("ordered", "順序付き辞書", SkkDictionaryDocument(listOf(
                SkkDictionaryEntry("かな", listOf(
                    SkkDictionaryCandidate("通常候補", "通常注釈"),
                )),
            ), SkkDictionaryEncoding.UTF8))
            repository.importSystem("disabled", "無効辞書", SkkDictionaryDocument(listOf(
                SkkDictionaryEntry("かk", listOf(SkkDictionaryCandidate("送り候補", "抑止注釈", "く"))),
            ), SkkDictionaryEncoding.UTF8))
            repository.setSourceEnabled("disabled", false)
            repository.setSystemOrder(listOf("disabled", "ordered"))
            repository.importSystem("removed", "削除済み辞書", document("かな /削除候補/"))
            repository.removeSystem("removed", 1)
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO candidate_suppressions VALUES('disabled','かk','送り候補','く')")
            db.execSQL("UPDATE dictionary_source_versions SET last_generation=7 WHERE source_id='removed'")
            db.execSQL("DROP TABLE dictionary_metadata")
            rebuildLegacySources(db)
            db.version = 2
        }
    }

    @Test fun `v1移行は候補と世代を保持する`() {
        val databaseName = name()
        SQLiteDictionaryRepository(context, databaseName).use { it.replacePersonal(document("かな /保持/")) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE dictionary_metadata")
            db.execSQL("DROP TABLE candidate_suppressions")
            db.execSQL("DROP TABLE dictionary_source_versions")
            rebuildLegacySources(db)
            db.version = 1
        }
        SQLiteDictionaryRepository(context, databaseName).use { repository ->
            assertEquals(0L, repository.dictionaryRevision())
            assertEquals(1L, repository.listSources().single().generation)
            assertEquals("保持", repository.lookup("かな").asComposite().lookup(DictionaryQuery("かな")).single().text)
            assertTrue(repository.loadSnapshot().allowFallback)
        }
    }
    /** 旧SQLiteでも使えるDDLで、移行前の実際の6列構成を再現します。 */
    private fun rebuildLegacySources(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(false)
        db.execSQL("CREATE TABLE legacy_sources (source_id TEXT PRIMARY KEY NOT NULL, source_name TEXT NOT NULL, " +
            "source_kind INTEGER NOT NULL, generation INTEGER NOT NULL, enabled INTEGER NOT NULL, source_order INTEGER NOT NULL)")
        db.execSQL("INSERT INTO legacy_sources SELECT source_id,source_name,source_kind,generation,enabled,source_order FROM dictionary_sources")
        db.execSQL("DROP TABLE dictionary_sources")
        db.execSQL("ALTER TABLE legacy_sources RENAME TO dictionary_sources")
        db.rawQuery("PRAGMA table_info(dictionary_sources)", null).use { assertEquals(6, it.count) }
        db.rawQuery("PRAGMA foreign_key_check", null).use { assertEquals(0, it.count) }
    }

    @Test fun `v3完全バックアップは候補使用履歴を置換復元し旧履歴を残さない`() {
        val restoredTarget = PredictionHistoryTarget("だい#", "第#0", null, "第12")
        val bytes = SQLiteDictionaryRepository(context, name()).use { source ->
            source.promotePersonalCandidate("だい#", SkkDictionaryCandidate("第#0"), restoredTarget)
            export(source)
        }
        ValidatedDictionaryBackup.prepare(context, bytes.inputStream()).use { validated ->
            SQLiteDictionaryRepository(context, name()).use { target ->
                val oldTarget = PredictionHistoryTarget("ふるい", "古い", null, "古い")
                target.promotePersonalCandidate("ふるい", SkkDictionaryCandidate("古い"), oldTarget)
                target.restoreComplete(validated, target.dictionaryRevision())
                assertTrue(checkNotNull(target.predictionUsage(restoredTarget, target.dictionaryRevision())) > 0)
                assertEquals(null, target.predictionUsage(oldTarget, target.dictionaryRevision()))
                assertEquals(1, records(export(target)).filterIsInstance<BackupRecord.Usage>().size)
            }
        }
    }
}
