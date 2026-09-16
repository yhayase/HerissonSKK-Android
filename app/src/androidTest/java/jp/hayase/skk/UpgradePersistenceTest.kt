package jp.hayase.skk

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executor
import jp.hayase.skk.core.CandidatePageMode
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.dictionary.CandidateSuppressionKey
import jp.hayase.skk.dictionary.DictionarySourceKind
import jp.hayase.skk.dictionary.SQLiteDictionaryRepository
import jp.hayase.skk.settings.CustomizationProfile
import jp.hayase.skk.settings.CustomizationStore
import jp.hayase.skk.settings.CustomizationStoreStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 旧 APK 上では Android API だけを使い、製品クラスを解決しないシード専用試験です。 */
@RunWith(AndroidJUnit4::class)
class UpgradePersistenceSeedTest {
    @Test fun seedVersionTwoFixture() {
        val fixture = UpgradeFixture.fromArguments("seed")
        assertFalse(fixture.databaseFile.exists())
        assertFalse(fixture.customizationFile.exists())
        assertFalse(fixture.sentinelFile.exists())

        fixture.context.openOrCreateDatabase(fixture.databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("PRAGMA foreign_keys=ON")
            database.beginTransaction()
            try {
                createVersionTwoSchema(database)
                seedDictionary(database)
                database.version = 2
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
        fixture.customizationFile.writeText(customizationVersionTwoJson(), StandardCharsets.UTF_8)
        writeSentinel(fixture.sentinelFile, fixture.id)
    }

    private fun createVersionTwoSchema(database: SQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE dictionary_sources (
                source_id TEXT PRIMARY KEY NOT NULL, source_name TEXT NOT NULL, source_kind INTEGER NOT NULL,
                generation INTEGER NOT NULL, enabled INTEGER NOT NULL, source_order INTEGER NOT NULL
            )
        """.trimIndent())
        database.execSQL("""
            CREATE TABLE dictionary_candidates (
                source_id TEXT NOT NULL, entry_key TEXT NOT NULL, ordinal INTEGER NOT NULL,
                candidate_text TEXT NOT NULL, annotation TEXT, okuri_condition TEXT,
                PRIMARY KEY(source_id, entry_key, ordinal),
                FOREIGN KEY(source_id) REFERENCES dictionary_sources(source_id) ON DELETE CASCADE
            )
        """.trimIndent())
        database.execSQL("CREATE INDEX candidates_key_lookup ON dictionary_candidates(entry_key, source_id, ordinal)")
        database.execSQL("""
            CREATE TABLE candidate_suppressions (
                source_id TEXT NOT NULL, entry_key TEXT NOT NULL, candidate_text TEXT NOT NULL,
                okuri_condition TEXT NOT NULL,
                PRIMARY KEY(source_id, entry_key, candidate_text, okuri_condition)
            )
        """.trimIndent())
        database.execSQL("CREATE INDEX suppressions_key_lookup ON candidate_suppressions(entry_key, source_id)")
        database.execSQL("""
            CREATE TABLE dictionary_source_versions (
                source_id TEXT PRIMARY KEY NOT NULL, last_generation INTEGER NOT NULL
            )
        """.trimIndent())
    }

    private fun seedDictionary(database: SQLiteDatabase) {
        database.execSQL("INSERT INTO dictionary_sources VALUES(?,?,?,?,?,?)", arrayOf<Any?>("personal", "個人辞書", 0, 7, 1, 0))
        database.execSQL("INSERT INTO dictionary_sources VALUES(?,?,?,?,?,?)", arrayOf<Any?>("system-priority", "優先辞書", 1, 4, 1, 0))
        database.execSQL("INSERT INTO dictionary_sources VALUES(?,?,?,?,?,?)", arrayOf<Any?>("system-disabled", "無効辞書", 1, 2, 0, 1))

        database.execSQL("INSERT INTO dictionary_candidates VALUES(?,?,?,?,?,?)", arrayOf<Any?>("personal", "かな", 0, "個人第一", "第一注釈", null))
        database.execSQL("INSERT INTO dictionary_candidates VALUES(?,?,?,?,?,?)", arrayOf<Any?>("personal", "かな", 1, "個人第二", "第二注釈", null))
        database.execSQL("INSERT INTO dictionary_candidates VALUES(?,?,?,?,?,?)", arrayOf<Any?>("personal", "おくr", 0, "送", "送り注釈", "る"))
        database.execSQL("INSERT INTO dictionary_candidates VALUES(?,?,?,?,?,?)", arrayOf<Any?>("system-priority", "かな", 0, "システム候補", "システム注釈", null))
        database.execSQL("INSERT INTO dictionary_candidates VALUES(?,?,?,?,?,?)", arrayOf<Any?>("system-priority", "かくれ", 0, "非表示候補", null, null))
        database.execSQL("INSERT INTO dictionary_candidates VALUES(?,?,?,?,?,?)", arrayOf<Any?>("system-disabled", "かな", 0, "無効候補", null, null))

        database.execSQL("INSERT INTO candidate_suppressions VALUES(?,?,?,?)", arrayOf<Any?>("system-priority", "かくれ", "非表示候補", ""))
        database.execSQL("INSERT INTO dictionary_source_versions VALUES(?,?)", arrayOf<Any?>("personal", 7))
        database.execSQL("INSERT INTO dictionary_source_versions VALUES(?,?)", arrayOf<Any?>("system-priority", 4))
        database.execSQL("INSERT INTO dictionary_source_versions VALUES(?,?)", arrayOf<Any?>("system-disabled", 2))
        database.execSQL("INSERT INTO dictionary_source_versions VALUES(?,?)", arrayOf<Any?>("deleted-ledger", 7))
    }

    private fun customizationVersionTwoJson(): String {
        val bindings = JSONArray()
        defaultBindings().forEach { (command, gesture) ->
            bindings.put(JSONObject().apply {
                put("command", command)
                put("text", gesture.text ?: JSONObject.NULL)
                put("special", gesture.special ?: JSONObject.NULL)
                put("ctrl", gesture.ctrl)
                put("alt", gesture.alt)
                put("shift", gesture.shift)
                put("ignoreShift", gesture.ignoreShift)
            })
        }
        return JSONObject().apply {
            put("documentVersion", 2)
            put("generation", 5)
            put("profile", "STANDARD")
            put("customRules", JSONArray())
            put("punctuation", JSONObject().apply {
                put("period", "．")
                put("comma", "，")
                put("fullwidthParentheses", false)
                put("fullwidthBrackets", true)
            })
            put("candidateDisplay", JSONObject().apply {
                put("labels", "1234567")
                put("pageMode", "AUTO")
                put("fixedPageSize", 5)
            })
            put("emacsEnabled", false)
            put("keyBindings", bindings)
        }.toString()
    }

    private data class Gesture(
        val text: String? = null,
        val special: String? = null,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false,
        val ignoreShift: Boolean = false,
    )

    private fun defaultBindings(): List<Pair<String, Gesture>> = listOf(
        "KANA" to Gesture("j", ctrl = true), "CANCEL" to Gesture("g", ctrl = true),
        "HALFWIDTH" to Gesture("q", ctrl = true), "ENTER" to Gesture(special = "ENTER"),
        "TOGGLE_KANA" to Gesture("q", ignoreShift = true), "START_READING" to Gesture("Q", ignoreShift = true),
        "ABBREV" to Gesture("/", ignoreShift = true), "SUFFIX" to Gesture(">", ignoreShift = true),
        "DIRECT" to Gesture("l", ignoreShift = true), "FULLWIDTH" to Gesture("L", ignoreShift = true),
        "CONVERT" to Gesture(" ", ignoreShift = true), "PREVIOUS" to Gesture("x", ignoreShift = true),
        "DELETE_CANDIDATE" to Gesture("X", ignoreShift = true), "REGISTER" to Gesture("r", ctrl = true),
        "COMPLETE" to Gesture(special = "TAB"), "COMPLETE_BACK" to Gesture(special = "TAB", shift = true),
        "ACCEPT_COMPLETION" to Gesture(special = "RIGHT"), "EDIT_HOME" to Gesture("a", ctrl = true),
        "EDIT_END" to Gesture("e", ctrl = true), "EDIT_LEFT" to Gesture("b", ctrl = true),
        "EDIT_RIGHT" to Gesture("f", ctrl = true), "EDIT_UP" to Gesture("p", ctrl = true),
        "EDIT_DOWN" to Gesture("n", ctrl = true), "EDIT_BACKSPACE" to Gesture("h", ctrl = true),
        "EDIT_DELETE" to Gesture("d", ctrl = true), "EDIT_KILL_LINE" to Gesture("k", ctrl = true),
        "EDIT_WORD_BACKWARD" to Gesture("b", alt = true), "EDIT_WORD_FORWARD" to Gesture("f", alt = true),
    )

    private fun writeSentinel(path: File, fixtureId: UUID) {
        val random = ByteArray(64).also(SecureRandom()::nextBytes)
        val payload = SENTINEL_MAGIC + fixtureId.toString().toByteArray(StandardCharsets.US_ASCII) + random
        path.writeBytes(payload + MessageDigest.getInstance("SHA-256").digest(payload))
    }
}

/** 新 APK の製品 API で、更新前に作った独立 fixture の移行と保持を検証します。 */
@RunWith(AndroidJUnit4::class)
class UpgradePersistenceTest {
    @Test fun verifyFixtureAfterSameSignatureReplacement() {
        val fixture = UpgradeFixture.fromArguments("verify")
        assertSentinel(fixture.sentinelFile, fixture.id)

        SQLiteDictionaryRepository(fixture.context, fixture.databaseName).use { repository ->
            val sources = repository.listSources()
            assertEquals(listOf("personal", "system-priority", "system-disabled"), sources.map { it.id })
            assertEquals(listOf(7L, 4L, 2L), sources.map { it.generation })
            assertEquals(listOf(true, true, false), sources.map { it.enabled })
            assertEquals(listOf(0, 0, 1), sources.map { it.order })
            assertEquals(listOf(DictionarySourceKind.PERSONAL, DictionarySourceKind.SYSTEM, DictionarySourceKind.SYSTEM), sources.map { it.kind })

            val kana = repository.lookup("かな").asComposite().lookup(DictionaryQuery("かな"))
            assertEquals(listOf("個人第一", "個人第二", "システム候補"), kana.map { it.text })
            assertEquals(listOf("第一注釈", "第二注釈", "システム注釈"), kana.map { it.annotation })
            val okuri = repository.lookup("おくr").asComposite().lookup(DictionaryQuery("おくr", "る"))
            assertEquals(listOf("送"), okuri.map { it.text })
            assertEquals("送り注釈", okuri.single().annotation)
            assertEquals("る", okuri.single().okuriCondition)

            val hidden = repository.listCandidateSuppressions()
            assertEquals(7L, hidden.personalGeneration)
            assertEquals(
                listOf(CandidateSuppressionKey("system-priority", "かくれ", "非表示候補", null)),
                hidden.suppressions.map { it.key },
            )
            assertTrue(repository.lookup("かくれ").asComposite().lookup(DictionaryQuery("かくれ")).isEmpty())

            val restored = repository.importSystem(
                "deleted-ledger",
                "再取込辞書",
                SkkDictionaryCodec.parseText("さい /再取込候補/"),
            )
            assertEquals(8L, restored.generation)
            assertEquals(2, restored.order)
            assertEquals(
                listOf("personal", "system-priority", "system-disabled", "deleted-ledger"),
                repository.listSources().map { it.id },
            )
            assertEquals(
                listOf("再取込候補"),
                repository.lookup("さい").asComposite().lookup(DictionaryQuery("さい")).map { it.text },
            )
        }

        SQLiteDatabase.openDatabase(fixture.databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            assertEquals(3, database.version)
            database.rawQuery("SELECT revision,allow_fallback FROM dictionary_metadata WHERE singleton=1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(1, cursor.getInt(1))
            }
            database.rawQuery("SELECT last_generation FROM dictionary_source_versions WHERE source_id=?", arrayOf("deleted-ledger")).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(8L, cursor.getLong(0))
            }
        }
        assertCustomization(fixture.customizationFile)
    }

    private fun assertCustomization(path: File) {
        val raw = JSONObject(path.readText(StandardCharsets.UTF_8))
        assertEquals(2, raw.getInt("documentVersion"))
        val direct = Executor { it.run() }
        CustomizationStore(path, direct, direct).use { store ->
            store.loadAsync()
            assertTrue(store.status is CustomizationStoreStatus.Ready)
            val settings = store.snapshot
            assertEquals(5L, settings.generation)
            assertEquals(CustomizationProfile.STANDARD, settings.profile)
            assertTrue(settings.customRules.isEmpty())
            assertEquals("．", settings.punctuation.period)
            assertEquals("，", settings.punctuation.comma)
            assertFalse(settings.punctuation.fullwidthParentheses)
            assertTrue(settings.punctuation.fullwidthBrackets)
            assertEquals("1234567", settings.candidateDisplay.labels)
            assertEquals(CandidatePageMode.AUTO, settings.candidateDisplay.pageMode)
            assertEquals(5, settings.candidateDisplay.fixedPageSize)
            assertFalse(settings.emacsEnabled)
        }
    }

    private fun assertSentinel(path: File, fixtureId: UUID) {
        val bytes = path.readBytes()
        assertEquals(SENTINEL_MAGIC.size + 36 + 64 + 32, bytes.size)
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(payload), bytes.copyOfRange(bytes.size - 32, bytes.size))
        assertArrayEquals(SENTINEL_MAGIC, payload.copyOfRange(0, SENTINEL_MAGIC.size))
        assertEquals(
            fixtureId.toString(),
            String(payload, SENTINEL_MAGIC.size, 36, StandardCharsets.US_ASCII),
        )
    }
}

private class UpgradeFixture private constructor(val context: Context, val id: UUID) {
    val databaseName = "upgrade-$id.db"
    val databaseFile: File = context.getDatabasePath(databaseName)
    val customizationFile = File(context.filesDir, "upgrade-$id-customization.json")
    val sentinelFile = File(context.filesDir, "upgrade-$id-sentinel.bin")

    companion object {
        fun fromArguments(requiredPhase: String): UpgradeFixture {
            val arguments = InstrumentationRegistry.getArguments()
            require(arguments.getString("phase") == requiredPhase) { "試験 phase が不正です" }
            val raw = requireNotNull(arguments.getString("fixture_id")) { "fixture_id がありません" }
            val id = UUID.fromString(raw)
            require(id.toString() == raw) { "fixture_id は小文字の正規 UUID にします" }
            return UpgradeFixture(InstrumentationRegistry.getInstrumentation().targetContext, id)
        }
    }
}

private val SENTINEL_MAGIC = ByteBuffer.allocate(8).putLong(0x534b4b5550475231L).array()
