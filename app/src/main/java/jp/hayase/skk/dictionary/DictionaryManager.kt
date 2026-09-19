package jp.hayase.skk.dictionary

import jp.hayase.skk.core.DictionaryCandidate
import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.IOException
import jp.hayase.skk.core.dictionary.CompleteDictionaryBackupCodec
import jp.hayase.skk.core.dictionary.CompleteDictionaryBackupError
import jp.hayase.skk.core.dictionary.CompleteDictionaryBackupException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.BasicSkkDictionary
import jp.hayase.skk.core.dictionary.DictionaryUnavailableException
import jp.hayase.skk.core.dictionary.DictionaryUnavailableReason
import jp.hayase.skk.core.dictionary.SkkDictionaryDocument
import jp.hayase.skk.core.dictionary.SkkDictionarySource
import jp.hayase.skk.core.dictionary.SkkDictionaryCandidate
import jp.hayase.skk.core.dictionary.CandidateSelection
import jp.hayase.skk.core.numeric.NumericSkkDictionary

/** 公開済み辞書と再読込の状態です。 */
sealed interface DictionaryManagerStatus {
    data object Loading : DictionaryManagerStatus
    data class Ready(val freshness: DictionaryFreshness = DictionaryFreshness.CURRENT) : DictionaryManagerStatus
    data class Unavailable(val reason: DictionaryUnavailableReason) : DictionaryManagerStatus
}

enum class DictionaryFreshness { CURRENT, REFRESHING, STALE }

/** 保存操作の結果です。保存済みでも新世代をメモリーへ公開できない場合を区別します。 */
sealed interface DictionaryManagerWriteResult<out T> {
    data class Applied<T>(val value: T) : DictionaryManagerWriteResult<T>
    data class SavedButNotApplied<T>(val value: T) : DictionaryManagerWriteResult<T>
    data object Failed : DictionaryManagerWriteResult<Nothing>
}

/** 状態通知の解除用ハンドルです。 */
fun interface DictionaryManagerSubscription : Closeable {
    override fun close()
}

/**
 * SQLite を正本とし、公開世代のメタデータと直近の検索結果だけを保持します。
 * [lookup] のキャッシュミスは非同期読込要求です。呼出側はバックグラウンドで読み込み後に再実行します。
 */
class DictionaryManager(
    private val repository: SQLiteDictionaryRepository,
    private val serialExecutor: Executor,
    private val callbackExecutor: Executor,
    fallbackSystems: List<SkkDictionarySource> = emptyList(),
    private val loadSnapshot: (() -> DictionaryLookupSnapshot)? = null,
    private val loadMetadata: () -> DictionaryMetadata = repository::loadMetadata,
    internal val deferReads: Boolean = true,
    private val ownedExecutor: ExecutorService? = null,
    val personalDataPolicy: PersonalDataPolicy = PersonalDataPolicy(),
) : Closeable, BasicSkkDictionary {
    private data class Published(
        val dictionary: BasicSkkDictionary?,
        val status: DictionaryManagerStatus,
        val immutableApprovals: Map<String, Long> = emptyMap(),
        val inputEpoch: Any = Any(),
        val inputWritesBlocked: Boolean = false,
        val sqlite: SQLiteSkkDictionary? = null,
    )

    private data class PendingPromotion(
        val key: String,
        val candidate: SkkDictionaryCandidate,
        val context: DictionaryInputWriteContext,
        val permit: PersonalDataPolicy.Permit,
    )
    private val pendingPromotions = linkedMapOf<Any, PendingPromotion>()

    private val observers = linkedMapOf<Long, (DictionaryManagerStatus) -> Unit>()
    private val fallbackSystems = fallbackSystems.toList()
    private val contextOwner = Any()
    private val backupHandles = mutableSetOf<Closeable>()
    private val settingsDrafts = linkedMapOf<String, DictionarySettingsDraft>()
    private val applyingSettingsDrafts = mutableSetOf<String>()
    private val settingsDraftCallbacks = mutableMapOf<String, MutableList<(DictionaryManagerWriteResult<Unit>) -> Unit>>()
    private val completedSettingsDrafts = linkedMapOf<String, DictionaryManagerWriteResult<Unit>>()
    private var nextObserverId = 0L
    @Volatile
    private var closed = false

    @Volatile
    private var published = Published(null, DictionaryManagerStatus.Loading)

    /** 現在の状態です。検索可能な古い世代がある再読込失敗は [DictionaryFreshness.STALE] で表します。 */
    val status: DictionaryManagerStatus get() = published.status

    /** 公開世代の検索結果へ保存待ちの順位を重ねます。 */
    override fun lookup(query: DictionaryQuery): List<DictionaryCandidate> = readForCaller {
        val candidates = currentDictionary().lookup(query).toMutableList()
        synchronized(this) {
            pendingPromotions.entries.removeAll { (_, pending) ->
                !acceptsInputWrite(pending.context) || !personalDataPolicy.accepts(pending.permit)
            }
            // 保存待ちの学習は既存候補の順位だけに反映し、保存由来や削除用の世代を作りません。
            // 数値変換では表示文字列でなく学習先のテンプレートを照合します。
            for (pending in pendingPromotions.values) {
                val (promoted, remaining) = candidates.partition { candidate ->
                    val target = candidate.learningTarget
                    val key = target?.query?.readingKey ?: query.readingKey
                    val text = target?.templateText ?: candidate.text
                    val condition = target?.query?.okuri ?: query.okuri ?: candidate.okuriCondition
                    key == pending.key && text == pending.candidate.text &&
                        condition == pending.candidate.okuriCondition
                }
                if (promoted.isNotEmpty()) {
                    candidates.clear()
                    candidates.addAll(promoted)
                    candidates.addAll(remaining)
                }
            }
        }
        candidates
    }

    override fun complete(query: jp.hayase.skk.core.CompletionQuery): List<String> =
        readForCaller { currentDictionary().complete(query) }

    override fun registrationQuery(original: DictionaryQuery): DictionaryQuery =
        currentDictionary().registrationQuery(original)

    override fun prepareRegistration(original: DictionaryQuery, templateText: String) =
        readForCaller { currentDictionary().prepareRegistration(original, templateText) }

    private fun currentDictionary(): BasicSkkDictionary {
        val current = published
        return current.dictionary ?: throw DictionaryUnavailableException(
            (current.status as? DictionaryManagerStatus.Unavailable)?.reason
                ?: DictionaryUnavailableReason.INITIALIZING,
        )
    }

    /** 起動時または明示的な再読込時にメタデータだけを読み込みます。 */
    @Synchronized
    fun loadAsync(callback: ((DictionaryManagerStatus) -> Unit)? = null) {
        check(!closed) { "辞書管理器は閉じています" }
        val current = published
        publish(
            if (current.dictionary == null) current.copy(status = DictionaryManagerStatus.Loading)
            else current.copy(status = DictionaryManagerStatus.Ready(DictionaryFreshness.REFRESHING)),
        )
        serialExecutor.execute {
            val loaded = runCatching { buildPublished() }
            if (loaded.isSuccess) {
                publish(loaded.getOrThrow())
            } else {
                publishRefreshFailure()
            }
            callback?.let { deliver(it, published.status) }
        }
    }

    /** 状態を通知し、返したハンドルを閉じると以後の通知を止めます。 */
    @Synchronized
    fun observe(observer: (DictionaryManagerStatus) -> Unit): DictionaryManagerSubscription {
        check(!closed) { "辞書管理器は閉じています" }
        val id = nextObserverId++
        observers[id] = observer
        deliverObserver(id, published.status)
        return DictionaryManagerSubscription { synchronized(this) { observers.remove(id) } }
    }

    fun importSystem(
        id: String,
        name: String,
        document: SkkDictionaryDocument,
        expectedGeneration: Long? = null,
        callback: (DictionaryManagerWriteResult<DictionarySourceInfo>) -> Unit,
    ) = writeThenReload(callback) { repository.importSystem(id, name, document, expectedGeneration) }

    fun removeSystem(
        id: String,
        expectedGeneration: Long,
        callback: (DictionaryManagerWriteResult<DictionarySourceInfo>) -> Unit,
    ) = writeThenReload(callback) { repository.removeSystem(id, expectedGeneration) }

    fun replacePersonal(
        document: SkkDictionaryDocument,
        expectedGeneration: Long? = null,
        callback: (DictionaryManagerWriteResult<DictionarySourceInfo>) -> Unit,
    ) = writeThenReload(callback) { repository.replacePersonal(document, expectedGeneration) }

    fun mergePersonal(
        document: SkkDictionaryDocument,
        expectedGeneration: Long? = null,
        callback: (DictionaryManagerWriteResult<DictionarySourceInfo>) -> Unit,
    ) = writeThenReload(callback) { repository.mergePersonal(document, expectedGeneration) }

    fun savePersonalCandidate(
        key: String,
        candidate: SkkDictionaryCandidate,
        originAllowsSaving: Boolean,
        callback: (PersonalWriteResult) -> Unit,
    ) = savePersonalCandidate(key, candidate, originAllowsSaving, captureInputWriteContext(), callback)

    /** 入力由来の登録・学習だけを対象に、受理時と実際の書込直前に方針を確認します。 */
    @Synchronized
    fun savePersonalCandidate(
        key: String,
        candidate: SkkDictionaryCandidate,
        originAllowsSaving: Boolean,
        inputContext: DictionaryInputWriteContext,
        callback: (PersonalWriteResult) -> Unit,
    ) {
        check(!closed) { "辞書管理器は閉じています" }
        val permit = personalDataPolicy.request(originAllowsSaving)
        if (permit == null) {
            deliver(callback, PersonalWriteResult.Failed(PersonalWriteFailure.POLICY_REJECTED))
            return
        }
        if (!acceptsInputWrite(inputContext)) {
            deliver(callback, PersonalWriteResult.Failed(PersonalWriteFailure.CONFLICT))
            return
        }
        val promotionId = Any()
        pendingPromotions[promotionId] = PendingPromotion(key, candidate, inputContext, permit)
        if (pendingPromotions.size > 128) pendingPromotions.remove(pendingPromotions.keys.first())
        serialExecutor.execute {
            val saved = runCatching {
                requireInputWrite(inputContext)
                repository.promotePersonalCandidate(key, candidate) {
                    requireInputWrite(inputContext)
                    personalDataPolicy.accepts(permit)
                }
            }
            val error = saved.exceptionOrNull()
            if (error != null) {
                val reason = when (error) {
                    is SQLiteFullException -> PersonalWriteFailure.CAPACITY
                    is StaleDictionaryGenerationException,
                    is StaleDictionaryInputContextException -> PersonalWriteFailure.CONFLICT
                    is PersonalDataPolicyRejectedException -> PersonalWriteFailure.POLICY_REJECTED
                    else -> PersonalWriteFailure.GENERAL
                }
                synchronized(this) { pendingPromotions.remove(promotionId) }
                deliver(callback, PersonalWriteResult.Failed(reason))
                return@execute
            }
            val loaded = runCatching { buildPublished() }
            synchronized(this) {
                pendingPromotions.remove(promotionId)
                if (loaded.isSuccess) publish(loaded.getOrThrow()) else publishRefreshFailure()
            }
            deliver(callback, if (loaded.isSuccess) PersonalWriteResult.Applied else PersonalWriteResult.SavedButNotApplied)
        }
    }

    fun deleteCandidate(
        request: DeleteCandidateRequest,
        originAllowsSaving: Boolean,
        callback: (PersonalWriteResult) -> Unit,
    ) = deleteCandidate(request, originAllowsSaving, captureInputWriteContext(), callback)

    /** 入力中の候補削除を、登録と同じ個人データ方針と直列キューで実行します。 */
    @Synchronized
    fun deleteCandidate(
        request: DeleteCandidateRequest,
        originAllowsSaving: Boolean,
        inputContext: DictionaryInputWriteContext,
        callback: (PersonalWriteResult) -> Unit,
    ) {
        check(!closed) { "辞書管理器は閉じています" }
        val permit = personalDataPolicy.request(originAllowsSaving)
        if (permit == null) {
            deliver(callback, PersonalWriteResult.Failed(PersonalWriteFailure.POLICY_REJECTED))
            return
        }
        if (!acceptsInputWrite(inputContext)) {
            deliver(callback, PersonalWriteResult.Failed(PersonalWriteFailure.CONFLICT))
            return
        }
        val approvals = published.immutableApprovals
        serialExecutor.execute {
            val saved = runCatching {
                requireInputWrite(inputContext)
                repository.deleteCandidate(request, approvals) {
                    requireInputWrite(inputContext)
                    personalDataPolicy.accepts(permit)
                }
            }
            val error = saved.exceptionOrNull()
            if (error != null) {
                deliver(callback, PersonalWriteResult.Failed(personalWriteFailure(error)))
                return@execute
            }
            val loaded = runCatching { buildPublished() }
            if (loaded.isSuccess) {
                publish(loaded.getOrThrow())
                deliver(callback, PersonalWriteResult.Applied)
            } else {
                publishRefreshFailure()
                deliver(callback, PersonalWriteResult.SavedButNotApplied)
            }
        }
    }

    fun deleteSelection(
        selection: CandidateSelection,
        originAllowsSaving: Boolean,
        callback: (PersonalWriteResult) -> Unit,
    ) = deleteSelection(selection, originAllowsSaving, captureInputWriteContext(), callback)

    /** コアが固定した候補由来を、管理器が保持する組み込み辞書承認表で削除要求へ変換します。 */
    @Synchronized
    fun deleteSelection(
        selection: CandidateSelection,
        originAllowsSaving: Boolean,
        inputContext: DictionaryInputWriteContext,
        callback: (PersonalWriteResult) -> Unit,
    ) = deleteCandidate(
        DeleteCandidateRequest(
            selection.personalGeneration,
            selection.origins.map { origin ->
                StoredCandidateOriginRef(
                    sourceId = origin.dictionaryId,
                    sourceGeneration = origin.generation,
                    kind = when {
                        origin.personal -> CandidateOriginKind.PERSONAL
                        origin.dictionaryId in published.immutableApprovals -> CandidateOriginKind.IMMUTABLE_SYSTEM
                        else -> CandidateOriginKind.STORED_SYSTEM
                    },
                    entryKey = origin.entryKey,
                    templateText = origin.text,
                    okuriCondition = origin.okuriCondition,
                )
            },
        ),
        originAllowsSaving,
        inputContext,
        callback,
    )

    /** 抑止行と、その行を取得した個人辞書世代をバックグラウンドで返します。 */
    @Synchronized
    fun listSuppressions(callback: (DictionaryManagerWriteResult<CandidateSuppressionSnapshot>) -> Unit) {
        check(!closed) { "辞書管理器は閉じています" }
        serialExecutor.execute {
            val result = runCatching { repository.listCandidateSuppressions() }
            deliver(callback, result.fold(
                onSuccess = { DictionaryManagerWriteResult.Applied(it) },
                onFailure = { DictionaryManagerWriteResult.Failed },
            ))
        }
    }

    /** 設定画面から一件の抑止だけを復元します。入力由来の保存方針は適用しません。 */
    fun restoreCandidateSuppression(
        key: CandidateSuppressionKey,
        expectedGeneration: Long,
        callback: (DictionaryManagerWriteResult<DictionarySourceInfo>) -> Unit,
    ) = writeThenReload(callback) { repository.restoreCandidateSuppression(key, expectedGeneration) }

    fun setSourceEnabled(id: String, enabled: Boolean, callback: (DictionaryManagerWriteResult<Unit>) -> Unit) =
        writeThenReload(callback) { repository.setSourceEnabled(id, enabled) }

    fun setSystemOrder(ids: List<String>, callback: (DictionaryManagerWriteResult<Unit>) -> Unit) {
        val requested = ids.toList()
        writeThenReload(callback) { repository.setSystemOrder(requested) }
    }

    /** 編集中の文書を画面再生成中だけ保持します。DB と公開済み辞書は変更しません。 */
    @Synchronized
    fun createSettingsDraft(sources: List<DictionarySourceInfo>): DictionarySettingsDraft {
        check(!closed)
        return DictionarySettingsDraft(sources).also { settingsDrafts[it.id] = it }
    }

    @Synchronized
    fun settingsDraft(id: String): DictionarySettingsDraft? = settingsDrafts[id]

    @Synchronized
    fun isSettingsDraftApplying(id: String): Boolean = id in applyingSettingsDrafts

    @Synchronized
    fun consumeSettingsDraftCompletion(id: String): DictionaryManagerWriteResult<Unit>? = completedSettingsDrafts.remove(id)

    @Synchronized
    fun discardSettingsDraft(id: String) {
        settingsDrafts.remove(id)
    }

    /** 一回の保存と一回のメタデータ公開を直列化します。公開失敗でも保存済み編集は再実行しません。 */
    @Synchronized
    fun applySettingsDraft(id: String, callback: (DictionaryManagerWriteResult<Unit>) -> Unit) {
        check(!closed)
        completedSettingsDrafts[id]?.let { result ->
            deliver(callback, result)
            return
        }
        val draft = settingsDrafts[id] ?: run {
            deliver(callback, DictionaryManagerWriteResult.Failed)
            return
        }
        settingsDraftCallbacks.getOrPut(id) { mutableListOf() } += callback
        if (!applyingSettingsDrafts.add(id)) return
        val edits = draft.snapshot()
        if (edits.isEmpty()) {
            finishSettingsDraftCallbacks(id, DictionaryManagerWriteResult.Applied(Unit))
            return
        }
        serialExecutor.execute {
            if (runCatching { repository.applySettingsEdits(edits, draft.baselineSystems) }.isFailure) {
                finishSettingsDraftCallbacks(id, DictionaryManagerWriteResult.Failed)
                return@execute
            }
            val loaded = runCatching { buildPublished() }
            if (loaded.isSuccess) {
                publish(loaded.getOrThrow())
                finishSettingsDraftCallbacks(id, DictionaryManagerWriteResult.Applied(Unit))
            } else {
                publishRefreshFailure()
                finishSettingsDraftCallbacks(id, DictionaryManagerWriteResult.SavedButNotApplied(Unit))
            }
        }
    }

    private fun finishSettingsDraftCallbacks(id: String, result: DictionaryManagerWriteResult<Unit>) {
        val callbacks = synchronized(this) {
            applyingSettingsDrafts.remove(id)
            if (result != DictionaryManagerWriteResult.Failed) {
                // 再構築中の画面再生成には下書きを返し、完了記録と同時に破棄します。
                settingsDrafts.remove(id)
                completedSettingsDrafts[id] = result
                while (completedSettingsDrafts.size > 8) completedSettingsDrafts.remove(completedSettingsDrafts.keys.first())
            }
            settingsDraftCallbacks.remove(id).orEmpty()
        }
        callbacks.forEach { deliver(it, result) }
    }

    /** 設定画面向けにソースのメタデータを直列 I/O で取得します。 */
    @Synchronized
    fun listSources(callback: (DictionaryManagerWriteResult<List<DictionarySourceInfo>>) -> Unit) {
        check(!closed) { "辞書管理器は閉じています" }
        serialExecutor.execute {
            val result = runCatching { repository.listSources().toList() }
            deliver(callback, result.fold(
                onSuccess = { DictionaryManagerWriteResult.Applied(it) },
                onFailure = { DictionaryManagerWriteResult.Failed },
            ))
        }
    }

    /** 書き出しも直列 I/O で実行し、公開済み検索世代を変更しません。 */
    @Synchronized
    fun exportPersonal(callback: (DictionaryManagerWriteResult<ByteArray>) -> Unit) {
        check(!closed) { "辞書管理器は閉じています" }
        serialExecutor.execute {
            val result = runCatching { repository.exportPersonal() }
            deliver(callback, result.fold(
                onSuccess = { DictionaryManagerWriteResult.Applied(it) },
                onFailure = { DictionaryManagerWriteResult.Failed },
            ))
        }
    }

    /** 書き出し本文と、互換 SKK テキストへ含めない抑止件数を直列 I/O で取得します。 */
    @Synchronized
    fun exportPersonalWithMetadata(callback: (DictionaryManagerWriteResult<PersonalDictionaryExport>) -> Unit) {
        check(!closed) { "辞書管理器は閉じています" }
        serialExecutor.execute {
            val result = runCatching { repository.exportPersonalWithMetadata() }
            deliver(callback, result.fold(
                onSuccess = { DictionaryManagerWriteResult.Applied(it) },
                onFailure = { DictionaryManagerWriteResult.Failed },
            ))
        }
    }

    /** 外部 URI へ渡す前に、専用領域で全体の作成と再検証を完了します。 */
    @Synchronized
    fun exportCompleteBackup(
        context: Context,
        producerVersion: String,
        callback: (CompleteBackupResult<CompleteBackupExport>) -> Unit,
    ) {
        check(!closed) { "辞書管理器は閉じています" }
        val appContext = context.applicationContext
        serialExecutor.execute {
            if (closed) return@execute
            var file: File? = null
            val result = runCatching {
                val directory = File(appContext.cacheDir, "dictionary-export")
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
                val staged = File.createTempFile("complete-", ".skkbackup", directory)
                file = staged
                val summary = staged.outputStream().use {
                    repository.writeCompleteBackup(it, fallbackSystems, producerVersion)
                }
                val checked = staged.inputStream().use { CompleteDictionaryBackupCodec().validate(it) { } }
                check(summary == checked) { "バックアップの再検証に失敗しました" }
                CompleteBackupExport(staged, summary, ::forgetBackupHandle).also(::trackBackupHandle)
            }
            if (result.isFailure) file?.delete()
            result.fold(
                onSuccess = { deliverBackupHandle(callback, it) },
                onFailure = { deliver(callback, CompleteBackupResult.Failed(backupFailure(it))) },
            )
        }
    }

    /** 外部入力はここで開閉し、確認後に同じ URI を読み直しません。 */
    @Synchronized
    fun prepareCompleteRestore(
        context: Context,
        openInput: () -> InputStream,
        callback: (CompleteBackupResult<PreparedDictionaryRestore>) -> Unit,
    ) {
        check(!closed) { "辞書管理器は閉じています" }
        val appContext = context.applicationContext
        serialExecutor.execute {
            if (closed) return@execute
            var validated: ValidatedDictionaryBackup? = null
            val result = runCatching {
                validated = ValidatedDictionaryBackup.prepare(appContext, openInput())
                PreparedDictionaryRestore(contextOwner, checkNotNull(validated),
                    repository.dictionaryRevision(), ::forgetBackupHandle).also(::trackBackupHandle)
            }
            if (result.isFailure) validated?.close()
            result.fold(
                onSuccess = { deliverBackupHandle(callback, it) },
                onFailure = { deliver(callback, CompleteBackupResult.Failed(backupFailure(it))) },
            )
        }
    }

    /** 確認済み候補を一度だけ消費します。保存済みの再試行は loadAsync で行います。 */
    @Synchronized
    fun restoreComplete(
        prepared: PreparedDictionaryRestore,
        callback: (CompleteBackupResult<RestoreSummary>) -> Unit,
    ) {
        check(!closed) { "辞書管理器は閉じています" }
        val validated = prepared.claim(contextOwner)
        if (validated == null) {
            deliver(callback, CompleteBackupResult.Failed(CompleteBackupFailure.CONFLICT))
            return
        }
        try {
            serialExecutor.execute {
                try {
                    if (closed) return@execute
                    val saved = runCatching { repository.restoreComplete(validated, prepared.expectedRevision) }
                    if (saved.isFailure) {
                        deliver(callback, CompleteBackupResult.Failed(backupFailure(saved.exceptionOrNull()!!)))
                        return@execute
                    }
                    // コミット直後に旧入力を失効させます。再公開できなくても元へ戻しません。
                    invalidateInputContextsAfterRestore()
                    val summary = saved.getOrThrow()
                    val loaded = runCatching { buildPublished() }
                    if (loaded.isSuccess) {
                        publish(loaded.getOrThrow())
                        deliver(callback, CompleteBackupResult.Applied(summary))
                    } else {
                        publishRefreshFailure()
                        deliver(callback, CompleteBackupResult.SavedButNotApplied(summary))
                    }
                } finally {
                    prepared.finish()
                }
            }
        } catch (error: RuntimeException) {
            prepared.finish()
            throw error
        }
    }

    @Synchronized
    private fun invalidateInputContextsAfterRestore() {
        if (!closed) {
            pendingPromotions.clear()
            published = published.copy(inputEpoch = Any(), inputWritesBlocked = true)
        }
    }

    @Synchronized private fun trackBackupHandle(handle: Closeable) {
        if (closed) handle.close() else backupHandles.add(handle)
    }

    @Synchronized private fun forgetBackupHandle(handle: Closeable) {
        backupHandles.remove(handle)
    }

    private fun <T : Closeable> deliverBackupHandle(callback: (CompleteBackupResult<T>) -> Unit, handle: T) {
        try {
            callbackExecutor.execute {
                if (closed) handle.close() else callback(CompleteBackupResult.Applied(handle))
            }
        } catch (error: RuntimeException) {
            handle.close()
            throw error
        }
    }

    private fun backupFailure(error: Throwable): CompleteBackupFailure = when (error) {
        is CompleteDictionaryBackupException -> when (error.error) {
            CompleteDictionaryBackupError.UNSUPPORTED_VERSION -> CompleteBackupFailure.UNSUPPORTED_VERSION
            CompleteDictionaryBackupError.LIMIT_EXCEEDED -> CompleteBackupFailure.LIMIT_EXCEEDED
            else -> CompleteBackupFailure.INVALID_FORMAT
        }
        is SQLiteFullException -> CompleteBackupFailure.CAPACITY
        is StaleDictionaryRevisionException, is ArithmeticException -> CompleteBackupFailure.CONFLICT
        else -> CompleteBackupFailure.IO
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        pendingPromotions.clear()
        observers.clear()
        settingsDrafts.clear()
        applyingSettingsDrafts.clear()
        settingsDraftCallbacks.clear()
        completedSettingsDrafts.clear()
        published = Published(null, DictionaryManagerStatus.Unavailable(DictionaryUnavailableReason.FAILED))
        serialExecutor.execute {
            val handles = synchronized(this) { backupHandles.toList() }
            handles.forEach { runCatching { it.close() } }
            repository.close()
            ownedExecutor?.shutdown()
        }
    }

    @Synchronized
    private fun <T> writeThenReload(
        callback: (DictionaryManagerWriteResult<T>) -> Unit,
        write: () -> T,
    ) {
        check(!closed) { "辞書管理器は閉じています" }
        serialExecutor.execute {
            val saved = runCatching(write)
            if (saved.isFailure) {
                deliver(callback, DictionaryManagerWriteResult.Failed)
                return@execute
            }
            val value = saved.getOrThrow()
            val loaded = runCatching { buildPublished() }
            if (loaded.isSuccess) {
                publish(loaded.getOrThrow())
                deliver(callback, DictionaryManagerWriteResult.Applied(value))
            } else {
                publishRefreshFailure()
                deliver(callback, DictionaryManagerWriteResult.SavedButNotApplied(value))
            }
        }
    }

    internal val cacheStats: DictionaryCacheStats get() = published.sqlite?.stats ?: DictionaryCacheStats(0, 0, 0)

    /** テストとバックグラウンド計測で同期境界を駆動します。主スレッドからは呼びません。 */
    internal fun <T> readBlocking(block: () -> T): T {
        val scope = jp.hayase.skk.core.dictionary.DictionaryReadScope()
        try {
            repeat(256) {
                try { return scope.run(block) } catch (pending: jp.hayase.skk.core.dictionary.DeferredDictionaryReadException) {
                    pending.load()
                }
            }
            throw DictionaryUnavailableException(DictionaryUnavailableReason.FAILED)
        } finally {
            scope.close()
        }
    }

    private fun <T> readForCaller(block: () -> T): T = if (deferReads) block() else readBlocking(block)

    private fun buildPublished(): Published {
        // 全件スナップショットは旧世代の故障注入試験だけに残します。
        loadSnapshot?.let { return buildFixturePublished(it()) }
        val metadata = loadMetadata()
        val ids = metadata.sources.map { it.id }.toSet()
        val fallbacks = if (metadata.allowFallback) fallbackSystems.filterNot { it.id in ids } else emptyList()
        val sqlite = SQLiteSkkDictionary(repository, metadata, fallbacks)
        return Published(NumericSkkDictionary(sqlite), DictionaryManagerStatus.Ready(),
            fallbacks.associate { it.id to it.generation }, published.inputEpoch, sqlite = sqlite)
    }

    private fun buildFixturePublished(snapshot: DictionaryLookupSnapshot): Published {
        val fallbacks = if (snapshot.allowFallback) {
            fallbackSystems.filterNot { it.id in snapshot.storedSourceIds }
        } else emptyList()
        return Published(
            NumericSkkDictionary(snapshot.asComposite(fallbacks)),
            DictionaryManagerStatus.Ready(),
            fallbacks.associate { it.id to it.generation },
            published.inputEpoch,
        )
    }

    /** 入力欄を作る時点の許可を固定します。古い確認から取得し直しません。 */
    fun captureInputWriteContext(): DictionaryInputWriteContext =
        DictionaryInputWriteContext(contextOwner, published.inputEpoch)

    fun isInputWriteContextCurrent(context: DictionaryInputWriteContext): Boolean =
        context.owner === contextOwner && context.epoch === published.inputEpoch

    private fun acceptsInputWrite(context: DictionaryInputWriteContext): Boolean {
        val current = published
        return !closed && context.owner === contextOwner && context.epoch === current.inputEpoch &&
            !current.inputWritesBlocked &&
            (current.status as? DictionaryManagerStatus.Ready)?.freshness != DictionaryFreshness.STALE
    }

    private fun requireInputWrite(context: DictionaryInputWriteContext) {
        if (!acceptsInputWrite(context)) throw StaleDictionaryInputContextException()
    }

    private fun personalWriteFailure(error: Throwable): PersonalWriteFailure = when (error) {
        is SQLiteFullException -> PersonalWriteFailure.CAPACITY
        is StaleDictionaryGenerationException,
        is CandidateOriginMismatchException,
        is StaleDictionaryInputContextException -> PersonalWriteFailure.CONFLICT
        is PersonalDataPolicyRejectedException -> PersonalWriteFailure.POLICY_REJECTED
        else -> PersonalWriteFailure.GENERAL
    }

    private fun publishRefreshFailure() {
        val current = published
        publish(
            if (current.dictionary == null) {
                current.copy(status = DictionaryManagerStatus.Unavailable(DictionaryUnavailableReason.FAILED),
                    inputWritesBlocked = true)
            } else {
                current.copy(status = DictionaryManagerStatus.Ready(DictionaryFreshness.STALE),
                    inputWritesBlocked = true)
            },
        )
    }

    @Synchronized
    private fun publish(next: Published) {
        if (closed) return
        if (next.inputEpoch !== published.inputEpoch || next.inputWritesBlocked) pendingPromotions.clear()
        published = next
        observers.keys.toList().forEach { deliverObserver(it, next.status) }
    }

    private fun <T> deliver(callback: (T) -> Unit, value: T) {
        callbackExecutor.execute {
            if (!closed) callback(value)
        }
    }

    private fun deliverObserver(id: Long, value: DictionaryManagerStatus) {
        callbackExecutor.execute {
            val observer = synchronized(this) { if (closed) null else observers[id] }
            observer?.invoke(value)
        }
    }

    companion object {
        /** Android の主スレッドへ通知し、SQLite 用の単一スレッドを所有する既定構成です。 */
        fun create(
            context: Context,
            fallbackSystems: List<SkkDictionarySource> = emptyList(),
            callbackExecutor: Executor = HandlerExecutor(Handler(Looper.getMainLooper())),
        ): DictionaryManager {
            val serial = Executors.newSingleThreadExecutor()
            return DictionaryManager(
                SQLiteDictionaryRepository(context.applicationContext),
                serial,
                callbackExecutor,
                fallbackSystems,
                ownedExecutor = serial,
            )
        }
    }
}

private class HandlerExecutor(private val handler: Handler) : Executor {
    override fun execute(command: Runnable) {
        check(handler.post(command)) { "主スレッドへ通知できません" }
    }
}
