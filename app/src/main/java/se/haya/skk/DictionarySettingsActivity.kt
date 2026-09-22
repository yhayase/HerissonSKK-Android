package se.haya.skk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import se.haya.skk.core.dictionary.SkkDictionaryCodec
import se.haya.skk.core.dictionary.SkkDictionaryDocument
import se.haya.skk.core.dictionary.SkkDictionaryEncoding
import se.haya.skk.core.dictionary.SkkDictionaryError
import se.haya.skk.core.dictionary.SkkDictionaryFormatException
import se.haya.skk.dictionary.DictionaryManager
import se.haya.skk.dictionary.DictionaryFreshness
import se.haya.skk.dictionary.DictionaryManagerStatus
import se.haya.skk.dictionary.DictionaryManagerSubscription
import se.haya.skk.dictionary.DictionaryManagerWriteResult
import se.haya.skk.dictionary.DictionaryRuntime
import se.haya.skk.dictionary.DictionarySettingsDraft
import se.haya.skk.dictionary.DictionarySourceInfo
import se.haya.skk.dictionary.DictionarySourceKind
import se.haya.skk.dictionary.CandidateSuppressionInfo
import se.haya.skk.dictionary.CandidateSuppressionSnapshot
import se.haya.skk.dictionary.PersonalDictionaryExport
import se.haya.skk.dictionary.network.NetworkDictionaryCancellation
import se.haya.skk.dictionary.network.NetworkDictionaryCatalog
import se.haya.skk.dictionary.network.NetworkDictionaryCatalogEntry
import se.haya.skk.dictionary.network.NetworkDictionaryDownload
import se.haya.skk.dictionary.network.NetworkDictionaryDownloader
import se.haya.skk.dictionary.network.NetworkDictionaryException
import se.haya.skk.dictionary.network.NetworkDictionaryFailure

class DictionarySettingsActivity : SettingsPageActivity() {
    private enum class ImportKind { NEW_SYSTEM, UPDATE_SYSTEM, MERGE_PERSONAL, REPLACE_PERSONAL }

    private data class ImportRequest(
        val kind: ImportKind,
        val encoding: SkkDictionaryEncoding,
        val id: String? = null,
        val name: String? = null,
        val expectedGeneration: Long? = null,
        val originUrl: String? = null,
    )

    private lateinit var manager: DictionaryManager
    private lateinit var pageFragment: DictionarySettingsFragment
    private var encodingSelection = SkkDictionaryEncoding.AUTO
    private var statusText: CharSequence? = null
    private val fileExecutor: Executor = fileExecutorFactoryForTest?.invoke() ?: Executors.newSingleThreadExecutor()
    private var subscription: DictionaryManagerSubscription? = null
    private var active = false
    private var pickerState = DictionaryPickerState()
    private var pendingImport: ImportRequest? = null
    private var personalSource: DictionarySourceInfo? = null
    private var systemSources: List<DictionarySourceInfo> = emptyList()
    private var sourcesLoadedOnce = false
    private var busy = false
    private var sourceAvailability = DictionarySourceAvailability.UNKNOWN
    private var importFailureMessage: String? = null
    private var importInProgress = false
    private var draft: DictionarySettingsDraft? = null
    private var networkCancellation: NetworkDictionaryCancellation? = null
    private var suppressions: CandidateSuppressionSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = true
        manager = managerFactoryForTest?.invoke(this) ?: DictionaryRuntime.get(this)
        encodingSelection = savedInstanceState?.getString(STATE_ENCODING_SELECTION)
            ?.let { runCatching { SkkDictionaryEncoding.valueOf(it) }.getOrNull() }
            ?: SkkDictionaryEncoding.AUTO
        val restoredDraftId = savedInstanceState?.getString(STATE_DRAFT)
        draft = restoredDraftId?.let(manager::settingsDraft)
        val restoredCompletion = if (draft == null) restoredDraftId?.let(manager::consumeSettingsDraftCompletion) else null
        pickerState = DictionaryPickerState.restore(savedInstanceState?.getString(STATE_PICKER))
        restorePendingImport(savedInstanceState)
        importFailureMessage = savedInstanceState?.getString(STATE_IMPORT_ERROR)
        if (restoredDraftId != null && draft == null && restoredCompletion == null) {
            importFailureMessage = "編集中の画面を復元できませんでした。保存済みの辞書を確認してください。"
        }
        if (savedInstanceState?.getBoolean(STATE_IMPORT_IN_PROGRESS) == true) {
            importFailureMessage = getString(R.string.dictionary_import_interrupted)
        }
        if (pickerState.pending == DictionaryPickerOperation.IMPORT && pendingImport == null) {
            pickerState.consume(DictionaryPickerOperation.IMPORT)
        }

        val requestedPage = DictionarySettingsFragment()
        installSettingsPage(getString(R.string.dictionary_settings_title), requestedPage)
        pageFragment = supportFragmentManager.findFragmentById(R.id.settings_content)
            as? DictionarySettingsFragment ?: requestedPage
        setPageActions(::saveAndClose)
        statusText = importFailureMessage ?: getString(R.string.dictionary_loading)

        setBusy(pickerState.isPending)
        subscription = manager.observe { status -> if (active) handleManagerStatus(status) }
        if (draft?.id?.let(manager::isSettingsDraftApplying) == true) {
            applyDraft()
        } else {
            (restoredCompletion ?: restoredDraftId?.let(manager::consumeSettingsDraftCompletion))?.let(::onDraftApplied)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingImport?.let { request ->
            outState.putString(STATE_KIND, request.kind.name)
            outState.putString(STATE_ENCODING, request.encoding.name)
            outState.putString(STATE_ID, request.id)
            outState.putString(STATE_NAME, request.name)
            outState.putString(STATE_ORIGIN_URL, request.originUrl)
            request.expectedGeneration?.let { outState.putLong(STATE_GENERATION, it) }
        }
        pickerState.savedValue()?.let { outState.putString(STATE_PICKER, it) }
        importFailureMessage?.let { outState.putString(STATE_IMPORT_ERROR, it) }
        outState.putBoolean(STATE_IMPORT_IN_PROGRESS, importInProgress)
        outState.putString(STATE_ENCODING_SELECTION, encodingSelection.name)
        draft?.id?.let { outState.putString(STATE_DRAFT, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) draft?.let { manager.discardSettingsDraft(it.id) }
        active = false
        networkCancellation?.cancel()
        networkCancellation = null
        subscription?.close()
        subscription = null
        (fileExecutor as? ExecutorService)?.shutdownNow()
        super.onDestroy()
    }

    public override fun saveAndClose() = applyDraft()

    private fun applyDraft() {
        if (busy) return
        val current = draft ?: run { finish(); return }
        if (!current.hasChanges) { finish(); return }
        setBusy(true)
        setStatus(R.string.dictionary_applying)
        manager.applySettingsDraft(current.id, ::onDraftApplied)
    }

    public override fun discardAndClose() {
        draft?.let { manager.discardSettingsDraft(it.id) }
        draft = null
        super.discardAndClose()
    }

    private fun onDraftApplied(result: DictionaryManagerWriteResult<Unit>) {
        if (!active) return
        setBusy(false)
        showWriteResult(result)
        when (result) {
            is DictionaryManagerWriteResult.Applied -> {
                draft?.let(::releaseRemovedFilePermissions)
                draft = null
                finish()
            }
            is DictionaryManagerWriteResult.SavedButNotApplied -> {
                draft?.let(::releaseRemovedFilePermissions)
                draft = null
                importFailureMessage = getString(R.string.dictionary_saved_not_applied)
                MaterialAlertDialogBuilder(this)
                    .setTitle("辞書を保存しましたが、変換へ反映できませんでした")
                    .setMessage(R.string.dictionary_saved_not_applied)
                    .setPositiveButton("閉じる") { _, _ -> finish() }
                    .show()
            }
            DictionaryManagerWriteResult.Failed -> Unit
        }
    }

    private fun releaseRemovedFilePermissions(current: DictionarySettingsDraft) {
        val retainedOrigins = current.sources.mapNotNull { it.originUrl }.toSet()
        current.baselineSystems.asSequence()
            .filter { baseline -> current.sources.none { it.id == baseline.id } }
            .mapNotNull { it.originUrl?.let(Uri::parse) }
            .filter { it.scheme.equals("content", ignoreCase = true) && it.toString() !in retainedOrigins }
            .forEach { uri ->
                runCatching {
                    contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
    }

    private fun confirmDiscardDraft() {
        if (busy) return
        if (draft?.hasChanges != true) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("変更を破棄する")
            .setMessage("適用していない辞書の変更を破棄しますか？")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("破棄する") { _, _ ->
                draft?.let { manager.discardSettingsDraft(it.id) }
                draft = null
                finish()
            }
            .show()
    }

    @Deprecated("基底 Activity では Activity Result API を利用できないため")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val pickerOperation = when (requestCode) {
            REQUEST_IMPORT -> DictionaryPickerOperation.IMPORT
            REQUEST_EXPORT -> DictionaryPickerOperation.EXPORT
            else -> return
        }
        if (resultCode != RESULT_OK) {
            if (!pickerState.consume(pickerOperation)) return
            if (requestCode == REQUEST_IMPORT) pendingImport = null
            setBusy(false)
            restoreManagerStatus()
            return
        }
        if (!pickerState.consume(pickerOperation)) return
        val uri = data?.data
        if (uri == null) {
            if (requestCode == REQUEST_IMPORT) pendingImport = null
            setBusy(false)
            restoreManagerStatus()
            return
        }
        when (requestCode) {
            REQUEST_IMPORT -> {
                val request = pendingImport
                pendingImport = null
                if (request == null) {
                    setBusy(false)
                    restoreManagerStatus()
                    return
                }
                val retainedRequest = if (
                    request.kind == ImportKind.NEW_SYSTEM || request.kind == ImportKind.UPDATE_SYSTEM
                ) {
                    request.copy(originUrl = uri.toString())
                } else request
                readAndPreview(uri, retainedRequest)
            }
            REQUEST_EXPORT -> exportTo(uri)
        }
    }

    private fun restorePendingImport(state: Bundle?) {
        val kind = state?.getString(STATE_KIND)?.let { runCatching { ImportKind.valueOf(it) }.getOrNull() } ?: return
        val encoding = state.getString(STATE_ENCODING)
            ?.let { runCatching { SkkDictionaryEncoding.valueOf(it) }.getOrNull() }
            ?: SkkDictionaryEncoding.AUTO
        pendingImport = ImportRequest(
            kind,
            encoding,
            state.getString(STATE_ID),
            state.getString(STATE_NAME),
            state.takeIf { it.containsKey(STATE_GENERATION) }?.getLong(STATE_GENERATION),
            state.getString(STATE_ORIGIN_URL),
        )
    }

    internal fun selectedEncoding(): SkkDictionaryEncoding = encodingSelection

    internal fun setEncoding(value: SkkDictionaryEncoding) {
        encodingSelection = value
        refreshPage()
    }

    private fun launchImport(request: ImportRequest) {
        if (busy) return
        setBusy(true)
        pickerState.begin(DictionaryPickerOperation.IMPORT)
        pendingImport = request
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (request.kind == ImportKind.NEW_SYSTEM || request.kind == ImportKind.UPDATE_SYSTEM) {
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
            },
            REQUEST_IMPORT,
        )
    }

    private fun launchExport() {
        if (busy) return
        setBusy(true)
        pickerState.begin(DictionaryPickerOperation.EXPORT)
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, DEFAULT_EXPORT_NAME)
            },
            REQUEST_EXPORT,
        )
    }

    private fun readAndPreview(uri: Uri, request: ImportRequest) {
        importInProgress = true
        setStatus(R.string.dictionary_reading)
        fileExecutor.execute {
            val loaded = runCatching {
                val name = displayName(uri)?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.dictionary_unnamed_file)
                val bytes = contentResolver.openInputStream(uri)?.use {
                    readBounded(it, SkkDictionaryCodec.MAX_FILE_BYTES)
                } ?: throw IllegalStateException()
                name to SkkDictionaryCodec.parse(bytes, request.encoding)
            }
            runOnUiThread {
                if (!active) return@runOnUiThread
                importInProgress = false
                loaded.fold(
                    onSuccess = { (name, document) -> showPreview(name, request, document) },
                    onFailure = { error ->
                        setBusy(false)
                        showImportFailure(importFailureText(error))
                    },
                )
            }
        }
    }

    private fun displayName(uri: Uri): String? = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun showPreview(name: String, request: ImportRequest, document: SkkDictionaryDocument) {
        val entryCount = document.entries.size
        val candidateCount = document.entries.sumOf { it.candidates.size }
        val diagnostics = document.diagnostics
        val message = buildString {
            append(getString(R.string.dictionary_preview_operation, operationLabel(request))).append('\n')
            append(getString(R.string.dictionary_preview_file, name)).append('\n')
            append(getString(R.string.dictionary_preview_encoding, encodingLabel(document.encoding))).append('\n')
            append(getString(R.string.dictionary_preview_entries, entryCount)).append('\n')
            append(getString(R.string.dictionary_preview_candidates, candidateCount)).append('\n')
            append(getString(R.string.dictionary_preview_duplicates, diagnostics.duplicateCandidateCount)).append('\n')
            append(getString(R.string.dictionary_preview_conflicts, diagnostics.annotationConflictCount))
            if (request.kind == ImportKind.REPLACE_PERSONAL) {
                append("\n\n").append(getString(R.string.dictionary_replace_warning))
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dictionary_preview_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelPreview() }
            .setPositiveButton(R.string.dictionary_apply) { _, _ -> applyImport(name, request, document) }
            .setOnCancelListener { cancelPreview() }
            .show()
    }

    private fun applyImport(name: String, request: ImportRequest, document: SkkDictionaryDocument) {
        val current = draft ?: run { setBusy(false); setStatus(R.string.dictionary_apply_failed); return }
        val origin = request.originUrl?.let(Uri::parse)
        if (
            (request.kind == ImportKind.NEW_SYSTEM || request.kind == ImportKind.UPDATE_SYSTEM) &&
            origin?.scheme.equals("content", ignoreCase = true) &&
            !hasPersistedReadPermission(requireNotNull(origin)) &&
            runCatching {
                contentResolver.takePersistableUriPermission(origin, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isFailure
        ) {
            setBusy(false)
            showImportFailure(getString(R.string.dictionary_source_permission_failed))
            return
        }
        setStatus(R.string.dictionary_applying)
        val staged = runCatching {
            when (request.kind) {
                ImportKind.NEW_SYSTEM, ImportKind.UPDATE_SYSTEM -> current.stageImport(
                    requireNotNull(request.id), request.name ?: name, document, request.expectedGeneration, request.originUrl)
                ImportKind.MERGE_PERSONAL, ImportKind.REPLACE_PERSONAL -> current.stagePersonal(
                    document, requireNotNull(request.expectedGeneration), request.kind == ImportKind.MERGE_PERSONAL)
            }
        }
        setBusy(false)
        if (staged.isSuccess) {
            renderSources(current.sources)
            setStatusText("変更はまだ保存されていません")
        } else {
            setStatusText(staged.exceptionOrNull()?.message ?: getString(R.string.dictionary_apply_failed))
        }
    }

    private fun exportTo(uri: Uri) {
        setStatus(R.string.dictionary_exporting)
        manager.exportPersonalWithMetadata { result ->
            if (!active) return@exportPersonalWithMetadata
            when (result) {
                is DictionaryManagerWriteResult.Applied -> writeExport(uri, result.value)
                is DictionaryManagerWriteResult.SavedButNotApplied -> writeExport(uri, result.value)
                DictionaryManagerWriteResult.Failed -> {
                    setBusy(false)
                    setStatus(R.string.dictionary_export_failed)
                }
            }
        }
    }

    private fun writeExport(uri: Uri, exported: PersonalDictionaryExport) {
        fileExecutor.execute {
            val written = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(exported.bytes) }
                    ?: throw IllegalStateException()
            }.isSuccess
            runOnUiThread {
                if (active) {
                    setBusy(false)
                    if (written) {
                        setStatusText("個人辞書を書き出しました。非表示の指定 ${exported.excludedSuppressionCount} 件は含まれません。")
                    } else {
                        setStatus(R.string.dictionary_export_failed)
                    }
                }
            }
        }
    }

    private fun refreshSources() {
        manager.listSources { result ->
            if (!active) return@listSources
            when (result) {
                is DictionaryManagerWriteResult.Applied -> {
                    sourceAvailability = managerSourceAvailability()
                    val current = draft ?: manager.createSettingsDraft(result.value).also { draft = it }
                    renderSources(current.sources)
                    refreshSuppressions()
                }
                is DictionaryManagerWriteResult.SavedButNotApplied -> {
                    sourceAvailability = managerSourceAvailability()
                    val current = draft ?: manager.createSettingsDraft(result.value).also { draft = it }
                    renderSources(current.sources)
                    refreshSuppressions()
                }
                DictionaryManagerWriteResult.Failed -> {
                    sourceAvailability = DictionarySourceAvailability.UNKNOWN
                    drawSources()
                    setStatus(R.string.dictionary_list_failed)
                }
            }
        }
    }

    private fun refreshSuppressions() {
        manager.listSuppressions { result ->
            if (!active) return@listSuppressions
            when (result) {
                is DictionaryManagerWriteResult.Applied -> drawSuppressions(result.value)
                is DictionaryManagerWriteResult.SavedButNotApplied -> drawSuppressions(result.value)
                DictionaryManagerWriteResult.Failed -> {
                    suppressions = null
                    setStatusText("非表示候補を読み込めませんでした")
                }
            }
        }
    }

    private fun renderSources(sources: List<DictionarySourceInfo>) {
        personalSource = sources.firstOrNull { it.kind == DictionarySourceKind.PERSONAL }
        systemSources = sources.filter { it.kind == DictionarySourceKind.SYSTEM }.sortedBy { it.order }
        sourcesLoadedOnce = true
        refreshPage()
    }

    private fun drawSources() {
        refreshPage()
    }

    private fun drawSuppressions(snapshot: CandidateSuppressionSnapshot) {
        suppressions = snapshot
        refreshPage()
    }

    internal fun confirmRestoreSuppression(
        suppression: CandidateSuppressionInfo,
        generation: Long,
        sourceName: String,
    ) {
        if (busy) return
        MaterialAlertDialogBuilder(this)
            .setTitle("候補を再表示する")
            .setMessage(
                "$sourceName\n読み: ${suppression.key.entryKey}\n候補: ${suppression.key.templateText}" +
                    "\n送り: ${suppression.key.okuriCondition ?: "なし"}\nを再表示しますか？",
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("再表示する") { _, _ -> restoreSuppression(suppression, generation) }
            .show()
    }

    private fun restoreSuppression(suppression: CandidateSuppressionInfo, generation: Long) {
        if (busy) return
        setBusy(true)
        manager.restoreCandidateSuppression(suppression.key, generation) { result ->
            if (!active) return@restoreCandidateSuppression
            setBusy(false)
            when (result) {
                is DictionaryManagerWriteResult.Applied -> {
                    setStatusText("非表示の指定を解除しました")
                }
                is DictionaryManagerWriteResult.SavedButNotApplied -> {
                    setStatusText("非表示の指定は解除しましたが、検索辞書を更新できませんでした。")
                }
                DictionaryManagerWriteResult.Failed -> {
                    setStatusText("候補を再表示できませんでした。一覧を更新します。")
                }
            }
            refreshSources()
        }
    }

    internal fun updateSource(source: DictionarySourceInfo) {
        if (busy) return
        val sourceUri = source.originUrl?.let(Uri::parse) ?: return
        when (sourceUri.scheme?.lowercase()) {
            "https" -> startNetworkImport(source.id, source.name, sourceUri.toString(), source.generation)
            "content" -> {
                if (!hasPersistedReadPermission(sourceUri)) {
                    showImportFailure(getString(R.string.dictionary_saved_source_permission_missing))
                    return
                }
                setBusy(true)
                readAndPreview(
                    sourceUri,
                    ImportRequest(
                        ImportKind.UPDATE_SYSTEM,
                        selectedEncoding(),
                        source.id,
                        source.name,
                        source.generation,
                        sourceUri.toString(),
                    ),
                )
            }
        }
    }

    internal fun canUpdateSource(source: DictionarySourceInfo): Boolean {
        val sourceUri = source.originUrl?.let(Uri::parse) ?: return false
        return sourceUri.scheme.equals("https", ignoreCase = true) ||
            sourceUri.scheme.equals("content", ignoreCase = true) && hasPersistedReadPermission(sourceUri)
    }

    internal fun sourceUpdateSummary(source: DictionarySourceInfo): String = when {
        source.originUrl == null -> "更新元なし（再追加が必要）"
        Uri.parse(source.originUrl).scheme.equals("content", ignoreCase = true) && !canUpdateSource(source) ->
            "元ファイルの権限なし（再追加が必要）"
        Uri.parse(source.originUrl).scheme.equals("https", ignoreCase = true) ->
            "更新元: ${Uri.parse(source.originUrl).host}"
        else -> "更新元: ${Uri.parse(source.originUrl).lastPathSegment ?: "ファイル"}"
    }

    private fun hasPersistedReadPermission(uri: Uri): Boolean =
        hasMatchingPersistedReadGrant(
            uri,
            contentResolver.persistedUriPermissions.map { it.uri to it.isReadPermission },
        )

    internal fun confirmRemove(source: DictionarySourceInfo) {
        if (busy || source.kind != DictionarySourceKind.SYSTEM) return
        setBusy(true)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dictionary_remove_title)
            .setMessage(getString(R.string.dictionary_remove_message, sourceDisplayName(source)))
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelRemove() }
            .setPositiveButton(R.string.dictionary_remove) { _, _ -> removeSystem(source) }
            .setOnCancelListener { cancelRemove() }
            .show()
    }

    private fun cancelRemove() {
        setBusy(false)
        restoreManagerStatus()
    }

    private fun removeSystem(source: DictionarySourceInfo) {
        draft?.stageRemove(source)
        setBusy(false)
        draft?.let { renderSources(it.sources) }
        setStatusText("変更はまだ保存されていません")
    }

    internal fun moveSource(from: Int, to: Int) {
        if (busy || from !in systemSources.indices || to !in systemSources.indices) return
        val reordered = systemSources.toMutableList().apply { add(to, removeAt(from)) }
        draft?.stageOrder(reordered.map { it.id })
        systemSources = reordered.mapIndexed { index, source -> source.copy(order = index) }
        renderPageState(draft?.hasChanges == true, busy, statusText)
        setStatusText("変更はまだ保存されていません")
    }

    internal fun showMoveSourceDialog(source: DictionarySourceInfo, index: Int) {
        if (busy || index !in systemSources.indices || systemSources[index].id != source.id) return
        val actions = buildList {
            if (index > 0) add("上へ移動" to index - 1)
            if (index < systemSources.lastIndex) add("下へ移動" to index + 1)
        }
        if (actions.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("${sourceDisplayName(source)}の優先順位")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                moveSource(index, actions[which].second)
                refreshPage()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showWriteResult(result: DictionaryManagerWriteResult<*>) {
        when (result) {
            is DictionaryManagerWriteResult.Applied -> setStatus(R.string.dictionary_apply_complete)
            is DictionaryManagerWriteResult.SavedButNotApplied -> setStatus(R.string.dictionary_saved_not_applied)
            DictionaryManagerWriteResult.Failed -> setStatus(R.string.dictionary_apply_failed)
        }
    }

    private fun reloadDictionary() {
        if (busy) return
        setBusy(true)
        setStatus(R.string.dictionary_loading)
        manager.loadAsync { status ->
            if (!active) return@loadAsync
            setBusy(false)
            handleManagerStatus(status)
        }
    }

    private fun cancelPreview() {
        setBusy(false)
        restoreManagerStatus()
    }

    private fun restoreManagerStatus() {
        handleManagerStatus(manager.status)
    }

    private fun handleManagerStatus(status: DictionaryManagerStatus) {
        sourceAvailability = sourceAvailabilityForStatus(status)
        if (sourcesLoadedOnce) drawSources()
        when (status) {
            DictionaryManagerStatus.Loading -> if (!busy && importFailureMessage == null) setStatus(R.string.dictionary_loading)
            is DictionaryManagerStatus.Ready -> {
                refreshSources()
                if (!busy && importFailureMessage == null) {
                    if (status.freshness == DictionaryFreshness.STALE) setStatus(R.string.dictionary_reload_stale)
                    else setStatusText(null)
                }
            }
            is DictionaryManagerStatus.Unavailable -> {
                refreshSources()
                if (!busy && importFailureMessage == null) setStatus(R.string.dictionary_reload_failed)
            }
        }
    }

    private fun setBusy(value: Boolean) {
        if (value) importFailureMessage = null
        busy = value
        if (sourcesLoadedOnce) drawSources()
        renderPageState(draft?.hasChanges == true, busy, statusText)
    }

    private fun sourceRowStatusText(status: DictionarySourceRowStatus): String = getString(
        when (status) {
            DictionarySourceRowStatus.AVAILABLE -> R.string.dictionary_source_available
            DictionarySourceRowStatus.UNKNOWN -> R.string.dictionary_source_unknown
            DictionarySourceRowStatus.PROCESSING -> R.string.dictionary_source_processing
            DictionarySourceRowStatus.FAILED -> R.string.dictionary_source_failed
        },
    )

    private fun managerSourceAvailability(): DictionarySourceAvailability = sourceAvailabilityForStatus(manager.status)

    private fun encodingLabel(encoding: SkkDictionaryEncoding): String = when (encoding) {
        SkkDictionaryEncoding.UTF8 -> getString(R.string.dictionary_encoding_utf8)
        SkkDictionaryEncoding.EUC_JP -> getString(R.string.dictionary_encoding_eucjp)
        SkkDictionaryEncoding.AUTO -> getString(R.string.dictionary_encoding_auto)
    }

    private fun operationLabel(request: ImportRequest): String = when (request.kind) {
        ImportKind.NEW_SYSTEM -> getString(R.string.dictionary_operation_add_system)
        ImportKind.UPDATE_SYSTEM -> getString(
            R.string.dictionary_operation_update_system,
            request.id?.let { id ->
                NetworkDictionaryCatalog.entries.firstOrNull { id == "official-skk-${it.key}" }?.displayName
            } ?: requireNotNull(request.name),
        )
        ImportKind.MERGE_PERSONAL -> getString(R.string.dictionary_operation_merge_personal)
        ImportKind.REPLACE_PERSONAL -> getString(R.string.dictionary_operation_replace_personal)
    }

    private fun setStatus(message: Int) {
        if (active) setStatusText(getString(message))
    }

    private fun showImportFailure(message: String) {
        importFailureMessage = message
        setStatusText(message)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dictionary_import_failure_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun importFailureText(error: Throwable): String = when (error) {
        is SkkDictionaryFormatException -> when (error.error) {
            SkkDictionaryError.FILE_TOO_LARGE -> getString(R.string.dictionary_import_too_large)
            SkkDictionaryError.DECODING_FAILED -> getString(R.string.dictionary_import_encoding_failed)
            else -> getString(R.string.dictionary_import_format_failed, error.message)
        }
        is DictionaryFileTooLargeException -> getString(R.string.dictionary_import_too_large)
        else -> getString(R.string.dictionary_import_read_failed)
    }

    internal fun confirmCatalogInstall(entry: NetworkDictionaryCatalogEntry) {
        if (busy) return
        MaterialAlertDialogBuilder(this)
            .setTitle("${entry.displayName}を追加")
            .setMessage("${entry.description}\n内容を確認してから、保存で適用します。")
            .setNeutralButton("配布元・ライセンス") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.licenseUrl)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("取得する") { _, _ ->
                val id = "official-skk-${entry.key}"
                startNetworkImport(id, entry.name, entry.url, systemSources.firstOrNull { it.id == id }?.generation)
            }
            .show()
    }

    private fun startNetworkImport(id: String, name: String, url: String, expectedGeneration: Long?) {
        if (busy || draft == null) return
        val cancellation = NetworkDictionaryCancellation()
        networkCancellation = cancellation
        importInProgress = true
        setBusy(true)
        setStatusText("ネットワーク辞書を取得しています…")
        fileExecutor.execute {
            val loaded = runCatching {
                val download = networkDownloadForTest?.invoke(url, cancellation)
                    ?: NetworkDictionaryDownloader().download(url, cancellation)
                download.finalUrl to SkkDictionaryCodec.parse(download.bytes, SkkDictionaryEncoding.AUTO)
            }
            val uiExecutor = networkUiExecutorForTest ?: Executor(::runOnUiThread)
            uiExecutor.execute {
                if (!active || networkCancellation !== cancellation) return@execute
                networkCancellation = null
                importInProgress = false
                refreshPage()
                loaded.fold(
                    onSuccess = { (finalUrl, document) ->
                        showPreview(
                            finalUrl,
                            ImportRequest(
                                if (expectedGeneration == null) ImportKind.NEW_SYSTEM else ImportKind.UPDATE_SYSTEM,
                                SkkDictionaryEncoding.AUTO,
                                id,
                                name,
                                expectedGeneration,
                                url,
                            ),
                            document,
                        )
                    },
                    onFailure = {
                        setBusy(false)
                        showImportFailure(networkFailureText(it))
                    },
                )
            }
        }
    }

    internal fun cancelNetworkImport() {
        val cancellation = networkCancellation ?: return
        networkCancellation = null
        importInProgress = false
        cancellation.cancel()
        setBusy(false)
        setStatusText("ネットワーク辞書の取得を中止しました。現在の辞書は保持されています。")
    }

    private fun networkFailureText(error: Throwable): String = when (
        (error as? NetworkDictionaryException)?.failure
    ) {
        NetworkDictionaryFailure.INVALID_URL -> "HTTPS の公開 URL を入力してください。認証情報とフラグメントは使用できません。"
        NetworkDictionaryFailure.PRIVATE_ADDRESS -> "端末内またはプライベートネットワークのアドレスからは辞書を取得できません。"
        NetworkDictionaryFailure.REDIRECT_LIMIT -> "転送回数が上限を超えたため取得を中止しました。"
        NetworkDictionaryFailure.HTTP_ERROR -> "配布元から辞書を取得できませんでした。HTTP 応答を確認してください。"
        NetworkDictionaryFailure.COMPRESSED_TOO_LARGE,
        NetworkDictionaryFailure.DECOMPRESSED_TOO_LARGE -> getString(R.string.dictionary_import_too_large)
        NetworkDictionaryFailure.CANCELLED -> "ネットワーク辞書の取得を中止しました。現在の辞書は保持されています。"
        NetworkDictionaryFailure.IO, null -> importFailureText(error)
    }

    internal fun visibleSources(): List<DictionarySourceInfo> = systemSources
    internal fun sourceDisplayName(source: DictionarySourceInfo): String = dictionarySourceDisplayName(source)

    internal fun visibleSourceStatus(sourceId: String): String = sourceRowStatusText(
        sourceRowStatus(sourceId, sourceAvailability, operation = null),
    )
    internal fun visibleSuppressions(): CandidateSuppressionSnapshot? = suppressions
    internal fun isPageBusy(): Boolean = busy
    internal fun isCatalogInstalled(entry: NetworkDictionaryCatalogEntry): Boolean =
        systemSources.any { it.id == "official-skk-${entry.key}" }

    internal fun addFromFile() = launchImport(
        ImportRequest(ImportKind.NEW_SYSTEM, selectedEncoding(), id = UUID.randomUUID().toString()),
    )

    internal fun mergePersonal() {
        val generation = personalSource?.generation ?: return
        launchImport(ImportRequest(ImportKind.MERGE_PERSONAL, selectedEncoding(), expectedGeneration = generation))
    }

    internal fun replacePersonal() {
        val generation = personalSource?.generation ?: return
        launchImport(ImportRequest(ImportKind.REPLACE_PERSONAL, selectedEncoding(), expectedGeneration = generation))
    }

    internal fun exportPersonal() = launchExport()

    internal fun openCompleteBackup() {
        startActivity(Intent(this, CompleteDictionaryBackupActivity::class.java))
    }

    internal fun openCatalogLicense() {
        val entries = NetworkDictionaryCatalog.entries
        MaterialAlertDialogBuilder(this).setTitle("配布元とライセンス")
            .setItems(entries.map { it.displayName }.toTypedArray()) { _, index ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entries[index].licenseUrl)))
            }.setNegativeButton("キャンセル", null).show()
    }

    internal fun showAddSourceDialog() {
        if (busy) return
        val catalog = NetworkDictionaryCatalog.entries
        val labels = catalog.map { it.displayName } + listOf("HTTPS URL", "ファイル")
        MaterialAlertDialogBuilder(this)
            .setTitle("辞書の追加元")
            .setItems(labels.toTypedArray()) { _, index ->
                when {
                    index < catalog.size -> confirmCatalogInstall(catalog[index])
                    index == catalog.size -> showUrlImportDialog()
                    else -> addFromFile()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    internal fun showUrlImportDialog() {
        if (busy) return
        val input = EditText(this).apply {
            hint = "https://example.jp/SKK-JISYO.example.gz"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 3
            contentDescription = "辞書の HTTPS URL"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("URLから辞書を追加")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("取得する") { _, _ ->
                addFromUrl(input.text.toString())
            }
            .show()
    }

    internal fun addFromUrl(rawUrl: String) {
        val url = rawUrl.trim()
        val catalog = NetworkDictionaryCatalog.entries.firstOrNull { it.url == url }
        val name = catalog?.name ?: runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
            ?.removeSuffix(".gz")?.takeIf { it.isNotBlank() } ?: "ネットワーク辞書"
        val id = catalog?.let { "official-skk-${it.key}" } ?: UUID.randomUUID().toString()
        startNetworkImport(id, name, url, systemSources.firstOrNull { it.id == id }?.generation)
    }

    private fun setStatusText(value: CharSequence?) {
        statusText = value
        renderPageState(draft?.hasChanges == true, busy, statusText)
    }

    private fun refreshPage() {
        if (::pageFragment.isInitialized) pageFragment.refresh()
        renderPageState(draft?.hasChanges == true, busy, statusText)
    }

    companion object {
        private const val REQUEST_IMPORT = 100
        private const val REQUEST_EXPORT = 101
        private const val DEFAULT_EXPORT_NAME = "SKK-JISYO.user.utf8"
        private const val BUILTIN_FIXTURE_ID = "__builtin_fixture__"
        private const val STATE_KIND = "dictionary.import.kind"
        private const val STATE_ENCODING = "dictionary.import.encoding"
        private const val STATE_ID = "dictionary.import.id"
        private const val STATE_NAME = "dictionary.import.name"
        private const val STATE_ORIGIN_URL = "dictionary.import.origin_url"
        private const val STATE_GENERATION = "dictionary.import.generation"
        private const val STATE_PICKER = "dictionary.picker.operation"
        private const val STATE_IMPORT_ERROR = "dictionary.import.error"
        private const val STATE_IMPORT_IN_PROGRESS = "dictionary.import.in_progress"
        private const val STATE_ENCODING_SELECTION = "dictionary.encoding.selection"
        private const val STATE_DRAFT = "dictionary.settings.draft"
        const val EXTRA_INITIAL_S_DICTIONARY = "se.haya.skk.extra.INITIAL_S_DICTIONARY"
        internal var managerFactoryForTest: ((DictionarySettingsActivity) -> DictionaryManager)? = null
        internal var fileExecutorFactoryForTest: (() -> Executor)? = null
        internal var networkDownloadForTest: ((String, NetworkDictionaryCancellation) -> NetworkDictionaryDownload)? = null
        internal var networkUiExecutorForTest: Executor? = null
    }
}

internal enum class DictionarySourceAvailability { AVAILABLE, UNKNOWN }

internal fun hasMatchingPersistedReadGrant(target: Uri, grants: Iterable<Pair<Uri, Boolean>>): Boolean =
    grants.any { (uri, readable) -> readable && uri == target }

internal fun dictionarySourceDisplayName(source: DictionarySourceInfo): String =
    NetworkDictionaryCatalog.entries.firstOrNull { source.id == "official-skk-${it.key}" }
        ?.displayName ?: source.name

internal fun sourceAvailabilityForStatus(status: DictionaryManagerStatus): DictionarySourceAvailability = when (status) {
    is DictionaryManagerStatus.Ready -> if (status.freshness == DictionaryFreshness.CURRENT) {
        DictionarySourceAvailability.AVAILABLE
    } else {
        DictionarySourceAvailability.UNKNOWN
    }
    DictionaryManagerStatus.Loading, is DictionaryManagerStatus.Unavailable -> DictionarySourceAvailability.UNKNOWN
}

internal sealed interface DictionarySourceOperation {
    val sourceId: String

    data class Processing(override val sourceId: String) : DictionarySourceOperation
    data class Failed(override val sourceId: String) : DictionarySourceOperation
}

internal enum class DictionarySourceRowStatus { AVAILABLE, UNKNOWN, PROCESSING, FAILED }

internal fun sourceRowStatus(
    sourceId: String,
    availability: DictionarySourceAvailability,
    operation: DictionarySourceOperation?,
): DictionarySourceRowStatus = when {
    availability == DictionarySourceAvailability.UNKNOWN -> DictionarySourceRowStatus.UNKNOWN
    operation?.sourceId != sourceId -> DictionarySourceRowStatus.AVAILABLE
    operation is DictionarySourceOperation.Processing -> DictionarySourceRowStatus.PROCESSING
    operation is DictionarySourceOperation.Failed -> DictionarySourceRowStatus.FAILED
    else -> DictionarySourceRowStatus.AVAILABLE
}

internal enum class DictionaryPickerOperation { IMPORT, EXPORT }

internal class DictionaryPickerState private constructor(
    var pending: DictionaryPickerOperation?,
) {
    constructor() : this(null)

    val isPending: Boolean get() = pending != null

    fun begin(operation: DictionaryPickerOperation) {
        check(pending == null)
        pending = operation
    }

    fun consume(operation: DictionaryPickerOperation): Boolean {
        if (pending != operation) return false
        pending = null
        return true
    }

    fun savedValue(): String? = pending?.name

    companion object {
        fun restore(value: String?): DictionaryPickerState = DictionaryPickerState(
            value?.let { runCatching { DictionaryPickerOperation.valueOf(it) }.getOrNull() },
        )
    }
}

internal fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
    require(maxBytes >= 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return output.toByteArray()
        if (read == 0) continue
        total += read
        if (total > maxBytes) throw DictionaryFileTooLargeException()
        output.write(buffer, 0, read)
    }
}

internal class DictionaryFileTooLargeException : IllegalArgumentException("辞書ファイルが上限を超えています")
