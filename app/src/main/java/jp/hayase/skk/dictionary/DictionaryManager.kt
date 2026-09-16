package jp.hayase.skk.dictionary

import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import jp.hayase.skk.core.DictionaryCandidate
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.dictionary.CompositeSkkDictionary
import jp.hayase.skk.core.dictionary.DictionaryUnavailableException
import jp.hayase.skk.core.dictionary.DictionaryUnavailableReason
import jp.hayase.skk.core.dictionary.SkkDictionaryDocument
import jp.hayase.skk.core.dictionary.SkkDictionarySource
import jp.hayase.skk.core.dictionary.SkkDictionaryCandidate

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
 * SQLite の読み書きを直列バックグラウンド実行し、検索用の不変スナップショットだけを公開します。
 *
 * [lookup] は DB、ファイル、解析器へ触れません。呼出側は起動時に [loadAsync] を開始し、状態通知で
 * 準備完了を待ちます。
 */
class DictionaryManager(
    private val repository: SQLiteDictionaryRepository,
    private val serialExecutor: Executor,
    private val callbackExecutor: Executor,
    fallbackSystems: List<SkkDictionarySource> = emptyList(),
    private val loadSnapshot: () -> DictionaryLookupSnapshot = repository::loadSnapshot,
    private val ownedExecutor: ExecutorService? = null,
    val personalDataPolicy: PersonalDataPolicy = PersonalDataPolicy(),
) : Closeable {
    private data class Published(
        val dictionary: CompositeSkkDictionary?,
        val status: DictionaryManagerStatus,
    )

    private val observers = linkedMapOf<Long, (DictionaryManagerStatus) -> Unit>()
    private val fallbackSystems = fallbackSystems.toList()
    private var nextObserverId = 0L
    @Volatile
    private var closed = false

    @Volatile
    private var published = Published(null, DictionaryManagerStatus.Loading)

    /** 現在の状態です。検索可能な古い世代がある再読込失敗は [DictionaryFreshness.STALE] で表します。 */
    val status: DictionaryManagerStatus get() = published.status

    /** メモリー上の公開済み辞書だけを検索します。 */
    fun lookup(query: DictionaryQuery): List<DictionaryCandidate> {
        val current = published
        val dictionary = current.dictionary ?: throw DictionaryUnavailableException(
            (current.status as? DictionaryManagerStatus.Unavailable)?.reason
                ?: DictionaryUnavailableReason.INITIALIZING,
        )
        return dictionary.lookup(query)
    }

    /** 起動時または明示的な再読込時に全辞書を読み込みます。 */
    @Synchronized
    fun loadAsync(callback: ((DictionaryManagerStatus) -> Unit)? = null) {
        check(!closed) { "辞書管理器は閉じています" }
        val current = published
        publish(
            if (current.dictionary == null) Published(null, DictionaryManagerStatus.Loading)
            else Published(current.dictionary, DictionaryManagerStatus.Ready(DictionaryFreshness.REFRESHING)),
        )
        serialExecutor.execute {
            val loaded = runCatching { buildDictionary(loadSnapshot()) }
            if (loaded.isSuccess) {
                publish(Published(loaded.getOrThrow(), DictionaryManagerStatus.Ready()))
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

    /** 入力由来の登録・学習だけを対象に、受理時と実際の書込直前に方針を確認します。 */
    @Synchronized
    fun savePersonalCandidate(
        key: String,
        candidate: SkkDictionaryCandidate,
        originAllowsSaving: Boolean,
        callback: (PersonalWriteResult) -> Unit,
    ) {
        check(!closed) { "辞書管理器は閉じています" }
        val permit = personalDataPolicy.request(originAllowsSaving)
        if (permit == null) {
            deliver(callback, PersonalWriteResult.Failed(PersonalWriteFailure.POLICY_REJECTED))
            return
        }
        serialExecutor.execute {
            val saved = runCatching {
                repository.promotePersonalCandidate(key, candidate) { personalDataPolicy.accepts(permit) }
            }
            val error = saved.exceptionOrNull()
            if (error != null) {
                val reason = when (error) {
                    is SQLiteFullException -> PersonalWriteFailure.CAPACITY
                    is StaleDictionaryGenerationException -> PersonalWriteFailure.CONFLICT
                    is PersonalDataPolicyRejectedException -> PersonalWriteFailure.POLICY_REJECTED
                    else -> PersonalWriteFailure.GENERAL
                }
                deliver(callback, PersonalWriteResult.Failed(reason))
                return@execute
            }
            val loaded = runCatching { buildDictionary(loadSnapshot()) }
            if (loaded.isSuccess) {
                publish(Published(loaded.getOrThrow(), DictionaryManagerStatus.Ready()))
                deliver(callback, PersonalWriteResult.Applied)
            } else {
                publishRefreshFailure()
                deliver(callback, PersonalWriteResult.SavedButNotApplied)
            }
        }
    }

    fun setSourceEnabled(id: String, enabled: Boolean, callback: (DictionaryManagerWriteResult<Unit>) -> Unit) =
        writeThenReload(callback) { repository.setSourceEnabled(id, enabled) }

    fun setSystemOrder(ids: List<String>, callback: (DictionaryManagerWriteResult<Unit>) -> Unit) {
        val requested = ids.toList()
        writeThenReload(callback) { repository.setSystemOrder(requested) }
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

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        observers.clear()
        published = Published(null, DictionaryManagerStatus.Unavailable(DictionaryUnavailableReason.FAILED))
        serialExecutor.execute {
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
            val loaded = runCatching { buildDictionary(loadSnapshot()) }
            if (loaded.isSuccess) {
                publish(Published(loaded.getOrThrow(), DictionaryManagerStatus.Ready()))
                deliver(callback, DictionaryManagerWriteResult.Applied(value))
            } else {
                publishRefreshFailure()
                deliver(callback, DictionaryManagerWriteResult.SavedButNotApplied(value))
            }
        }
    }

    private fun buildDictionary(snapshot: DictionaryLookupSnapshot): CompositeSkkDictionary =
        CompositeSkkDictionary(snapshot.personal, snapshot.systems + fallbackSystems)

    private fun publishRefreshFailure() {
        val current = published
        publish(
            if (current.dictionary == null) {
                Published(null, DictionaryManagerStatus.Unavailable(DictionaryUnavailableReason.FAILED))
            } else {
                Published(current.dictionary, DictionaryManagerStatus.Ready(DictionaryFreshness.STALE))
            },
        )
    }

    @Synchronized
    private fun publish(next: Published) {
        if (closed) return
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
