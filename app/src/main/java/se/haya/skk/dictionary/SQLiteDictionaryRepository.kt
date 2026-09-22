package se.haya.skk.dictionary

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import java.io.OutputStream
import java.net.URI
import se.haya.skk.core.dictionary.BackupRecord
import se.haya.skk.core.dictionary.BackupSummary
import se.haya.skk.core.dictionary.CompleteDictionaryBackupCodec
import java.util.Collections
import se.haya.skk.core.CompletionQuery
import se.haya.skk.core.CompletionScope
import se.haya.skk.core.CompletionException
import se.haya.skk.core.CompletionFailure
import se.haya.skk.core.PredictionHistoryTarget
import se.haya.skk.core.PredictionQuery
import se.haya.skk.core.PredictionSearchFailure
import se.haya.skk.core.PredictionSearchResult
import se.haya.skk.core.rankPredictionCandidates
import se.haya.skk.core.dictionary.DictionaryUnavailableException
import se.haya.skk.core.dictionary.DictionaryUnavailableReason
import se.haya.skk.core.dictionary.CompositeSkkDictionary
import se.haya.skk.core.dictionary.SkkDictionaryCandidate
import se.haya.skk.core.dictionary.SkkDictionaryCodec
import se.haya.skk.core.dictionary.SkkDictionaryDocument
import se.haya.skk.core.dictionary.SkkDictionaryEncoding
import se.haya.skk.core.dictionary.SkkDictionaryEntry
import se.haya.skk.core.dictionary.SkkDictionarySource
import se.haya.skk.core.dictionary.SuppressedDictionaryCandidate

enum class DictionarySourceKind { PERSONAL, SYSTEM }

data class DictionarySourceInfo(
    val id: String,
    val name: String,
    val kind: DictionarySourceKind,
    val generation: Long,
    val enabled: Boolean,
    val order: Int,
    val originUrl: String? = null,
)

/** 同じ読み取り時点で固定した個人辞書書き出しと、テキストへ含めない抑止件数です。 */
class PersonalDictionaryExport(bytes: ByteArray, val excludedSuppressionCount: Int) {
    private val encoded = bytes.copyOf()
    val bytes: ByteArray get() = encoded.copyOf()

    init {
        require(excludedSuppressionCount >= 0) { "非表示候補件数が不正です" }
    }
}

internal data class DictionaryReadStats(
    val metadataReads: Long,
    val keyReads: Long,
    val prefixReads: Long,
    val fullSnapshotReads: Long,
)

private sealed interface PredictionPrefixRows<out T> {
    data class Ready<T>(val value: T) : PredictionPrefixRows<T>
    data class Limited(val failure: PredictionSearchFailure) : PredictionPrefixRows<Nothing>
}

/** 候補行を読まずに固定する公開世代です。 */
data class DictionaryMetadata(
    val sources: List<DictionarySourceInfo>,
    val revision: Long,
    val allowFallback: Boolean,
)

/** 一回のキー検索で固定した、複合辞書へ渡す読み取り専用スナップショットです。 */
class DictionaryLookupSnapshot(
    val personal: SkkDictionarySource?,
    systems: List<SkkDictionarySource>,
    suppressions: List<CandidateSuppressionInfo> = emptyList(),
    storedSourceIds: Set<String> = systems.map { it.id }.toSet() + listOfNotNull(personal?.id),
    val dictionaryRevision: Long = 0,
    val allowFallback: Boolean = true,
) {
    val storedSourceIds: Set<String> = Collections.unmodifiableSet(HashSet(storedSourceIds))
    val systems: List<SkkDictionarySource> = Collections.unmodifiableList(ArrayList(systems))
    val suppressions: List<CandidateSuppressionInfo> =
        Collections.unmodifiableList(ArrayList(suppressions))

    fun asComposite(fallbackSystems: List<SkkDictionarySource> = emptyList()): CompositeSkkDictionary =
        CompositeSkkDictionary(personal, systems + if (allowFallback) fallbackSystems.filterNot { it.id in storedSourceIds } else emptyList(), suppressions.map { suppression ->
            suppression.key.let {
                SuppressedDictionaryCandidate(it.sourceId, it.entryKey, it.templateText, it.okuriCondition)
            }
        })
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

    private var closed = false
    private var metadataReads = 0L
    private var keyReads = 0L
    private var prefixReads = 0L
    private var fullSnapshotReads = 0L
    internal val readStats: DictionaryReadStats
        @Synchronized get() = DictionaryReadStats(metadataReads, keyReads, prefixReads, fullSnapshotReads)

    /** 同じ ID のシステム辞書だけを、新しい世代へ原子的に置き換えます。 */
    @Synchronized
    fun importSystem(
        id: String,
        name: String,
        document: SkkDictionaryDocument,
        expectedGeneration: Long? = null,
        originUrl: String? = null,
    ): DictionarySourceInfo {
        require(id.isNotBlank() && id != PERSONAL_SOURCE_ID) { "システム辞書IDが不正です" }
        require(name.isNotBlank()) { "システム辞書名は空にできません" }
        require(originUrl == null || isValidStoredOrigin(originUrl)) { "辞書の取得元 URI が不正です" }
        return replaceDocument(id, name, DictionarySourceKind.SYSTEM, document, expectedGeneration, originUrl)
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
        null,
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
            val published = publishNextGeneration(database, current)
            failpoint.hit(DictionaryWritePoint.BEFORE_PUBLICATION)
            published
        }
    }

    /** 最新の個人辞書の一見出しだけを更新し、登録・学習候補を先頭へ移します。 */
    @Synchronized
    fun promotePersonalCandidate(
        key: String,
        candidate: SkkDictionaryCandidate,
        historyTarget: PredictionHistoryTarget? = null,
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
            historyTarget?.let { recordPredictionUsage(database, it) }
            failpoint.hit(DictionaryWritePoint.AFTER_ROWS_CHANGED)
            val published = publishNextGeneration(database, current)
            failpoint.hit(DictionaryWritePoint.BEFORE_PUBLICATION)
            published
        }
    }

    /** 入力先へ受理された候補の予測順位を、通常学習と同じトランザクションで更新します。 */
    private fun recordPredictionUsage(database: SQLiteDatabase, target: PredictionHistoryTarget) {
        require(target.readingKey.isNotEmpty() && target.templateText.isNotEmpty() && target.committedText.isNotEmpty())
        val current = database.rawQuery(
            "SELECT $COLUMN_USAGE_SEQUENCE FROM $TABLE_METADATA WHERE singleton=1", null,
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        val next = Math.addExact(current, 1L)
        database.execSQL(
            "UPDATE $TABLE_METADATA SET $COLUMN_USAGE_SEQUENCE=? WHERE singleton=1", arrayOf(next),
        )
        val values = ContentValues().apply {
            put(COLUMN_ENTRY_KEY, target.readingKey)
            put(COLUMN_TEMPLATE_TEXT, target.templateText)
            put(COLUMN_OKURI, target.okuriCondition ?: NO_OKURI)
            put(COLUMN_COMMITTED_TEXT, target.committedText)
            put(COLUMN_LAST_USED_SEQUENCE, next)
        }
        check(database.insertWithOnConflict(
            TABLE_USAGE, null, values, SQLiteDatabase.CONFLICT_REPLACE,
        ) != -1L)
    }

    /** 個人辞書を UTF-8、BOM なし、LF、末尾改行ありの SKK テキストへ書き出します。 */
    @Synchronized fun exportPersonal(): ByteArray = exportPersonalWithMetadata().bytes

    /** 個人候補行と、同じ読み取りトランザクションで数えた非出力の抑止件数を返します。 */
    @Synchronized
    fun exportPersonalWithMetadata(): PersonalDictionaryExport {
        val database = helper.readableDatabase
        return database.inReadTransaction {
            val entries = readEntries(database, PERSONAL_SOURCE_ID)
            PersonalDictionaryExport(
                SkkDictionaryCodec.encodeUtf8(SkkDictionaryDocument(entries, SkkDictionaryEncoding.UTF8)),
                database.rawQuery("SELECT COUNT(*) FROM $TABLE_SUPPRESSIONS", null).use { cursor ->
                    check(cursor.moveToFirst())
                    Math.toIntExact(cursor.getLong(0))
                },
            )
        }
    }

    @Synchronized
    fun listSources(): List<DictionarySourceInfo> {
        val database = helper.readableDatabase
        return database.inReadTransaction { querySources(database) }
    }

    /** 固定した表示候補の全保存由来を、一つの世代変更として削除・抑止します。 */
    @Synchronized
    fun deleteCandidate(
        request: DeleteCandidateRequest,
        approvedImmutableSources: Map<String, Long>,
        mayWrite: () -> Boolean = { true },
    ): DictionarySourceInfo {
        val immutableApprovals = approvedImmutableSources.toMap()
        require(immutableApprovals.none { (id, generation) ->
            id.isBlank() || id == PERSONAL_SOURCE_ID || generation < 0
        }) {
            "組み込み辞書の承認表が不正です"
        }
        val database = helper.writableDatabase
        return database.inTransaction {
            if (!mayWrite()) throw PersonalDataPolicyRejectedException()
            val personal = requireSource(database, PERSONAL_SOURCE_ID)
            checkGeneration(personal, request.expectedPersonalGeneration)
            validateDeletionOrigins(database, request.origins, immutableApprovals)

            request.origins.filter { it.kind == CandidateOriginKind.PERSONAL }
                .groupBy { it.entryKey }
                .forEach { (entryKey, origins) ->
                    val removed = origins.map { CandidateIdentity(it.templateText, it.okuriCondition) }.toSet()
                    val previous = readEntries(database, PERSONAL_SOURCE_ID, entryKey).singleOrNull()
                        ?: throw CandidateOriginMismatchException()
                    val retained = previous.candidates.filterNot {
                        CandidateIdentity(it.text, it.okuriCondition) in removed
                    }
                    if (previous.candidates.size - retained.size != removed.size) {
                        throw CandidateOriginMismatchException()
                    }
                    database.delete(
                        TABLE_CANDIDATES,
                        "$COLUMN_SOURCE_ID = ? AND $COLUMN_ENTRY_KEY = ?",
                        arrayOf(PERSONAL_SOURCE_ID, entryKey),
                    )
                    if (retained.isNotEmpty()) {
                        insertRows(database, PERSONAL_SOURCE_ID, listOf(SkkDictionaryEntry(entryKey, retained)))
                    }
                }

            request.origins.filter { it.kind != CandidateOriginKind.PERSONAL }.forEach { origin ->
                insertSuppression(database, origin.suppressionKey())
            }
            // 表示候補の全由来を削除・抑止した時点で、元候補に結び付く展開済み履歴も破棄します。
            request.origins.map { Triple(it.entryKey, it.templateText, it.okuriCondition) }.distinct()
                .forEach { (entryKey, templateText, okuriCondition) ->
                    database.delete(
                        TABLE_USAGE,
                        "$COLUMN_ENTRY_KEY=? AND $COLUMN_TEMPLATE_TEXT=? AND $COLUMN_OKURI=?",
                        arrayOf(entryKey, templateText, okuriCondition ?: NO_OKURI),
                    )
                }
            failpoint.hit(DictionaryWritePoint.AFTER_ROWS_CHANGED)
            val published = publishNextGeneration(database, personal)
            failpoint.hit(DictionaryWritePoint.BEFORE_PUBLICATION)
            published
        }
    }

    /** 復元画面に必要な抑止と対応する個人辞書世代を一つの読み取りで返します。 */
    @Synchronized
    fun listCandidateSuppressions(): CandidateSuppressionSnapshot {
        val database = helper.readableDatabase
        return database.inReadTransaction {
            CandidateSuppressionSnapshot(
                requireSource(database, PERSONAL_SOURCE_ID).generation,
                readSuppressions(database, null),
            )
        }
    }

    /** 完全一致する抑止一件だけを復元し、個人辞書世代を進めます。 */
    @Synchronized
    fun restoreCandidateSuppression(
        key: CandidateSuppressionKey,
        expectedPersonalGeneration: Long,
    ): DictionarySourceInfo {
        val database = helper.writableDatabase
        return database.inTransaction {
            val personal = requireSource(database, PERSONAL_SOURCE_ID)
            checkGeneration(personal, expectedPersonalGeneration)
            val deleted = database.delete(
                TABLE_SUPPRESSIONS,
                suppressionWhereClause(),
                suppressionWhereArgs(key),
            )
            if (deleted != 1) throw CandidateSuppressionMissingException()
            failpoint.hit(DictionaryWritePoint.AFTER_ROWS_CHANGED)
            val published = publishNextGeneration(database, personal)
            failpoint.hit(DictionaryWritePoint.BEFORE_PUBLICATION)
            published
        }
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

    /** 設定画面の編集を一つの DB トランザクションで確定します。失敗時は全操作を取り消します。 */
    @Synchronized
    fun applySettingsEdits(edits: List<DictionarySettingsEdit>, baselineSystems: List<DictionarySourceInfo>) {
        if (edits.isEmpty()) return
        val database = helper.writableDatabase
        database.inTransaction {
            // 個別操作の入れ子トランザクションは外側の確定まで永続化されません。
            if (edits.any { it is DictionarySettingsEdit.ImportSystem || it is DictionarySettingsEdit.RemoveSystem ||
                    it is DictionarySettingsEdit.SetEnabled || it is DictionarySettingsEdit.SetOrder }) {
                check(querySources(database).filter { it.kind == DictionarySourceKind.SYSTEM } == baselineSystems) {
                    "システム辞書は編集開始後に変更されました"
                }
            }
            var personalChanged = false
            edits.filterNot { it is DictionarySettingsEdit.SetOrder }.forEach { edit ->
                when (edit) {
                    is DictionarySettingsEdit.ImportSystem -> importSystem(
                        edit.id, edit.name, edit.document, edit.expectedGeneration, edit.originUrl)
                    is DictionarySettingsEdit.RemoveSystem -> removeSystem(edit.id, edit.expectedGeneration)
                    is DictionarySettingsEdit.ReplacePersonal -> {
                        replacePersonal(edit.document, if (personalChanged) null else edit.expectedGeneration)
                        personalChanged = true
                    }
                    is DictionarySettingsEdit.MergePersonal -> {
                        mergePersonal(edit.document, if (personalChanged) null else edit.expectedGeneration)
                        personalChanged = true
                    }
                    is DictionarySettingsEdit.SetEnabled -> setSourceEnabled(edit.id, edit.enabled)
                    is DictionarySettingsEdit.SetOrder -> Unit
                }
            }
            edits.filterIsInstance<DictionarySettingsEdit.SetOrder>().lastOrNull()?.let { setSystemOrder(it.ids) }
        }
    }

    /** 有効な辞書について [key] の行だけを索引検索し、検索開始時の世代と順を固定します。 */
    @Synchronized
    fun lookup(key: String, expectedRevision: Long? = null): DictionaryLookupSnapshot {
        check(!closed) { "辞書保管庫は閉じています" }
        keyReads++
        val database = helper.readableDatabase
        return database.inReadTransaction {
            checkReadRevision(database, expectedRevision)
            readSnapshot(database, key)
        }
    }

    /** 明示的な書き出し・検証用です。起動・学習・通常検索には使用しません。 */
    @Synchronized
    fun loadSnapshot(): DictionaryLookupSnapshot {
        check(!closed) { "辞書保管庫は閉じています" }
        fullSnapshotReads++
        val database = helper.readableDatabase
        return database.inReadTransaction { readSnapshot(database, null) }
    }

    /** 起動と変更の公開では、候補や抑止の全件を列挙しません。 */
    @Synchronized
    fun loadMetadata(): DictionaryMetadata {
        check(!closed) { "辞書保管庫は閉じています" }
        metadataReads++
        val database = helper.readableDatabase
        return database.inReadTransaction {
            DictionaryMetadata(querySources(database), readRevision(database), readAllowFallback(database))
        }
    }

    private fun checkReadRevision(database: SQLiteDatabase, expected: Long?) {
        if (expected != null && readRevision(database) != expected) {
            throw DictionaryUnavailableException(DictionaryUnavailableReason.FAILED)
        }
    }

    /** 前方一致索引の範囲を走査し、候補本文をメモリーへ読み込まずに表示可能な見出しを返します。 */
    @Synchronized
    fun complete(
        query: CompletionQuery,
        expectedRevision: Long,
        fallbackSystems: List<SkkDictionarySource> = emptyList(),
    ): List<String> {
        check(!closed) { "辞書保管庫は閉じています" }
        prefixReads++
        // コアと同じ入力検証を行います。空の辞書なので候補は読みません。
        CompositeSkkDictionary().complete(query)
        if (query.prefix.isEmpty()) return emptyList()
        val database = helper.readableDatabase
        return database.inReadTransaction {
            checkReadRevision(database, expectedRevision)
            val sources = querySources(database)
            val result = linkedSetOf<String>()
            var work = 0
            var totalChars = 0L
            fun charge() {
                if (++work > CompletionQuery.MAX_WORK_ITEMS) throw CompletionException(CompletionFailure.WORK_LIMIT)
            }
            fun eligible(key: String): Boolean = key != query.prefix &&
                (query.abbrev && key.all { it.code in 0x20..0x7e } ||
                    !query.abbrev && key.lastOrNull() !in 'a'..'z')
            fun append(key: String) {
                if (key.length > CompletionQuery.MAX_RESULT_CHARS) throw CompletionException(CompletionFailure.RESULT_LIMIT)
                if (result.add(key)) {
                    totalChars += key.length
                    if (totalChars > CompletionQuery.MAX_TOTAL_RESULT_CHARS) throw CompletionException(CompletionFailure.RESULT_LIMIT)
                }
            }
            val upper = prefixUpperBound(query.prefix)
            for (source in sources) {
                if (!source.enabled || query.scope == CompletionScope.PERSONAL_ONLY &&
                    source.kind != DictionarySourceKind.PERSONAL) continue
                var after: String? = null
                while (result.size < query.limit) {
                    val args = mutableListOf(source.id, after ?: query.prefix)
                    val upperClause = if (upper == null) "" else " AND $COLUMN_ENTRY_KEY < ?".also { args += upper }
                    // 直前の見出しを越える索引 seek により、採用済み見出しの残り候補は走査しません。
                    val key = database.rawQuery(
                        "SELECT $COLUMN_ENTRY_KEY FROM $TABLE_CANDIDATES WHERE $COLUMN_SOURCE_ID=? " +
                            "AND $COLUMN_ENTRY_KEY ${if (after == null) ">=" else ">"} ?$upperClause " +
                            "ORDER BY $COLUMN_ENTRY_KEY LIMIT 1", args.toTypedArray(),
                    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: break
                    if (!key.startsWith(query.prefix)) break
                    after = key
                    charge()
                    if (!eligible(key)) continue
                    val visible = if (source.kind == DictionarySourceKind.PERSONAL) true else {
                        database.rawQuery(
                            """SELECT NOT EXISTS (SELECT 1 FROM $TABLE_SUPPRESSIONS x
                                WHERE x.$COLUMN_SOURCE_ID=c.$COLUMN_SOURCE_ID
                                AND x.$COLUMN_ENTRY_KEY=c.$COLUMN_ENTRY_KEY
                                AND x.$COLUMN_TEXT=c.$COLUMN_TEXT
                                AND x.$COLUMN_OKURI=COALESCE(c.$COLUMN_OKURI, ''))
                                FROM $TABLE_CANDIDATES c
                                WHERE c.$COLUMN_SOURCE_ID=? AND c.$COLUMN_ENTRY_KEY=?
                                ORDER BY c.$COLUMN_ORDINAL LIMIT ${CompletionQuery.MAX_WORK_ITEMS - work + 1}""".trimIndent(),
                            arrayOf(source.id, key),
                        ).use { cursor ->
                            var found = false
                            while (cursor.moveToNext()) {
                                charge()
                                if (cursor.getInt(0) != 0) { found = true; break }
                            }
                            found
                        }
                    }
                    if (visible) append(key)
                }
                if (result.size >= query.limit) return@inReadTransaction result.toList()
            }
            if (query.scope == CompletionScope.ALL && readAllowFallback(database)) {
                val storedIds = sources.map { it.id }.toSet()
                for (source in fallbackSystems.filter { it.enabled && it.id !in storedIds }) {
                    for (key in source.completionKeys(query.prefix)) {
                        if (result.size >= query.limit) return@inReadTransaction result.toList()
                        charge()
                        if (!eligible(key)) continue
                        val suppressions = readSuppressions(database, key).map { it.key }.toSet()
                        val visible = source.candidates(key).any { candidate ->
                            charge()
                            CandidateSuppressionKey(source.id, key, candidate.text, candidate.okuriCondition) !in suppressions
                        }
                        if (visible) append(key)
                    }
                }
            }
            result.toList()
        }
    }

    /** 現在有効な候補だけを有界に読み、使用履歴を重ねた予測結果を返します。 */
    @Synchronized
    fun predict(
        query: PredictionQuery,
        expectedRevision: Long,
        fallbackSystems: List<SkkDictionarySource> = emptyList(),
    ): PredictionSearchResult {
        check(!closed) { "辞書保管庫は閉じています" }
        prefixReads++
        val database = helper.readableDatabase
        return database.inReadTransaction {
            checkReadRevision(database, expectedRevision)
            if (query.prefix.isEmpty() || query.prefix.length > PredictionQuery.MAX_PREFIX_CHARS ||
                query.limit !in 1..PredictionQuery.MAX_RESULTS ||
                query.workLimit !in 1..PredictionQuery.MAX_WORK_ITEMS ||
                query.totalResultCharsLimit !in 1..PredictionQuery.MAX_TOTAL_RESULT_CHARS
            ) return@inReadTransaction PredictionSearchResult.Indeterminate(PredictionSearchFailure.INVALID_INPUT)
            val upper = prefixUpperBound(query.prefix)
            val args = mutableListOf<String>(query.prefix)
            val upperClause = if (upper == null) "" else " AND c.$COLUMN_ENTRY_KEY < ?".also { args += upper }
            val builders = linkedMapOf<String, SnapshotBuilder>()
            val sourceInfo = querySources(database).filter { it.enabled }
            sourceInfo.forEach { source ->
                builders[source.id] = SnapshotBuilder(source.id, source.generation,
                    source.kind == DictionarySourceKind.PERSONAL)
            }
            var work = 0
            var totalChars = 0L
            database.rawQuery(
                """
                SELECT s.$COLUMN_ID,s.$COLUMN_GENERATION,s.$COLUMN_KIND,
                       c.$COLUMN_ENTRY_KEY,c.$COLUMN_TEXT,c.$COLUMN_ANNOTATION,c.$COLUMN_OKURI
                  FROM $TABLE_SOURCES s JOIN $TABLE_CANDIDATES c ON c.$COLUMN_SOURCE_ID=s.$COLUMN_ID
                 WHERE s.$COLUMN_ENABLED=1 AND c.$COLUMN_ENTRY_KEY>=?$upperClause
                 ORDER BY c.$COLUMN_ENTRY_KEY,s.$COLUMN_KIND,s.$COLUMN_ORDER,c.$COLUMN_ORDINAL
                 LIMIT ${query.workLimit + 1}
                """.trimIndent(), args.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    if (++work > query.workLimit) {
                        return@inReadTransaction PredictionSearchResult.Indeterminate(PredictionSearchFailure.WORK_LIMIT)
                    }
                    val entryKey = cursor.getString(3)
                    val text = cursor.getString(4)
                    val annotation = cursor.nullableString(5)
                    val okuri = cursor.nullableString(6)
                    totalChars += entryKey.length + text.length + (annotation?.length ?: 0) + (okuri?.length ?: 0)
                    if (totalChars > query.totalResultCharsLimit) {
                        return@inReadTransaction PredictionSearchResult.Indeterminate(PredictionSearchFailure.RESULT_LIMIT)
                    }
                    val id = cursor.getString(0)
                    builders.getValue(id).entries.getOrPut(entryKey) { mutableListOf() } +=
                        SkkDictionaryCandidate(text, annotation, okuri)
                }
            }
            val personal = builders.values.firstOrNull { it.personal }?.asSource()
            val systems = builders.values.filterNot { it.personal }.map { it.asSource() }
            val storedIds = sourceInfo.map { it.id }.toSet()
            val fallbacks = if (readAllowFallback(database)) fallbackSystems.filterNot { it.id in storedIds } else emptyList()
            val suppressions = when (val rows = readSuppressionsForPrefix(
                database, query.prefix, upper, query.workLimit, query.totalResultCharsLimit,
            )) {
                is PredictionPrefixRows.Ready -> rows.value
                is PredictionPrefixRows.Limited -> return@inReadTransaction PredictionSearchResult.Indeterminate(rows.failure)
            }
            val usages = when (val rows = readPredictionUsageForPrefix(
                database, query.prefix, upper, query.workLimit, query.totalResultCharsLimit,
            )) {
                is PredictionPrefixRows.Ready -> rows.value
                is PredictionPrefixRows.Limited -> return@inReadTransaction PredictionSearchResult.Indeterminate(rows.failure)
            }
            val composite = CompositeSkkDictionary(personal, systems + fallbacks, suppressions.map {
                SuppressedDictionaryCandidate(it.key.sourceId, it.key.entryKey, it.key.templateText, it.key.okuriCondition)
            }, usageLookup = usages::get)
            composite.predict(query)
        }
    }

    @Synchronized
    fun predictionUsage(target: PredictionHistoryTarget, expectedRevision: Long): Long? {
        val database = helper.readableDatabase
        return database.inReadTransaction {
            checkReadRevision(database, expectedRevision)
            readPredictionUsage(database, target)
        }
    }

    private fun readPredictionUsage(database: SQLiteDatabase, target: PredictionHistoryTarget): Long? =
        database.query(TABLE_USAGE, arrayOf(COLUMN_LAST_USED_SEQUENCE),
            "$COLUMN_ENTRY_KEY=? AND $COLUMN_TEMPLATE_TEXT=? AND $COLUMN_OKURI=? AND $COLUMN_COMMITTED_TEXT=?",
            arrayOf(target.readingKey, target.templateText, target.okuriCondition ?: NO_OKURI, target.committedText),
            null, null, null, "1").use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun readPredictionUsageForPrefix(
        database: SQLiteDatabase,
        prefix: String,
        upper: String?,
        workLimit: Int,
        totalCharsLimit: Int,
    ): PredictionPrefixRows<Map<PredictionHistoryTarget, Long>> {
        val args = mutableListOf(prefix)
        val upperClause = if (upper == null) "" else " AND $COLUMN_ENTRY_KEY < ?".also { args += upper }
        var work = 0
        var totalChars = 0L
        val values = linkedMapOf<PredictionHistoryTarget, Long>()
        database.rawQuery(
            "SELECT $COLUMN_ENTRY_KEY,$COLUMN_TEMPLATE_TEXT,$COLUMN_OKURI,$COLUMN_COMMITTED_TEXT,$COLUMN_LAST_USED_SEQUENCE FROM $TABLE_USAGE WHERE $COLUMN_ENTRY_KEY>=?$upperClause LIMIT ${workLimit + 1}",
            args.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (++work > workLimit) {
                    return PredictionPrefixRows.Limited(PredictionSearchFailure.WORK_LIMIT)
                }
                val readingKey = cursor.getString(0)
                val templateText = cursor.getString(1)
                val okuri = cursor.getString(2).takeIf(String::isNotEmpty)
                val committedText = cursor.getString(3)
                totalChars += readingKey.length + templateText.length + (okuri?.length ?: 0) + committedText.length
                if (totalChars > totalCharsLimit) {
                    return PredictionPrefixRows.Limited(PredictionSearchFailure.RESULT_LIMIT)
                }
                values[PredictionHistoryTarget(readingKey, templateText, okuri, committedText)] = cursor.getLong(4)
            }
        }
        return PredictionPrefixRows.Ready(values)
    }

    private fun readSuppressionsForPrefix(
        database: SQLiteDatabase,
        prefix: String,
        upper: String?,
        workLimit: Int,
        totalCharsLimit: Int,
    ): PredictionPrefixRows<List<CandidateSuppressionInfo>> {
        val args = mutableListOf(prefix)
        val upperClause = if (upper == null) "" else " AND $COLUMN_ENTRY_KEY < ?".also { args += upper }
        var work = 0
        var totalChars = 0L
        val values = mutableListOf<CandidateSuppressionInfo>()
        database.rawQuery(
            "SELECT $COLUMN_SOURCE_ID,$COLUMN_ENTRY_KEY,$COLUMN_TEXT,$COLUMN_OKURI FROM $TABLE_SUPPRESSIONS WHERE $COLUMN_ENTRY_KEY>=?$upperClause LIMIT ${workLimit + 1}",
            args.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (++work > workLimit) {
                    return PredictionPrefixRows.Limited(PredictionSearchFailure.WORK_LIMIT)
                }
                val sourceId = cursor.getString(0)
                val entryKey = cursor.getString(1)
                val templateText = cursor.getString(2)
                val okuri = cursor.getString(3).takeIf(String::isNotEmpty)
                totalChars += sourceId.length + entryKey.length + templateText.length + (okuri?.length ?: 0)
                if (totalChars > totalCharsLimit) {
                    return PredictionPrefixRows.Limited(PredictionSearchFailure.RESULT_LIMIT)
                }
                values += CandidateSuppressionInfo(CandidateSuppressionKey(sourceId, entryKey, templateText, okuri))
            }
        }
        return PredictionPrefixRows.Ready(values)
    }

    private fun prefixUpperBound(prefix: String): String? {
        val points = prefix.codePoints().toArray()
        for (index in points.indices.reversed()) {
            if (points[index] < Character.MAX_CODE_POINT) {
                points[index]++
                if (points[index] in 0xd800..0xdfff) points[index] = 0xe000
                return String(points, 0, index + 1)
            }
        }
        return null
    }

    @Synchronized fun dictionaryRevision(): Long = readRevision(helper.readableDatabase)

    private fun readRevision(database: SQLiteDatabase): Long =
        database.rawQuery("SELECT revision FROM dictionary_metadata WHERE singleton=1", null).use {
            check(it.moveToFirst()); it.getLong(0)
        }

    private fun readAllowFallback(database: SQLiteDatabase): Boolean =
        database.rawQuery("SELECT allow_fallback FROM dictionary_metadata WHERE singleton=1", null).use {
            check(it.moveToFirst()); it.getInt(0) != 0
        }

    /** 無効辞書と削除済み台帳も含め、同じ読取時点から順次出力します。 */
    @Synchronized fun writeCompleteBackup(
        output: OutputStream,
        fallbackSources: List<SkkDictionarySource>,
        producerVersion: String,
    ): BackupSummary {
        val database = helper.readableDatabase
        return database.inReadTransaction {
            val stored = querySources(database)
            val storedIds = stored.map { it.id }.toSet()
            val fallback = if (readAllowFallback(database)) fallbackSources.filterNot { it.id in storedIds } else emptyList()
            require(fallback.map { it.id }.distinct().size == fallback.size)
            val systemOrder = stored.count { it.kind == DictionarySourceKind.SYSTEM }
            val sources = stored.mapIndexed { index, source -> source.copy(order = if (source.kind == DictionarySourceKind.PERSONAL) 0 else index - 1) } +
                fallback.mapIndexed { index, source -> DictionarySourceInfo(source.id, source.id, DictionarySourceKind.SYSTEM, source.generation, source.enabled, systemOrder + index) }
            val records = sequence<BackupRecord> {
                yield(BackupRecord.Header(
                    se.haya.skk.core.dictionary.COMPLETE_DICTIONARY_BACKUP_FORMAT,
                    se.haya.skk.core.dictionary.COMPLETE_DICTIONARY_BACKUP_VERSION,
                    producerVersion,
                ))
                sources.forEach { source -> yield(BackupRecord.Source(source.id, source.name,
                    if (source.kind == DictionarySourceKind.PERSONAL) se.haya.skk.core.dictionary.BackupSourceKind.PERSONAL else se.haya.skk.core.dictionary.BackupSourceKind.SYSTEM,
                    source.generation, source.enabled, source.order, source.originUrl)) }
                var candidates = 0
                stored.forEach { source ->
                    database.rawQuery("SELECT entry_key,ordinal,candidate_text,annotation,okuri_condition FROM dictionary_candidates WHERE source_id=? ORDER BY entry_key,ordinal", arrayOf(source.id)).use { cursor ->
                        while (cursor.moveToNext()) {
                            yield(BackupRecord.Candidate(source.id, cursor.getString(0), cursor.getInt(1), cursor.getString(2), cursor.nullableString(3), cursor.nullableString(4)))
                            candidates = Math.addExact(candidates, 1)
                        }
                    }
                }
                fallback.forEach { source -> source.entriesForBackup().forEach { entry ->
                    entry.candidates.forEachIndexed { ordinal, candidate ->
                        yield(BackupRecord.Candidate(source.id, entry.key, ordinal, candidate.text, candidate.annotation?.takeIf(String::isNotEmpty), candidate.okuriCondition))
                        candidates = Math.addExact(candidates, 1)
                    }
                } }
                var suppressions = 0
                database.rawQuery("SELECT source_id,entry_key,candidate_text,okuri_condition FROM candidate_suppressions ORDER BY source_id,entry_key,candidate_text,okuri_condition", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        yield(BackupRecord.Suppression(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3).takeIf(String::isNotEmpty)))
                        suppressions = Math.addExact(suppressions, 1)
                    }
                }
                val pending = fallback.associate { it.id to it.generation }.toMutableMap()
                // 台帳は一行ずつ送り、組み込みソースの小さな集合だけを併合します。
                val fallbackIds = pending.keys.sortedWith(CODE_POINT_ORDER)
                var fallbackIndex = 0
                var versions = 0
                database.rawQuery("SELECT source_id,last_generation FROM dictionary_source_versions ORDER BY source_id", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        while (fallbackIndex < fallbackIds.size && CODE_POINT_ORDER.compare(fallbackIds[fallbackIndex], id) < 0) {
                            val next = fallbackIds[fallbackIndex++]
                            yield(BackupRecord.SourceVersion(next, pending.getValue(next))); versions++
                        }
                        val generation = maxOf(cursor.getLong(1), pending[id] ?: 0)
                        if (fallbackIndex < fallbackIds.size && fallbackIds[fallbackIndex] == id) fallbackIndex++
                        yield(BackupRecord.SourceVersion(id, generation)); versions++
                    }
                }
                while (fallbackIndex < fallbackIds.size) {
                    val next = fallbackIds[fallbackIndex++]
                    yield(BackupRecord.SourceVersion(next, pending.getValue(next))); versions++
                }
                var usages = 0
                database.rawQuery("SELECT $COLUMN_ENTRY_KEY,$COLUMN_TEMPLATE_TEXT,$COLUMN_OKURI,$COLUMN_COMMITTED_TEXT,$COLUMN_LAST_USED_SEQUENCE FROM $TABLE_USAGE ORDER BY $COLUMN_ENTRY_KEY,$COLUMN_TEMPLATE_TEXT,$COLUMN_OKURI,$COLUMN_COMMITTED_TEXT", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        yield(BackupRecord.Usage(cursor.getString(0), cursor.getString(1),
                            cursor.getString(2).takeIf(String::isNotEmpty), cursor.getString(3), cursor.getLong(4)))
                        usages = Math.addExact(usages, 1)
                    }
                }
                yield(BackupRecord.End(sources.size, candidates, suppressions, versions, usages))
            }
            CompleteDictionaryBackupCodec().write(records, output)
        }
    }

    /** 検証済みファイルの全件を、revision の一致時だけ一つのトランザクションで置換します。 */
    @Synchronized fun restoreComplete(validated: ValidatedDictionaryBackup, expectedRevision: Long): RestoreSummary =
        validated.readVerified { staging ->
            val database = helper.writableDatabase
            database.inTransaction {
                val actual = readRevision(database)
                if (actual != expectedRevision) throw StaleDictionaryRevisionException(expectedRevision, actual)
                // 削除されるソースの世代も台帳へ残します。
                querySources(database).forEach { source -> mergeLedger(database, source.id, source.generation) }
                staging.rawQuery("SELECT source,generation FROM versions", null).use { cursor ->
                    while (cursor.moveToNext()) mergeLedger(database, cursor.getString(0), cursor.getLong(1))
                }
                val generations = linkedMapOf<String, RestoredSourceGeneration>()
                validated.sources.forEach { source ->
                    val next = allocateNextGeneration(database, source)
                    generations[source.id] = RestoredSourceGeneration(source.generation, next)
                }
                database.delete(TABLE_SOURCES, null, null)
                database.delete(TABLE_SUPPRESSIONS, null, null)
                database.delete(TABLE_USAGE, null, null)
                validated.sources.forEach { source -> insertSource(database, source.copy(generation = generations.getValue(source.id).generation)) }
                staging.rawQuery("SELECT source,entry,ordinal,text,annotation,okuri FROM candidates ORDER BY source,entry,ordinal", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val values = ContentValues().apply {
                            put(COLUMN_SOURCE_ID, cursor.getString(0)); put(COLUMN_ENTRY_KEY, cursor.getString(1))
                            put(COLUMN_ORDINAL, cursor.getInt(2)); put(COLUMN_TEXT, cursor.getString(3))
                            putNullable(COLUMN_ANNOTATION, cursor.nullableString(4))
                            putNullable(COLUMN_OKURI, cursor.getString(5).takeIf(String::isNotEmpty))
                        }
                        database.insertOrThrow(TABLE_CANDIDATES, null, values)
                        failpoint.hit(DictionaryWritePoint.AFTER_ROWS_CHANGED)
                    }
                }
                staging.rawQuery("SELECT source,entry,text,okuri FROM suppressions", null).use { cursor ->
                    while (cursor.moveToNext()) insertSuppression(database, CandidateSuppressionKey(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3).takeIf(String::isNotEmpty)))
                }
                var maximumUsage = 0L
                staging.rawQuery("SELECT reading,template,okuri,committed,sequence FROM usages ORDER BY sequence", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val values = ContentValues().apply {
                            put(COLUMN_ENTRY_KEY, cursor.getString(0)); put(COLUMN_TEMPLATE_TEXT, cursor.getString(1))
                            put(COLUMN_OKURI, cursor.getString(2)); put(COLUMN_COMMITTED_TEXT, cursor.getString(3))
                            put(COLUMN_LAST_USED_SEQUENCE, cursor.getLong(4))
                        }
                        database.insertOrThrow(TABLE_USAGE, null, values)
                        maximumUsage = maxOf(maximumUsage, cursor.getLong(4))
                    }
                }
                database.execSQL("UPDATE $TABLE_METADATA SET $COLUMN_USAGE_SEQUENCE=? WHERE singleton=1", arrayOf(maximumUsage))
                database.execSQL("UPDATE dictionary_metadata SET allow_fallback=0 WHERE singleton=1")
                failpoint.hit(DictionaryWritePoint.AFTER_ROWS_CHANGED)
                failpoint.hit(DictionaryWritePoint.BEFORE_PUBLICATION)
                RestoreSummary(validated.summary, Collections.unmodifiableMap(generations))
            }
        }

    private fun mergeLedger(database: SQLiteDatabase, id: String, generation: Long) {
        database.execSQL("INSERT OR IGNORE INTO dictionary_source_versions(source_id,last_generation) VALUES(?,?)", arrayOf<Any>(id, generation))
        database.execSQL("UPDATE dictionary_source_versions SET last_generation=MAX(last_generation,?) WHERE source_id=?", arrayOf<Any>(generation, id))
    }

    @Synchronized
    override fun close() {
        closed = true
        helper.close()
    }

    private fun replaceDocument(
        id: String,
        name: String,
        kind: DictionarySourceKind,
        document: SkkDictionaryDocument,
        expectedGeneration: Long?,
        originUrl: String?,
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
                    id, name, kind, 0, true, nextSystemOrder(database), originUrl,
                ).also { insertSource(database, it) }
            }
            replaceRows(database, id, document.entries)
            failpoint.hit(DictionaryWritePoint.AFTER_ROWS_CHANGED)
            val published = publishNextGeneration(database, base.copy(name = name, originUrl = originUrl))
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

    private fun validateDeletionOrigins(
        database: SQLiteDatabase,
        origins: List<StoredCandidateOriginRef>,
        approvedImmutableSources: Map<String, Long>,
    ) {
        origins.forEach { origin ->
            when (origin.kind) {
                CandidateOriginKind.PERSONAL -> {
                    if (origin.sourceId != PERSONAL_SOURCE_ID) throw CandidateOriginMismatchException()
                    val source = requireSource(database, PERSONAL_SOURCE_ID)
                    if (source.generation != origin.sourceGeneration) throw CandidateOriginMismatchException()
                    if (!candidateExists(database, origin)) throw CandidateOriginMismatchException()
                }
                CandidateOriginKind.STORED_SYSTEM -> {
                    if (origin.sourceId == PERSONAL_SOURCE_ID) throw CandidateOriginMismatchException()
                    val source = findSource(database, origin.sourceId) ?: throw CandidateOriginMismatchException()
                    if (source.kind != DictionarySourceKind.SYSTEM || source.generation != origin.sourceGeneration) {
                        throw CandidateOriginMismatchException()
                    }
                    if (!candidateExists(database, origin)) throw CandidateOriginMismatchException()
                }
                CandidateOriginKind.IMMUTABLE_SYSTEM -> {
                    if (origin.sourceId == PERSONAL_SOURCE_ID || findSource(database, origin.sourceId) != null) {
                        throw CandidateOriginMismatchException()
                    }
                    if (approvedImmutableSources[origin.sourceId] != origin.sourceGeneration) {
                        throw CandidateOriginMismatchException()
                    }
                }
            }
        }
    }

    private fun candidateExists(database: SQLiteDatabase, origin: StoredCandidateOriginRef): Boolean {
        val okuriClause = if (origin.okuriCondition == null) "$COLUMN_OKURI IS NULL" else "$COLUMN_OKURI = ?"
        val args = mutableListOf(origin.sourceId, origin.entryKey, origin.templateText)
        origin.okuriCondition?.let(args::add)
        return database.query(
            TABLE_CANDIDATES,
            arrayOf("1"),
            "$COLUMN_SOURCE_ID = ? AND $COLUMN_ENTRY_KEY = ? AND $COLUMN_TEXT = ? AND $okuriClause",
            args.toTypedArray(),
            null,
            null,
            null,
            "1",
        ).use { it.moveToFirst() }
    }

    private fun insertSuppression(database: SQLiteDatabase, key: CandidateSuppressionKey) {
        val values = ContentValues().apply {
            put(COLUMN_SOURCE_ID, key.sourceId)
            put(COLUMN_ENTRY_KEY, key.entryKey)
            put(COLUMN_TEXT, key.templateText)
            put(COLUMN_OKURI, key.okuriCondition ?: NO_OKURI)
        }
        if (database.insertWithOnConflict(
                TABLE_SUPPRESSIONS,
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE,
            ) == -1L
        ) {
            throw CandidateOriginMismatchException()
        }
    }

    private fun readSuppressions(database: SQLiteDatabase, key: String?): List<CandidateSuppressionInfo> = buildList {
        database.query(
            TABLE_SUPPRESSIONS,
            arrayOf(COLUMN_SOURCE_ID, COLUMN_ENTRY_KEY, COLUMN_TEXT, COLUMN_OKURI),
            if (key == null) null else "$COLUMN_ENTRY_KEY = ?",
            key?.let { arrayOf(it) },
            null,
            null,
            "$COLUMN_SOURCE_ID ASC, $COLUMN_ENTRY_KEY ASC, $COLUMN_TEXT ASC, $COLUMN_OKURI ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                add(CandidateSuppressionInfo(CandidateSuppressionKey(
                    sourceId = cursor.getString(0),
                    entryKey = cursor.getString(1),
                    templateText = cursor.getString(2),
                    okuriCondition = cursor.getString(3).takeIf(String::isNotEmpty),
                )))
            }
        }
    }

    private fun StoredCandidateOriginRef.suppressionKey() = CandidateSuppressionKey(
        sourceId,
        entryKey,
        templateText,
        okuriCondition,
    )

    private fun suppressionWhereClause(): String =
        "$COLUMN_SOURCE_ID = ? AND $COLUMN_ENTRY_KEY = ? AND $COLUMN_TEXT = ? AND $COLUMN_OKURI = ?"

    private fun suppressionWhereArgs(key: CandidateSuppressionKey): Array<String> = arrayOf(
        key.sourceId,
        key.entryKey,
        key.templateText,
        key.okuriCondition ?: NO_OKURI,
    )

    private fun allocateNextGeneration(database: SQLiteDatabase, source: DictionarySourceInfo): Long {
        val ledgerGeneration = database.query(
            TABLE_SOURCE_VERSIONS,
            arrayOf(COLUMN_LAST_GENERATION),
            "$COLUMN_SOURCE_ID = ?",
            arrayOf(source.id),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        val next = Math.addExact(maxOf(ledgerGeneration, source.generation), 1L)
        val values = ContentValues().apply {
            put(COLUMN_SOURCE_ID, source.id)
            put(COLUMN_LAST_GENERATION, next)
        }
        check(database.insertWithOnConflict(
            TABLE_SOURCE_VERSIONS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        ) != -1L)
        return next
    }

    private fun publishNextGeneration(
        database: SQLiteDatabase,
        source: DictionarySourceInfo,
    ): DictionarySourceInfo = source.copy(
        generation = allocateNextGeneration(database, source),
    ).also { updateSource(database, it) }

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
        // 個人辞書に一致行がない検索でも削除用の個人世代を保持します。
        querySources(database).filter { it.enabled }.forEach { source ->
            sources[source.id] = SnapshotBuilder(source.id, source.generation, source.kind == DictionarySourceKind.PERSONAL)
        }
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
        return DictionaryLookupSnapshot(personal, systems, readSuppressions(database, key),
            querySources(database).map { it.id }.toSet(), readRevision(database), readAllowFallback(database))
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
        putNullable(COLUMN_ORIGIN_URL, originUrl)
    }

    private fun Cursor.sourceInfo() = DictionarySourceInfo(
        id = getString(0),
        name = getString(1),
        kind = if (getInt(2) == KIND_PERSONAL) DictionarySourceKind.PERSONAL else DictionarySourceKind.SYSTEM,
        generation = getLong(3),
        enabled = getInt(4) != 0,
        order = getInt(5),
        originUrl = nullableString(6),
    )

    private data class CandidateIdentity(val text: String, val okuri: String?)
    private data class SnapshotBuilder(
        val id: String,
        val generation: Long,
        val personal: Boolean,
        val entries: LinkedHashMap<String, MutableList<SkkDictionaryCandidate>> = linkedMapOf(),
    ) {
        fun asSource() = SkkDictionarySource(
            id, generation, entries.map { (key, candidates) -> SkkDictionaryEntry(key, candidates) },
        )
    }

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
                    $COLUMN_ORDER INTEGER NOT NULL,
                    $COLUMN_ORIGIN_URL TEXT
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
            createVersion2Tables(database)
            createVersion3Tables(database)
            createVersion5Tables(database)
            val personal = ContentValues().apply {
                put(COLUMN_ID, PERSONAL_SOURCE_ID)
                put(COLUMN_NAME, PERSONAL_SOURCE_NAME)
                put(COLUMN_KIND, KIND_PERSONAL)
                put(COLUMN_GENERATION, 0)
                put(COLUMN_ENABLED, 1)
                put(COLUMN_ORDER, 0)
            }
            check(database.insertOrThrow(TABLE_SOURCES, null, personal) != -1L)
            val version = ContentValues().apply {
                put(COLUMN_SOURCE_ID, PERSONAL_SOURCE_ID)
                put(COLUMN_LAST_GENERATION, 0)
            }
            check(database.insertOrThrow(TABLE_SOURCE_VERSIONS, null, version) != -1L)
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            require(oldVersion in 1..4 && newVersion == 5)
            if (oldVersion == 1) {
                createVersion2Tables(database)
                database.execSQL("INSERT INTO $TABLE_SOURCE_VERSIONS SELECT $COLUMN_ID, $COLUMN_GENERATION FROM $TABLE_SOURCES")
            }
            if (oldVersion < 3) createVersion3Tables(database)
            if (oldVersion < 4) database.execSQL("ALTER TABLE $TABLE_SOURCES ADD COLUMN $COLUMN_ORIGIN_URL TEXT")
            if (oldVersion < 5) createVersion5Tables(database)
        }

        private fun createVersion3Tables(database: SQLiteDatabase) {
            database.execSQL("CREATE TABLE $TABLE_METADATA (singleton INTEGER PRIMARY KEY CHECK(singleton=1), revision INTEGER NOT NULL, allow_fallback INTEGER NOT NULL)")
            database.execSQL("INSERT INTO $TABLE_METADATA(singleton,revision,allow_fallback) VALUES(1, 0, 1)")
        }

        private fun createVersion5Tables(database: SQLiteDatabase) {
            database.execSQL("ALTER TABLE $TABLE_METADATA ADD COLUMN $COLUMN_USAGE_SEQUENCE INTEGER NOT NULL DEFAULT 0")
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_USAGE (
                    $COLUMN_ENTRY_KEY TEXT NOT NULL,
                    $COLUMN_TEMPLATE_TEXT TEXT NOT NULL,
                    $COLUMN_OKURI TEXT NOT NULL,
                    $COLUMN_COMMITTED_TEXT TEXT NOT NULL,
                    $COLUMN_LAST_USED_SEQUENCE INTEGER NOT NULL,
                    PRIMARY KEY ($COLUMN_ENTRY_KEY,$COLUMN_TEMPLATE_TEXT,$COLUMN_OKURI,$COLUMN_COMMITTED_TEXT)
                )
            """.trimIndent())
            database.execSQL("CREATE INDEX IF NOT EXISTS prediction_usage_recency ON $TABLE_USAGE($COLUMN_LAST_USED_SEQUENCE DESC,$COLUMN_ENTRY_KEY,$COLUMN_COMMITTED_TEXT)")
        }

        private fun createVersion2Tables(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE $TABLE_SUPPRESSIONS (
                    $COLUMN_SOURCE_ID TEXT NOT NULL,
                    $COLUMN_ENTRY_KEY TEXT NOT NULL,
                    $COLUMN_TEXT TEXT NOT NULL,
                    $COLUMN_OKURI TEXT NOT NULL,
                    PRIMARY KEY ($COLUMN_SOURCE_ID, $COLUMN_ENTRY_KEY, $COLUMN_TEXT, $COLUMN_OKURI)
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX suppressions_key_lookup ON $TABLE_SUPPRESSIONS($COLUMN_ENTRY_KEY, $COLUMN_SOURCE_ID)",
            )
            database.execSQL(
                """
                CREATE TABLE $TABLE_SOURCE_VERSIONS (
                    $COLUMN_SOURCE_ID TEXT PRIMARY KEY NOT NULL,
                    $COLUMN_LAST_GENERATION INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    /** 既定ハンドラーの自動削除を避け、破損ファイルを復旧判断のために残します。 */
    private object PreservingCorruptionHandler : DatabaseErrorHandler {
        override fun onCorruption(dbObj: SQLiteDatabase) {
            throw DictionaryDatabaseCorruptionException()
        }
    }

    companion object {
        private val CODE_POINT_ORDER = Comparator<String> { left, right ->
            val a = left.codePoints().iterator()
            val b = right.codePoints().iterator()
            var result = 0
            while (a.hasNext() && b.hasNext() && result == 0) result = a.nextInt().compareTo(b.nextInt())
            if (result != 0) result else a.hasNext().compareTo(b.hasNext())
        }
        const val PERSONAL_SOURCE_ID = "personal"
        private const val PERSONAL_SOURCE_NAME = "個人辞書"
        private const val DEFAULT_DATABASE_NAME = "skk-dictionaries.db"
        private const val DATABASE_VERSION = 5
        private const val MISSING_GENERATION = -1L
        private const val KIND_PERSONAL = 0
        private const val KIND_SYSTEM = 1
        private const val TABLE_SOURCES = "dictionary_sources"
        private const val TABLE_CANDIDATES = "dictionary_candidates"
        private const val TABLE_SUPPRESSIONS = "candidate_suppressions"
        private const val TABLE_SOURCE_VERSIONS = "dictionary_source_versions"
        private const val TABLE_METADATA = "dictionary_metadata"
        private const val TABLE_USAGE = "candidate_usage"
        private const val COLUMN_ID = "source_id"
        private const val COLUMN_NAME = "source_name"
        private const val COLUMN_KIND = "source_kind"
        private const val COLUMN_GENERATION = "generation"
        private const val COLUMN_ENABLED = "enabled"
        private const val COLUMN_ORDER = "source_order"
        private const val COLUMN_ORIGIN_URL = "origin_url"
        private const val COLUMN_SOURCE_ID = "source_id"
        private const val COLUMN_ENTRY_KEY = "entry_key"
        private const val COLUMN_ORDINAL = "ordinal"
        private const val COLUMN_TEXT = "candidate_text"
        private const val COLUMN_ANNOTATION = "annotation"
        private const val COLUMN_OKURI = "okuri_condition"
        private const val COLUMN_LAST_GENERATION = "last_generation"
        private const val COLUMN_TEMPLATE_TEXT = "template_text"
        private const val COLUMN_COMMITTED_TEXT = "committed_text"
        private const val COLUMN_LAST_USED_SEQUENCE = "last_used_sequence"
        private const val COLUMN_USAGE_SEQUENCE = "usage_sequence"
        private const val NO_OKURI = ""
        private val SOURCE_COLUMNS = arrayOf(
            COLUMN_ID, COLUMN_NAME, COLUMN_KIND, COLUMN_GENERATION, COLUMN_ENABLED, COLUMN_ORDER, COLUMN_ORIGIN_URL,
        )
    }
}

private fun isValidStoredOrigin(value: String): Boolean = try {
    val uri = URI(value)
    when {
        uri.scheme.equals("https", ignoreCase = true) ->
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
        uri.scheme.equals("content", ignoreCase = true) ->
            !uri.isOpaque && !uri.rawAuthority.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
        else -> false
    }
} catch (_: Exception) {
    false
}

private fun ContentValues.putNullable(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)

private inline fun <T> SQLiteDatabase.inTransaction(block: () -> T): T {
    beginTransaction()
    return finishTransaction {
        val result = block()
        val revision = rawQuery("SELECT revision FROM dictionary_metadata WHERE singleton=1", null).use {
            check(it.moveToFirst()); it.getLong(0)
        }
        execSQL("UPDATE dictionary_metadata SET revision=? WHERE singleton=1", arrayOf(Math.addExact(revision, 1L)))
        result
    }
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
