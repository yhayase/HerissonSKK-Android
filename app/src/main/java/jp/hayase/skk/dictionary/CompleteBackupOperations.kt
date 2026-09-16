package jp.hayase.skk.dictionary

import java.io.Closeable
import java.io.File
import java.io.InputStream
import jp.hayase.skk.core.dictionary.BackupSummary

/** 入力欄の開始・復元通知で固定する、辞書への書込コンテキストです。 */
class DictionaryInputWriteContext internal constructor(internal val owner: Any, internal val epoch: Any)

internal class StaleDictionaryInputContextException : IllegalStateException("辞書の復元前の入力です")

enum class CompleteBackupFailure { INVALID_FORMAT, UNSUPPORTED_VERSION, LIMIT_EXCEEDED, CAPACITY, CONFLICT, IO }

sealed interface CompleteBackupResult<out T> {
    data class Applied<T>(val value: T) : CompleteBackupResult<T>
    data class SavedButNotApplied<T>(val value: T) : CompleteBackupResult<T>
    data class Failed(val reason: CompleteBackupFailure) : CompleteBackupResult<Nothing>
}

/** 検証済みの書き出しファイルです。利用終了時に専用領域のファイルを削除します。 */
class CompleteBackupExport internal constructor(
    private val file: File,
    val summary: BackupSummary,
    private val onClosed: (Closeable) -> Unit,
) : Closeable {
    private var closed = false

    @Synchronized fun openInput(): InputStream {
        check(!closed) { "バックアップは閉じています" }
        return file.inputStream()
    }

    override fun close() {
        val removed = synchronized(this) {
            if (closed) false else { closed = true; file.delete(); true }
        }
        if (removed) onClosed(this)
    }
}

/** 確認画面が所有する復元候補です。適用開始後の close は処理中のファイルを削除しません。 */
class PreparedDictionaryRestore internal constructor(
    private val owner: Any,
    private val validated: ValidatedDictionaryBackup,
    val expectedRevision: Long,
    private val onClosed: (Closeable) -> Unit,
) : Closeable {
    val summary: BackupSummary = validated.summary
    val sources: List<DictionarySourceInfo> = validated.sources.toList()
    private enum class State { READY, CLAIMED, CLOSED }
    private var state = State.READY

    @Synchronized internal fun claim(requester: Any): ValidatedDictionaryBackup? {
        if (requester !== owner || state != State.READY) return null
        state = State.CLAIMED
        return validated
    }

    override fun close() = release(onlyReady = true)

    internal fun finish() = release(onlyReady = false)

    private fun release(onlyReady: Boolean) {
        val cleanup = synchronized(this) {
            if (state == State.CLOSED || (onlyReady && state == State.CLAIMED)) false
            else { state = State.CLOSED; true }
        }
        if (cleanup) {
            try { validated.close() } finally { onClosed(this) }
        }
    }
}
