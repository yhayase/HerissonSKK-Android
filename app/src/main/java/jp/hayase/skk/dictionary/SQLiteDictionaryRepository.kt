package jp.hayase.skk.dictionary

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import java.util.Collections
import jp.hayase.skk.core.dictionary.CompositeSkkDictionary
import jp.hayase.skk.core.dictionary.SkkDictionaryCandidate
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.core.dictionary.SkkDictionaryDocument
import jp.hayase.skk.core.dictionary.SkkDictionaryEncoding
import jp.hayase.skk.core.dictionary.SkkDictionaryEntry
import jp.hayase.skk.core.dictionary.SkkDictionarySource

enum class DictionarySourceKind { PERSONAL, SYSTEM }

data class DictionarySourceInfo(
    val id: String,
    val name: String,
    val kind: DictionarySourceKind,
    val generation: Long,
    val enabled: Boolean,
    val order: Int,
)

/** 一回のキー検索で固定した、複合辞書へ渡す読み取り専用スナップショットです。 */
class DictionaryLookupSnapshot(
    val personal: SkkDictionarySource?,
    systems: List<SkkDictionarySource>,
) {
    val systems: List<SkkDictionarySource> = Collections.unmodifiableList(ArrayList(systems))

    fun asComposite(): CompositeSkkDictionary = CompositeSkkDictionary(personal, systems)
}

enum class DictionaryWritePoint {
    AFTER_ROWS_CHANGED,
    BEFORE_PUBLICATION,
}

fun interface DictionaryWriteFailpoint {
    fun hit(point: DictionaryWritePoint)
}

/** 実 SQLite 接続へ故障条件を設定する Android 試験専用の継ぎ目です。 */
internal fun interface DictionaryDatabaseConfigurator {
    fun configure(database: SQLiteDatabase)
}

class StaleDictionaryGenerationException(
    val sourceId: String,
    val expected: Long,
    val actual: Long,
) : IllegalStateException("辞書世代が更新されています: $sourceId (expected=$expected, actual=$actual)")

class DictionaryDatabaseCorruptionException : SQLiteException("辞書データベースが破損しています")

/**
 * Android SQLite に公開済み辞書を保存します。
 *
 * すべての公開メソッドはブロッキング I/O です。呼出側がバックグラウンドスレッドで実行し、
 * 解析・検証済みの [SkkDictionaryDocument] だけを渡します。このクラスはスレッドを生成しません。
 */
class SQLiteDictionaryRepository internal constructor(
    context: Context,
    databaseName: String,
    private val failpoint: DictionaryWriteFailpoint,
    databaseConfigurator: DictionaryDatabaseConfigurator,
) : Closeable {
    constructor(
        context: Context,
        databaseName: String = DEFAULT_DATABASE_NAME,
        failpoint: DictionaryWriteFailpoint = DictionaryWriteFailpoint { },
    ) : this(context, databaseName, failpoint, DictionaryDatabaseConfigurator { })

    private val helper = Helper(context.applicationContext, databaseName, databaseConfigurator)

    /** 同じ ID のシステム辞書だけを、新しい世代へ原子的に置き換えます。 */
    @Synchronized
    fun importSystem(
        id: String,
        name: String,
        document: SkkDictionaryDocument,
        expectedGeneration: Long? = null,
    ): DictionarySourceInfo {
        require(id.isNotBlank() && id != PERSONAL_SOURCE_ID) { "システム辞書IDが不正です" }
        require(name.isNotBlank()) { "システム辞書名は空にできません" }
        return replaceDocument(id, name, DictionarySourceKind.SYSTEM, document, expectedGeneration)
    }

    /** 指定した世代のシステム辞書だけを削除し、他の辞書と優先順は変更しません。 */
    @Synchronized
    fun removeSystem(id: String, expectedGeneration: Long): DictionarySourceInfo {
        require(id != PERSONAL_SOURCE_ID) { "個人辞書は削除できません" }
        val database = helper.writableDatabase
        return database.inTransaction {
            val source = requireSource(database, id)
            require(source.kind == DictionarySourceKind.SYSTEM) { "システム辞書ではありません" }
            checkGeneration(source, expectedGeneration)
            check(database.delete(TABLE_SOURCES, "$COLUMN_ID = ?", arrayOf(id)) == 1)
            failpoint.hit(DictionaryWritePoint.AFTER_ROWS_CHANGED)
            failpoint.hit(DictionaryWritePoint.BEFORE_PUBLICATION)
            source
        }
    }

    /** 個人辞書全体を、新しい世代へ原子的に置き換えます。 */
    @Synchronized
    fun replacePersonal(
        document: SkkDictionaryDocument,
        expectedGeneration: Long? = null,
    ): DictionarySourceInfo = replaceDocument(
        PERSONAL_SOURCE_ID,
        PERSONAL_SOURCE_NAME,
        DictionarySourceKind.PERSONAL,
        document,
        expectedGeneration,
    )

    /** 取り込み側を先頭にし、既存だけの候補を後ろへ残して個人辞書を更新します。 */
    @Synchronized
    fun mergePersonal(
        document: SkkDictionaryDocument,
        expectedGeneration: Long? = null,
    ): DictionarySourceInfo {
        val database = helper.writableDatabase
        return database.inTransaction {
            val current = requireSource(database, PERSONAL_SOURCE_ID)
            checkGeneration(current, expectedGeneration)
            val merged = mergeEntries(document.entries, readEntries(database, PERSONAL_SOURCE_ID))
            replaceRows(database, PERSONAL_SOURCE_ID, merged)
            failpoint.hit(DictionaryWritePoint.AFTER_ROWS_CHANGED)
            val published = current.copy(generation = current.generation + 1)
            updateSource(database, published)
            failpoint.hit(DictionaryWritePoint.BEFORE_PUBLICATION)
            published
        }
    }

    /** 最新の個人辞書の一見出しだけを更新し、登録・学習候補を先頭へ移します。 */
    @Synchronized
    fun promotePersonalCandidate(
        key: String,
        candidate: SkkDictionaryCandidate,
        mayWrite: () -> Boolean = { true },
    ): DictionarySourceInfo {
        val incoming = SkkDictionaryEntry(key, listOf(candidate))
        // 公開されたモデルを直接渡す経路でも、不正な辞書値を永続化しません。
        SkkDictionaryCodec.encodeUtf8(SkkDictionaryDocument(listOf(incoming), SkkDictionaryEncoding.UTF8))
        val database = helper.writableDatabase
        return database.inTransaction {
            if (!mayWrite()) throw PersonalDataPolicyRejectedException()
            val current = requireSource(database, PERSONAL_SOURCE_ID)
            val previous = readEntries(database, PERSONAL_SOURCE_ID, key)
            val merged = mergeEntries(listOf(incoming), previous)
            database.delete(TABLE_CANDIDATES,
                "$COLUMN_SOURCE_ID = ? AND $COLUMN_ENTRY_KEY = ?", arrayOf(PERSONAL_SOURCE_ID, key))
            insertRows(database, PERSONAL_SOURCE_ID, merged)
            failpoint.hit(DictionaryWritePoint.AFTER_ROWS_CHANGED)
            val published = current.copy(generation = current.generation + 1)
            updateSource(database, published)
            failpoint.hit(DictionaryWritePoint.BEFORE_PUBLICATION)
            published
        }
    }

    /** 個人辞書を UTF-8、BOM なし、LF、末尾改行ありの SKK テキストへ書き出します。 */
    @Synchronized
    fun exportPersonal(): ByteArray {
        val database = helper.readableDatabase
        val entries = database.inReadTransaction { readEntries(database, PERSONAL_SOURCE_ID) }
        return SkkDictionaryCodec.encodeUtf8(SkkDictionaryDocument(entries, SkkDictionaryEncoding.UTF8))
    }

    @Synchronized
    fun listSources(): List<DictionarySourceInfo> {
        val database = helper.readableDatabase
        return database.inReadTransaction { querySources(database) }
    }

    /** システム辞書の有効状態を保存します。個人辞書は常に有効です。 */
    @Synchronized
    fun setSourceEnabled(id: String, enabled: Boolean) {
        require(id != PERSONAL_SOURCE_ID) { "個人辞書は無効にできません" }
        val database = helper.writableDatabase
        database.inTransaction {
            val source = requireSource(database, id)
            require(source.kind == DictionarySourceKind.SYSTEM) { "システム辞書ではありません" }
            updateSource(database, source.copy(enabled = enabled))
        }
    }

    /** 全システム辞書 ID を優先順に指定し、重複や欠落を拒否します。 */
    @Synchronized
    fun setSystemOrder(ids: List<String>) {
        require(ids.distinct().size == ids.size) { "辞書順に重複があります" }
        val database = helper.writableDatabase
        database.inTransaction {
            val current = querySources(database).filter { it.kind == DictionarySourceKind.SYSTEM }
            require(ids.toSet() == current.map { it.id }.toSet()) { "辞書順には全システム辞書を一度ずつ指定します" }
            ids.forEachIndexed { order, id ->
                val values = ContentValues().apply { put(COLUMN_ORDER, order) }
                check(database.update(TABLE_SOURCES, values, "$COLUMN_ID = ?", arrayOf(id)) == 1)
            }
        }
    }

    /** 有効な辞書について [key] の行だけを索引検索し、検索開始時の世代と順を固定します。 */
    @Synchronized
    fun lookup(key: String): DictionaryLookupSnapshot {
        val database = helper.readableDatabase
        return database.inReadTransaction { readSnapshot(database, key) }
    }

    /** 起動時または公開成功後に、全有効辞書を一つの世代スナップショットとして読み込みます。 */
    @Synchronized
    fun loadSnapshot(): DictionaryLookupSnapshot {
        val database = helper.readableDatabase
        return database.inReadTransaction { readSnapshot(database, null) }
    }

    @Synchronized
    override fun close() = helper.close()

    private fun replaceDocument(
        id: String,
        name: String,
        kind: DictionarySourceKind,
        document: SkkDictionaryDocument,
        expectedGeneration: Long?,
    ): DictionarySourceInfo {
        val database = helper.writableDatabase
        return database.inTransaction {
            val existing = findSource(database, id)
            val base = if (existing != null) {
                require(existing.kind == kind) { "辞書IDの種別が一致しません" }
                checkGeneration(existing, expectedGeneration)
                existing
            } else {
                if (expectedGeneration != null) {
                    throw StaleDictionaryGenerationException(id, expectedGeneration, MISSING_GENERATION)
                }
                check(kind == DictionarySourceKind.SYSTEM)
                DictionarySourceInfo(
                    id, name, kind, 0, true, nextSystemOrder(database),
                ).also { insertSource(database, it) }
            }
            replaceRows(database, id, document.entries)
            failpoint.hit(DictionaryWritePoint.AFTER_ROWS_CHANGED)
            val published = base.copy(name = name, generation = base.generation + 1)
            updateSource(database, published)
            failpoint.hit(DictionaryWritePoint.BEFORE_PUBLICATION)
            published
        }
    }

    private fun replaceRows(database: SQLiteDatabase, sourceId: String, entries: List<SkkDictionaryEntry>) {
        database.delete(TABLE_CANDIDATES, "$COLUMN_SOURCE_ID = ?", arrayOf(sourceId))
        insertRows(database, sourceId, entries)
    }

    private fun insertRows(database: SQLiteDatabase, sourceId: String, entries: List<SkkDictionaryEntry>) {
        entries.forEach { entry ->
            entry.candidates.forEachIndexed { ordinal, candidate ->
                val values = ContentValues().apply {
                    put(COLUMN_SOURCE_ID, sourceId)
                    put(COLUMN_ENTRY_KEY, entry.key)
                    put(COLUMN_ORDINAL, ordinal)
                    put(COLUMN_TEXT, candidate.text)
                    putNullable(COLUMN_ANNOTATION, candidate.annotation?.takeIf(String::isNotEmpty))
                    putNullable(COLUMN_OKURI, candidate.okuriCondition)
                }
                check(database.insertOrThrow(TABLE_CANDIDATES, null, values) != -1L)
            }
        }
    }

    private fun readEntries(database: SQLiteDatabase, sourceId: String, key: String? = null): List<SkkDictionaryEntry> {
        val entries = linkedMapOf<String, MutableList<SkkDictionaryCandidate>>()
        database.query(
            TABLE_CANDIDATES,
            arrayOf(COLUMN_ENTRY_KEY, COLUMN_TEXT, COLUMN_ANNOTATION, COLUMN_OKURI),
            "$COLUMN_SOURCE_ID = ?" + if (key == null) "" else " AND $COLUMN_ENTRY_KEY = ?",
            if (key == null) arrayOf(sourceId) else arrayOf(sourceId, key),
            null,
            null,
            "$COLUMN_ENTRY_KEY ASC, $COLUMN_ORDINAL ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries.getOrPut(cursor.getString(0)) { mutableListOf() } += SkkDictionaryCandidate(
                    cursor.getString(1), cursor.nullableString(2), cursor.nullableString(3),
                )
            }
        }
        return entries.map { (key, candidates) -> SkkDictionaryEntry(key, candidates) }
    }

    private fun mergeEntries(
        imported: List<SkkDictionaryEntry>,
        existing: List<SkkDictionaryEntry>,
    ): List<SkkDictionaryEntry> {
        val byKey = linkedMapOf<String, LinkedHashMap<CandidateIdentity, SkkDictionaryCandidate>>()
        existing.forEach { entry ->
            byKey.getOrPut(entry.key) { linkedMapOf() }.apply {
                entry.candidates.forEach { put(CandidateIdentity(it.text, it.okuriCondition), it) }
            }
        }
        imported.forEach { entry ->
            val old = byKey[entry.key].orEmpty()
            val merged = linkedMapOf<CandidateIdentity, SkkDictionaryCandidate>()
            entry.candidates.forEach { candidate ->
                val identity = CandidateIdentity(candidate.text, candidate.okuriCondition)
                val importedAnnotation = candidate.annotation?.takeIf(String::isNotEmpty)
                merged[identity] = candidate.copy(annotation = importedAnnotation ?: old[identity]?.annotation)
            }
            old.forEach { (identity, candidate) -> merged.putIfAbsent(identity, candidate) }
            byKey[entry.key] = merged
        }
        return byKey.map { (key, candidates) -> SkkDictionaryEntry(key, candidates.values.toList()) }
    }

    private fun readSnapshot(database: SQLiteDatabase, key: String?): DictionaryLookupSnapshot {
        val sources = linkedMapOf<String, SnapshotBuilder>()
        val join = if (key == null) "LEFT JOIN" else "JOIN"
        val keyClause = if (key == null) "" else " AND c.$COLUMN_ENTRY_KEY = ?"
        database.rawQuery(
            """
            SELECT s.$COLUMN_ID, s.$COLUMN_GENERATION, s.$COLUMN_KIND,
                   c.$COLUMN_ENTRY_KEY, c.$COLUMN_ORDINAL, c.$COLUMN_TEXT,
                   c.$COLUMN_ANNOTATION, c.$COLUMN_OKURI
              FROM $TABLE_SOURCES s
              $join $TABLE_CANDIDATES c ON c.$COLUMN_SOURCE_ID = s.$COLUMN_ID$keyClause
             WHERE s.$COLUMN_ENABLED = 1
             ORDER BY s.$COLUMN_KIND ASC, s.$COLUMN_ORDER ASC,
                      c.$COLUMN_ENTRY_KEY ASC, c.$COLUMN_ORDINAL ASC
            """.trimIndent(),
            key?.let { arrayOf(it) },
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val builder = sources.getOrPut(id) {
                    SnapshotBuilder(
                        id = id,
                        generation = cursor.getLong(1),
                        personal = cursor.getInt(2) == KIND_PERSONAL,
                    )
                }
                if (!cursor.isNull(5)) {
                    builder.entries.getOrPut(cursor.getString(3)) { mutableListOf() } += SkkDictionaryCandidate(
                        text = cursor.getString(5),
                        annotation = cursor.nullableString(6),
                        okuriCondition = cursor.nullableString(7),
                    )
                }
            }
        }
        var personal: SkkDictionarySource? = null
        val systems = mutableListOf<SkkDictionarySource>()
        sources.values.forEach { source ->
            val snapshot = SkkDictionarySource(
                source.id,
                source.generation,
                source.entries.map { (entryKey, candidates) -> SkkDictionaryEntry(entryKey, candidates) },
            )
            if (source.personal) personal = snapshot else systems += snapshot
        }
        return DictionaryLookupSnapshot(personal, systems)
    }

    private fun querySources(database: SQLiteDatabase): List<DictionarySourceInfo> = buildList {
        database.query(
            TABLE_SOURCES,
            SOURCE_COLUMNS,
            null,
            null,
            null,
            null,
            "$COLUMN_KIND ASC, $COLUMN_ORDER ASC",
        ).use { cursor -> while (cursor.moveToNext()) add(cursor.sourceInfo()) }
    }

    private fun findSource(database: SQLiteDatabase, id: String): DictionarySourceInfo? =
        database.query(
            TABLE_SOURCES,
            SOURCE_COLUMNS,
            "$COLUMN_ID = ?",
            arrayOf(id),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.sourceInfo() else null }

    private fun requireSource(database: SQLiteDatabase, id: String): DictionarySourceInfo =
        requireNotNull(findSource(database, id)) { "辞書IDが存在しません: $id" }

    private fun checkGeneration(source: DictionarySourceInfo, expected: Long?) {
        if (expected != null && expected != source.generation) {
            throw StaleDictionaryGenerationException(source.id, expected, source.generation)
        }
    }

    private fun nextSystemOrder(database: SQLiteDatabase): Int = database.rawQuery(
        "SELECT COALESCE(MAX($COLUMN_ORDER), -1) + 1 FROM $TABLE_SOURCES WHERE $COLUMN_KIND = $KIND_SYSTEM",
        null,
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun insertSource(database: SQLiteDatabase, source: DictionarySourceInfo) {
        check(database.insertOrThrow(TABLE_SOURCES, null, source.values()) != -1L)
    }

    private fun updateSource(database: SQLiteDatabase, source: DictionarySourceInfo) {
        check(database.update(TABLE_SOURCES, source.values(includeId = false), "$COLUMN_ID = ?", arrayOf(source.id)) == 1)
    }

    private fun DictionarySourceInfo.values(includeId: Boolean = true) = ContentValues().apply {
        if (includeId) put(COLUMN_ID, id)
        put(COLUMN_NAME, name)
        put(COLUMN_KIND, if (kind == DictionarySourceKind.PERSONAL) KIND_PERSONAL else KIND_SYSTEM)
        put(COLUMN_GENERATION, generation)
        put(COLUMN_ENABLED, if (enabled) 1 else 0)
        put(COLUMN_ORDER, order)
    }

    private fun Cursor.sourceInfo() = DictionarySourceInfo(
        id = getString(0),
        name = getString(1),
        kind = if (getInt(2) == KIND_PERSONAL) DictionarySourceKind.PERSONAL else DictionarySourceKind.SYSTEM,
        generation = getLong(3),
        enabled = getInt(4) != 0,
        order = getInt(5),
    )

    private data class CandidateIdentity(val text: String, val okuri: String?)
    private data class SnapshotBuilder(
        val id: String,
        val generation: Long,
        val personal: Boolean,
        val entries: LinkedHashMap<String, MutableList<SkkDictionaryCandidate>> = linkedMapOf(),
    )

    private class Helper(
        context: Context,
        name: String,
        private val databaseConfigurator: DictionaryDatabaseConfigurator,
    ) : SQLiteOpenHelper(
        context,
        name,
        null,
        DATABASE_VERSION,
        PreservingCorruptionHandler,
    ) {
        override fun onConfigure(database: SQLiteDatabase) {
            database.setForeignKeyConstraintsEnabled(true)
        }

        override fun onOpen(database: SQLiteDatabase) {
            super.onOpen(database)
            databaseConfigurator.configure(database)
        }

        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE $TABLE_SOURCES (
                    $COLUMN_ID TEXT PRIMARY KEY NOT NULL,
                    $COLUMN_NAME TEXT NOT NULL,
                    $COLUMN_KIND INTEGER NOT NULL,
                    $COLUMN_GENERATION INTEGER NOT NULL,
                    $COLUMN_ENABLED INTEGER NOT NULL,
                    $COLUMN_ORDER INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE $TABLE_CANDIDATES (
                    $COLUMN_SOURCE_ID TEXT NOT NULL,
                    $COLUMN_ENTRY_KEY TEXT NOT NULL,
                    $COLUMN_ORDINAL INTEGER NOT NULL,
                    $COLUMN_TEXT TEXT NOT NULL,
                    $COLUMN_ANNOTATION TEXT,
                    $COLUMN_OKURI TEXT,
                    PRIMARY KEY ($COLUMN_SOURCE_ID, $COLUMN_ENTRY_KEY, $COLUMN_ORDINAL),
                    FOREIGN KEY ($COLUMN_SOURCE_ID) REFERENCES $TABLE_SOURCES($COLUMN_ID) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX candidates_key_lookup ON $TABLE_CANDIDATES($COLUMN_ENTRY_KEY, $COLUMN_SOURCE_ID, $COLUMN_ORDINAL)",
            )
            val personal = ContentValues().apply {
                put(COLUMN_ID, PERSONAL_SOURCE_ID)
                put(COLUMN_NAME, PERSONAL_SOURCE_NAME)
                put(COLUMN_KIND, KIND_PERSONAL)
                put(COLUMN_GENERATION, 0)
                put(COLUMN_ENABLED, 1)
                put(COLUMN_ORDER, 0)
            }
            check(database.insertOrThrow(TABLE_SOURCES, null, personal) != -1L)
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("未対応の辞書DB更新です: $oldVersion -> $newVersion")
        }
    }

    /** 既定ハンドラーの自動削除を避け、破損ファイルを復旧判断のために残します。 */
    private object PreservingCorruptionHandler : DatabaseErrorHandler {
        override fun onCorruption(dbObj: SQLiteDatabase) {
            throw DictionaryDatabaseCorruptionException()
        }
    }

    companion object {
        const val PERSONAL_SOURCE_ID = "personal"
        private const val PERSONAL_SOURCE_NAME = "個人辞書"
        private const val DEFAULT_DATABASE_NAME = "skk-dictionaries.db"
        private const val DATABASE_VERSION = 1
        private const val MISSING_GENERATION = -1L
        private const val KIND_PERSONAL = 0
        private const val KIND_SYSTEM = 1
        private const val TABLE_SOURCES = "dictionary_sources"
        private const val TABLE_CANDIDATES = "dictionary_candidates"
        private const val COLUMN_ID = "source_id"
        private const val COLUMN_NAME = "source_name"
        private const val COLUMN_KIND = "source_kind"
        private const val COLUMN_GENERATION = "generation"
        private const val COLUMN_ENABLED = "enabled"
        private const val COLUMN_ORDER = "source_order"
        private const val COLUMN_SOURCE_ID = "source_id"
        private const val COLUMN_ENTRY_KEY = "entry_key"
        private const val COLUMN_ORDINAL = "ordinal"
        private const val COLUMN_TEXT = "candidate_text"
        private const val COLUMN_ANNOTATION = "annotation"
        private const val COLUMN_OKURI = "okuri_condition"
        private val SOURCE_COLUMNS = arrayOf(
            COLUMN_ID, COLUMN_NAME, COLUMN_KIND, COLUMN_GENERATION, COLUMN_ENABLED, COLUMN_ORDER,
        )
    }
}

private fun ContentValues.putNullable(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)

private inline fun <T> SQLiteDatabase.inTransaction(block: () -> T): T {
    beginTransaction()
    return finishTransaction(block)
}

private inline fun <T> SQLiteDatabase.inReadTransaction(block: () -> T): T {
    beginTransactionNonExclusive()
    return finishTransaction(block)
}

private inline fun <T> SQLiteDatabase.finishTransaction(block: () -> T): T {
    var failure: Throwable? = null
    return try {
        block().also { setTransactionSuccessful() }
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            endTransaction()
        } catch (cleanup: Throwable) {
            // SQLITE_FULL 等の自動ロールバック後の終了失敗で、元の原因を隠しません。
            val original = failure
            if (original == null) throw cleanup
            if (original !== cleanup) original.addSuppressed(cleanup)
        }
    }
}
