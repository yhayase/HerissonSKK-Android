package jp.hayase.skk.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import jp.hayase.skk.core.dictionary.*

data class RestoredSourceGeneration(val backupGeneration: Long, val generation: Long)
data class RestoreSummary(val summary: BackupSummary, val generations: Map<String, RestoredSourceGeneration>)
class StaleDictionaryRevisionException(val expected: Long, val actual: Long) :
    IllegalStateException("辞書が更新されています。もう一度復元内容を確認してください。")

/** 全件検証した専用 SQLite ファイルです。外部からパスを指定して生成できません。 */
class ValidatedDictionaryBackup private constructor(
    private val file: File,
    private val size: Long,
    private val digest: ByteArray,
    val summary: BackupSummary,
    sources: List<DictionarySourceInfo>,
) : Closeable {
    val sources: List<DictionarySourceInfo> = Collections.unmodifiableList(ArrayList(sources))
    private var closed = false

    @Synchronized internal fun <T> readVerified(block: (SQLiteDatabase) -> T): T {
        check(!closed) { "復元の準備は終了しています" }
        check(file.isFile && file.length() == size && MessageDigest.isEqual(hash(file), digest)) {
            "検証済みの復元ファイルが変更されています"
        }
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use(block)
    }

    @Synchronized override fun close() {
        closed = true
        SQLiteDatabase.deleteDatabase(file)
    }

    companion object {
        /** 入力の close まで成功した場合だけ、適用可能なハンドルを返します。 */
        fun prepare(
            context: Context,
            input: InputStream,
            codec: CompleteDictionaryBackupCodec = CompleteDictionaryBackupCodec(),
        ): ValidatedDictionaryBackup {
            var prepared: ValidatedDictionaryBackup? = null
            try {
                return input.use { stream -> prepareOpen(context, stream, codec).also { prepared = it } }
            } catch (failure: Throwable) {
                prepared?.close()
                throw failure
            }
        }

        private fun prepareOpen(context: Context, stream: InputStream, codec: CompleteDictionaryBackupCodec): ValidatedDictionaryBackup {
            val directory = File(context.cacheDir, "dictionary-restore").apply {
                check(isDirectory || mkdirs()) { "復元用の領域を作成できません" }
            }
            val file = File.createTempFile("validated-", ".db", directory)
            try {
                val sources = mutableListOf<DictionarySourceInfo>()
                val summary = SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                    db.rawQuery("PRAGMA journal_mode=DELETE", null).use { check(it.moveToFirst()) }
                    db.setForeignKeyConstraintsEnabled(true)
                    db.execSQL("CREATE TABLE sources(id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, kind INTEGER NOT NULL, generation INTEGER NOT NULL, enabled INTEGER NOT NULL, priority INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE candidates(source TEXT NOT NULL REFERENCES sources(id), entry TEXT NOT NULL, ordinal INTEGER NOT NULL, text TEXT NOT NULL, annotation TEXT, okuri TEXT NOT NULL, PRIMARY KEY(source,entry,ordinal), UNIQUE(source,entry,text,okuri))")
                    db.execSQL("CREATE TABLE suppressions(source TEXT NOT NULL, entry TEXT NOT NULL, text TEXT NOT NULL, okuri TEXT NOT NULL, PRIMARY KEY(source,entry,text,okuri))")
                    db.execSQL("CREATE TABLE versions(source TEXT PRIMARY KEY NOT NULL, generation INTEGER NOT NULL)")
                    db.beginTransaction()
                    try {
                        var recordNumber = 0L
                        val result = codec.validate(stream) { record ->
                            recordNumber++
                            try {
                                when (record) {
                                    is BackupRecord.Source -> {
                                        val kind = if (record.kind == BackupSourceKind.PERSONAL) 0 else 1
                                        db.execSQL("INSERT INTO sources VALUES(?,?,?,?,?,?)", arrayOf<Any?>(record.id, record.name, kind, record.generation, if (record.enabled) 1 else 0, record.order))
                                        sources += DictionarySourceInfo(record.id, record.name, if (kind == 0) DictionarySourceKind.PERSONAL else DictionarySourceKind.SYSTEM, record.generation, record.enabled, record.order)
                                    }
                                    is BackupRecord.Candidate -> db.execSQL("INSERT INTO candidates VALUES(?,?,?,?,?,?)", arrayOf<Any?>(record.sourceId, record.entryKey, record.ordinal, record.text, record.annotation, record.okuriCondition ?: ""))
                                    is BackupRecord.Suppression -> db.execSQL("INSERT INTO suppressions VALUES(?,?,?,?)", arrayOf<Any?>(record.sourceId, record.entryKey, record.templateText, record.okuriCondition ?: ""))
                                    is BackupRecord.SourceVersion -> db.execSQL("INSERT INTO versions VALUES(?,?)", arrayOf<Any?>(record.sourceId, record.lastGeneration))
                                    else -> Unit
                                }
                            } catch (_: SQLiteConstraintException) {
                                throw CompleteDictionaryBackupException(recordNumber, CompleteDictionaryBackupError.DUPLICATE_IDENTITY)
                            }
                        }
                        db.rawQuery("SELECT 1 FROM sources s LEFT JOIN versions v ON s.id=v.source WHERE v.source IS NULL OR v.generation<s.generation LIMIT 1", null).use {
                            if (it.moveToFirst()) throw CompleteDictionaryBackupException(recordNumber, CompleteDictionaryBackupError.INVALID_VALUE)
                        }
                        db.setTransactionSuccessful()
                        result
                    } finally { db.endTransaction() }
                }
                return ValidatedDictionaryBackup(file, file.length(), hash(file), summary, sources)
            } catch (failure: Throwable) {
                SQLiteDatabase.deleteDatabase(file)
                throw failure
            }
        }

        private fun hash(file: File): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            return digest.digest()
        }
    }
}
