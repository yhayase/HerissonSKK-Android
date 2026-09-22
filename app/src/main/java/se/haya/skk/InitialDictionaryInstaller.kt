package se.haya.skk

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import se.haya.skk.core.dictionary.SkkDictionaryDocument
import se.haya.skk.dictionary.DictionaryManager
import se.haya.skk.dictionary.DictionaryManagerStatus
import se.haya.skk.dictionary.DictionaryFreshness
import se.haya.skk.core.dictionary.SkkDictionaryCodec
import se.haya.skk.dictionary.DictionaryManagerWriteResult
import se.haya.skk.dictionary.DictionaryRuntime
import se.haya.skk.dictionary.network.NetworkDictionaryCancellation
import se.haya.skk.dictionary.network.NetworkDictionaryCatalog
import se.haya.skk.dictionary.network.NetworkDictionaryDownloader

/** 初期設定の取得処理を画面回転から独立させ、同じ辞書の二重取得を防ぎます。 */
internal class InitialDictionaryInstaller(
    private val manager: DictionaryManager,
    private val worker: Executor = Executors.newSingleThreadExecutor(),
    private val callback: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
    private val download: (String) -> SkkDictionaryDocument = { url ->
        SkkDictionaryCodec.parse(NetworkDictionaryDownloader().download(url, NetworkDictionaryCancellation()).bytes)
    },
) {
    data class State(
        val busy: Boolean = false,
        val installed: Set<String> = emptySet(),
        val error: String? = null,
        val loaded: Boolean = false,
    )
    private val listeners = mutableSetOf<(State) -> Unit>()
    private var state = State()
    private var attempted = false
    private var explicitGeneralRequested = false
    private var inventoryGeneration = 0
    private val queue = ArrayDeque<String>()
    private var inFlight: String? = null

    fun observe(listener: (State) -> Unit): Closeable {
        listeners += listener
        listener(state)
        return Closeable { listeners -= listener }
    }

    fun ensureBasic() = refreshInstalled()

    fun refreshInstalled() {
        val generation = ++inventoryGeneration
        // 再表示時は前回の一覧を選択状態の初期値に使わないようにします。
        publish(state.copy(loaded = false))
        manager.listSources { result ->
            if (generation != inventoryGeneration) return@listSources
            val sources = result.valueOrNull() ?: run {
                publish(state.copy(loaded = false, error = "辞書一覧を読み込めませんでした。"))
                return@listSources
            }
            val installed = installedCatalogKeys(sources)
            val prepareBasic = !attempted && "S" !in installed && "L" !in installed
            attempted = true
            publish(state.copy(installed = installed, loaded = true, error = null))
            // 一覧の応答より先に届いた明示的な選択を、自動取得で置き換えません。
            if (prepareBasic && !explicitGeneralRequested) enqueue(setOf("S"))
        }
    }

    fun install(keys: Set<String>) {
        if ("S" in keys || "L" in keys) explicitGeneralRequested = true
        enqueue(keys)
    }

    private fun enqueue(keys: Set<String>) {
        normalizeInitialDictionaryKeys(keys)
            // 設定画面で削除されている場合があるため、保存状態は next で確認します。
            .filter { it !in queue && it != inFlight }
            .forEach(queue::addLast)
        if (!state.busy) next()
    }

    private fun publish(next: State) {
        state = next
        listeners.toList().forEach { it(next) }
    }

    private fun next() {
        val key = queue.removeFirstOrNull() ?: run { publish(state.copy(busy = false)); return }
        inFlight = key
        val entry = requireNotNull(NetworkDictionaryCatalog.find(key))
        publish(state.copy(busy = true, error = null))
        manager.listSources { result ->
            val sources = when (result) {
                is DictionaryManagerWriteResult.Applied -> result.value
                is DictionaryManagerWriteResult.SavedButNotApplied -> result.value
                DictionaryManagerWriteResult.Failed -> { fail("辞書一覧を読み込めませんでした。"); return@listSources }
            }
            val id = "official-skk-$key"
            if (sources.any { it.id == id }) {
                val redundantGeneral = sources.firstOrNull { it.id == "official-skk-${oppositeGeneral(key)}" }
                if (redundantGeneral != null) {
                    val draft = manager.createSettingsDraft(sources)
                    draft.stageRemove(redundantGeneral)
                    stageCatalogOrder(draft)
                    manager.applySettingsDraft(draft.id) { result ->
                        when (result) {
                            is DictionaryManagerWriteResult.Applied -> installed(key)
                            is DictionaryManagerWriteResult.SavedButNotApplied ->
                                fail("辞書は保存済みですが利用できません。再試行してください。")
                            DictionaryManagerWriteResult.Failed -> {
                                manager.discardSettingsDraft(draft.id)
                                fail("辞書の構成を保存できませんでした。再試行してください。")
                            }
                        }
                    }
                    return@listSources
                }
                // 保存済みでも前回の公開失敗があり得るため、再公開の成功を確認します。
                manager.loadAsync { status ->
                    if (status == DictionaryManagerStatus.Ready(DictionaryFreshness.CURRENT)) installed(key)
                    else fail("辞書は保存済みですが利用できません。再試行してください。")
                }
                return@listSources
            }
            worker.execute {
                val downloaded = runCatching {
                    download(entry.url)
                }
                callback.execute publishDownload@{
                    val document = downloaded.getOrElse {
                        fail("${entry.displayName}を取得できませんでした。接続を確認して再試行してください。")
                        return@publishDownload
                    }
                    val draft = manager.createSettingsDraft(sources)
                    runCatching { draft.stageImport(id, entry.name, document, null, entry.url) }.onFailure {
                        manager.discardSettingsDraft(draft.id)
                        fail("辞書を追加できませんでした。")
                        return@publishDownload
                    }
                    sources.firstOrNull { it.id == "official-skk-${oppositeGeneral(key)}" }?.let(draft::stageRemove)
                    stageCatalogOrder(draft)
                    manager.applySettingsDraft(draft.id) { saved ->
                        when (saved) {
                            is DictionaryManagerWriteResult.Applied -> {
                                installed(key)
                            }
                            is DictionaryManagerWriteResult.SavedButNotApplied -> {
                                fail("辞書は保存済みですが利用できません。再試行してください。")
                            }
                            DictionaryManagerWriteResult.Failed -> {
                                manager.discardSettingsDraft(draft.id)
                                fail("辞書を保存できませんでした。再試行してください。")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun installed(key: String) {
        inFlight = null
        val withoutOpposite = oppositeGeneral(key)?.let { state.installed - it } ?: state.installed
        val installed = withoutOpposite + key
        publish(state.copy(installed = installed))
        next()
    }

    private fun fail(message: String) {
        inFlight = null
        queue.clear()
        publish(state.copy(busy = false, error = message))
    }

    private fun installedCatalogKeys(sources: List<se.haya.skk.dictionary.DictionarySourceInfo>): Set<String> =
        NetworkDictionaryCatalog.entries.mapNotNull { entry ->
            entry.key.takeIf { key -> sources.any { it.id == "official-skk-$key" } }
        }.toSet()

    private fun oppositeGeneral(key: String): String? = when (key) { "S" -> "L"; "L" -> "S"; else -> null }

    private fun stageCatalogOrder(draft: se.haya.skk.dictionary.DictionarySettingsDraft) {
        val rank = NetworkDictionaryCatalog.entries.mapIndexed { index, entry -> "official-skk-${entry.key}" to index }.toMap()
        val ordered = draft.sources.filter { it.kind == se.haya.skk.dictionary.DictionarySourceKind.SYSTEM }
            .sortedWith(compareBy({ rank[it.id] ?: Int.MAX_VALUE }, { it.order }))
            .map { it.id }
        draft.stageOrder(ordered)
    }

    private fun <T> DictionaryManagerWriteResult<T>.valueOrNull(): T? = when (this) {
        is DictionaryManagerWriteResult.Applied -> value
        is DictionaryManagerWriteResult.SavedButNotApplied -> value
        DictionaryManagerWriteResult.Failed -> null
    }

    companion object {
        private var instance: InitialDictionaryInstaller? = null
        fun get(context: Context) = instance ?: InitialDictionaryInstaller(DictionaryRuntime.get(context.applicationContext)).also { instance = it }
    }
}
