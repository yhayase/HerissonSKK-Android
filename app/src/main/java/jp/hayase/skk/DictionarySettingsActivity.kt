package jp.hayase.skk

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import jp.hayase.skk.core.dictionary.SkkDictionaryCodec
import jp.hayase.skk.core.dictionary.SkkDictionaryDocument
import jp.hayase.skk.core.dictionary.SkkDictionaryEncoding
import jp.hayase.skk.dictionary.DictionaryManager
import jp.hayase.skk.dictionary.DictionaryFreshness
import jp.hayase.skk.dictionary.DictionaryManagerStatus
import jp.hayase.skk.dictionary.DictionaryManagerSubscription
import jp.hayase.skk.dictionary.DictionaryManagerWriteResult
import jp.hayase.skk.dictionary.DictionaryRuntime
import jp.hayase.skk.dictionary.DictionarySourceInfo
import jp.hayase.skk.dictionary.DictionarySourceKind
import jp.hayase.skk.dictionary.CandidateSuppressionInfo
import jp.hayase.skk.dictionary.CandidateSuppressionSnapshot
import jp.hayase.skk.dictionary.PersonalDictionaryExport

class DictionarySettingsActivity : Activity() {
    private enum class ImportKind { NEW_SYSTEM, UPDATE_SYSTEM, MERGE_PERSONAL, REPLACE_PERSONAL }

    private data class ImportRequest(
        val kind: ImportKind,
        val encoding: SkkDictionaryEncoding,
        val id: String? = null,
        val name: String? = null,
        val expectedGeneration: Long? = null,
    )

    private lateinit var manager: DictionaryManager
    private lateinit var encodingSpinner: Spinner
    private lateinit var addSystemButton: Button
    private lateinit var mergePersonalButton: Button
    private lateinit var replacePersonalButton: Button
    private lateinit var exportPersonalButton: Button
    private lateinit var reloadButton: Button
    private lateinit var sourceContainer: LinearLayout
    private lateinit var suppressionContainer: LinearLayout
    private lateinit var statusView: TextView
    private val fileExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var subscription: DictionaryManagerSubscription? = null
    private var active = false
    private var pickerState = DictionaryPickerState()
    private var pendingImport: ImportRequest? = null
    private var personalSource: DictionarySourceInfo? = null
    private var systemSources: List<DictionarySourceInfo> = emptyList()
    private var sourcesLoadedOnce = false
    private var busy = false
    private var sourceAvailability = DictionarySourceAvailability.UNKNOWN
    private var sourceOperation: DictionarySourceOperation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = true
        manager = DictionaryRuntime.get(this)
        pickerState = DictionaryPickerState.restore(savedInstanceState?.getString(STATE_PICKER))
        restorePendingImport(savedInstanceState)
        if (pickerState.pending == DictionaryPickerOperation.IMPORT && pendingImport == null) {
            pickerState.consume(DictionaryPickerOperation.IMPORT)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        content.addView(TextView(this).apply {
            setText(R.string.dictionary_settings_title)
            textSize = 22f
        })
        content.addView(TextView(this).apply { setText(R.string.dictionary_settings_description) })
        content.addView(TextView(this).apply { setText(R.string.dictionary_encoding_label) })
        encodingSpinner = Spinner(this).apply {
            adapter = ArrayAdapter.createFromResource(
                this@DictionarySettingsActivity,
                R.array.dictionary_encoding_choices,
                android.R.layout.simple_spinner_dropdown_item,
            )
            contentDescription = getString(R.string.dictionary_encoding_label)
        }
        content.addView(encodingSpinner)
        addSystemButton = Button(this).apply {
            setText(R.string.dictionary_add_system)
            setOnClickListener {
                launchImport(ImportRequest(ImportKind.NEW_SYSTEM, selectedEncoding(), id = UUID.randomUUID().toString()))
            }
        }
        content.addView(addSystemButton)
        mergePersonalButton = Button(this).apply {
            setText(R.string.dictionary_merge_personal)
            isEnabled = false
            setOnClickListener {
                launchImport(
                    ImportRequest(
                        ImportKind.MERGE_PERSONAL,
                        selectedEncoding(),
                        expectedGeneration = personalSource?.generation,
                    ),
                )
            }
        }
        content.addView(mergePersonalButton)
        replacePersonalButton = Button(this).apply {
            setText(R.string.dictionary_replace_personal)
            isEnabled = false
            setOnClickListener {
                launchImport(
                    ImportRequest(
                        ImportKind.REPLACE_PERSONAL,
                        selectedEncoding(),
                        expectedGeneration = personalSource?.generation,
                    ),
                )
            }
        }
        content.addView(replacePersonalButton)
        exportPersonalButton = Button(this).apply {
            setText(R.string.dictionary_export_personal)
            setOnClickListener { launchExport() }
        }
        content.addView(exportPersonalButton)
        content.addView(Button(this).apply {
            text = "完全辞書バックアップ・復元"
            setOnClickListener {
                startActivity(Intent(this@DictionarySettingsActivity, CompleteDictionaryBackupActivity::class.java))
            }
        })
        reloadButton = Button(this).apply {
            setText(R.string.dictionary_reload)
            setOnClickListener { reloadDictionary() }
        }
        content.addView(reloadButton)
        content.addView(TextView(this).apply {
            setText(R.string.dictionary_builtin_heading)
            textSize = 18f
        })
        content.addView(TextView(this).apply { setText(R.string.dictionary_builtin_fixture) })
        content.addView(TextView(this).apply {
            setText(R.string.dictionary_sources_heading)
            textSize = 18f
        })
        sourceContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(sourceContainer)
        content.addView(TextView(this).apply {
            text = "非表示にしたシステム候補"
            textSize = 18f
        })
        suppressionContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(suppressionContainer)
        statusView = TextView(this).apply {
            setText(R.string.dictionary_loading)
            isFocusable = true
        }
        content.addView(statusView)
        setContentView(ScrollView(this).apply { addView(content) })
        applySystemInsets()

        setBusy(pickerState.isPending)
        subscription = manager.observe { status -> if (active) handleManagerStatus(status) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingImport?.let { request ->
            outState.putString(STATE_KIND, request.kind.name)
            outState.putString(STATE_ENCODING, request.encoding.name)
            outState.putString(STATE_ID, request.id)
            outState.putString(STATE_NAME, request.name)
            request.expectedGeneration?.let { outState.putLong(STATE_GENERATION, it) }
        }
        pickerState.savedValue()?.let { outState.putString(STATE_PICKER, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        active = false
        subscription?.close()
        subscription = null
        fileExecutor.shutdownNow()
        super.onDestroy()
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
                readAndPreview(uri, request)
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
        )
    }

    private fun selectedEncoding(): SkkDictionaryEncoding = when (encodingSpinner.selectedItemPosition) {
        1 -> SkkDictionaryEncoding.UTF8
        2 -> SkkDictionaryEncoding.EUC_JP
        else -> SkkDictionaryEncoding.AUTO
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
                loaded.fold(
                    onSuccess = { (name, document) -> showPreview(name, request, document) },
                    onFailure = {
                        setBusy(false)
                        setStatus(R.string.dictionary_import_invalid)
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
        AlertDialog.Builder(this)
            .setTitle(R.string.dictionary_preview_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelPreview() }
            .setPositiveButton(R.string.dictionary_apply) { _, _ -> applyImport(name, request, document) }
            .setOnCancelListener { cancelPreview() }
            .show()
    }

    private fun applyImport(name: String, request: ImportRequest, document: SkkDictionaryDocument) {
        setStatus(R.string.dictionary_applying)
        val operationSourceId = request.id.takeIf { request.kind == ImportKind.UPDATE_SYSTEM }
        operationSourceId?.let(::beginSourceOperation)
        val callback: (DictionaryManagerWriteResult<DictionarySourceInfo>) -> Unit = { result ->
            if (active) {
                operationSourceId?.let { finishSourceOperation(it, result) }
                setBusy(false)
                showWriteResult(result)
                refreshSources()
            }
        }
        when (request.kind) {
            ImportKind.NEW_SYSTEM -> manager.importSystem(
                requireNotNull(request.id),
                name,
                document,
                callback = callback,
            )
            ImportKind.UPDATE_SYSTEM -> manager.importSystem(
                requireNotNull(request.id),
                requireNotNull(request.name),
                document,
                request.expectedGeneration,
                callback,
            )
            ImportKind.MERGE_PERSONAL -> manager.mergePersonal(document, request.expectedGeneration, callback)
            ImportKind.REPLACE_PERSONAL -> manager.replacePersonal(document, request.expectedGeneration, callback)
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
                        statusView.text = "個人辞書を書き出しました。非表示の指定 ${exported.excludedSuppressionCount} 件は含まれません。"
                        statusView.announceForAccessibility(statusView.text)
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
                    renderSources(result.value)
                    refreshSuppressions()
                }
                is DictionaryManagerWriteResult.SavedButNotApplied -> {
                    sourceAvailability = managerSourceAvailability()
                    renderSources(result.value)
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
                    suppressionContainer.removeAllViews()
                    suppressionContainer.addView(TextView(this).apply { text = "非表示候補を読み込めませんでした" })
                }
            }
        }
    }

    private fun renderSources(sources: List<DictionarySourceInfo>) {
        personalSource = sources.firstOrNull { it.kind == DictionarySourceKind.PERSONAL }
        systemSources = sources.filter { it.kind == DictionarySourceKind.SYSTEM }.sortedBy { it.order }
        drawSources()
        updatePrimaryControls()
        if (!sourcesLoadedOnce) {
            sourcesLoadedOnce = true
        }
    }

    private fun drawSources() {
        sourceContainer.removeAllViews()
        sourceContainer.addView(TextView(this).apply {
            setText(R.string.dictionary_personal_summary)
        })
        if (systemSources.isEmpty()) {
            sourceContainer.addView(TextView(this).apply { setText(R.string.dictionary_no_system_sources) })
        } else {
            systemSources.forEachIndexed { index, source -> sourceContainer.addView(sourceRow(source, index)) }
        }
    }

    private fun drawSuppressions(snapshot: CandidateSuppressionSnapshot) {
        suppressionContainer.removeAllViews()
        if (snapshot.suppressions.isEmpty()) {
            suppressionContainer.addView(TextView(this).apply { text = "非表示の候補はありません" })
            return
        }
        snapshot.suppressions.forEach { suppression ->
            suppressionContainer.addView(suppressionRow(suppression, snapshot.personalGeneration))
        }
    }

    private fun suppressionRow(
        suppression: CandidateSuppressionInfo,
        generation: Long,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 12, 0, 12)
        val key = suppression.key
        val sourceName = systemSources.firstOrNull { it.id == key.sourceId }?.name
            ?: if (key.sourceId == BUILTIN_FIXTURE_ID) "同梱の試験辞書" else "現在利用できない辞書"
        addView(TextView(this@DictionarySettingsActivity).apply { text = sourceName })
        addView(TextView(this@DictionarySettingsActivity).apply {
            text = "読み: ${key.entryKey}\n候補: ${key.templateText}\n送り: ${key.okuriCondition ?: "なし"}"
        })
        addView(Button(this@DictionarySettingsActivity).apply {
            text = "再表示する"
            isEnabled = !busy
            setOnClickListener { confirmRestoreSuppression(suppression, generation, sourceName) }
        })
    }

    private fun confirmRestoreSuppression(
        suppression: CandidateSuppressionInfo,
        generation: Long,
        sourceName: String,
    ) {
        if (busy) return
        AlertDialog.Builder(this)
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
                    statusView.text = "非表示の指定を解除しました"
                    statusView.announceForAccessibility(statusView.text)
                }
                is DictionaryManagerWriteResult.SavedButNotApplied -> {
                    statusView.text = "非表示の指定は解除しましたが、検索辞書を更新できませんでした。再読み込みしてください。"
                    statusView.announceForAccessibility(statusView.text)
                }
                DictionaryManagerWriteResult.Failed -> {
                    statusView.text = "候補を再表示できませんでした。一覧を更新します。"
                    statusView.announceForAccessibility(statusView.text)
                }
            }
            refreshSources()
        }
    }

    private fun sourceRow(source: DictionarySourceInfo, index: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 12, 0, 12)
        addView(TextView(this@DictionarySettingsActivity).apply {
            text = getString(
                R.string.dictionary_source_state,
                sourceRowStatusText(sourceRowStatus(source.id, sourceAvailability, sourceOperation)),
                getString(if (source.enabled) R.string.dictionary_source_enabled else R.string.dictionary_source_disabled),
                index + 1,
            )
        })
        addView(Switch(this@DictionarySettingsActivity).apply {
            text = getString(R.string.dictionary_system_summary, source.name)
            isChecked = source.enabled
            isEnabled = !busy
            setOnCheckedChangeListener { _, checked ->
                if (busy) return@setOnCheckedChangeListener
                beginSourceOperation(source.id)
                setBusy(true)
                manager.setSourceEnabled(source.id, checked) { result ->
                    if (!active) return@setSourceEnabled
                    finishSourceOperation(source.id, result)
                    setBusy(false)
                    showWriteResult(result)
                    refreshSources()
                }
            }
        })
        addView(LinearLayout(this@DictionarySettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@DictionarySettingsActivity).apply {
                setText(R.string.dictionary_move_up)
                isEnabled = !busy && index > 0
                setOnClickListener { moveSource(index, index - 1) }
            })
            addView(Button(this@DictionarySettingsActivity).apply {
                setText(R.string.dictionary_move_down)
                isEnabled = !busy && index < systemSources.lastIndex
                setOnClickListener { moveSource(index, index + 1) }
            })
            addView(Button(this@DictionarySettingsActivity).apply {
                setText(R.string.dictionary_update)
                isEnabled = !busy
                setOnClickListener {
                    launchImport(
                        ImportRequest(
                            ImportKind.UPDATE_SYSTEM,
                            selectedEncoding(),
                            source.id,
                            source.name,
                            source.generation,
                        ),
                    )
                }
            })
        })
        addView(Button(this@DictionarySettingsActivity).apply {
            setText(R.string.dictionary_remove)
            isEnabled = !busy
            setOnClickListener { confirmRemove(source) }
        })
    }

    private fun confirmRemove(source: DictionarySourceInfo) {
        if (busy || source.kind != DictionarySourceKind.SYSTEM) return
        setBusy(true)
        AlertDialog.Builder(this)
            .setTitle(R.string.dictionary_remove_title)
            .setMessage(getString(R.string.dictionary_remove_message, source.name))
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
        setStatus(R.string.dictionary_removing)
        beginSourceOperation(source.id)
        manager.removeSystem(source.id, source.generation) { result ->
            if (!active) return@removeSystem
            finishSourceOperation(source.id, result)
            setBusy(false)
            showWriteResult(result)
            refreshSources()
        }
    }

    private fun moveSource(from: Int, to: Int) {
        if (busy || from !in systemSources.indices || to !in systemSources.indices) return
        val reordered = systemSources.toMutableList().apply { add(to, removeAt(from)) }
        setBusy(true)
        setStatus(R.string.dictionary_applying)
        manager.setSystemOrder(reordered.map { it.id }) { result ->
            if (!active) return@setSystemOrder
            setBusy(false)
            showWriteResult(result)
            refreshSources()
        }
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
        if (::sourceContainer.isInitialized && sourcesLoadedOnce) drawSources()
        when (status) {
            DictionaryManagerStatus.Loading -> if (!busy) setStatus(R.string.dictionary_loading)
            is DictionaryManagerStatus.Ready -> {
                refreshSources()
                if (!busy) {
                    setStatus(
                        if (status.freshness == DictionaryFreshness.STALE) {
                            R.string.dictionary_reload_stale
                        } else {
                            R.string.dictionary_ready
                        },
                    )
                }
            }
            is DictionaryManagerStatus.Unavailable -> {
                refreshSources()
                if (!busy) setStatus(R.string.dictionary_reload_failed)
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        updatePrimaryControls()
        if (::sourceContainer.isInitialized && sourcesLoadedOnce) drawSources()
        if (::suppressionContainer.isInitialized) refreshSuppressions()
    }

    private fun beginSourceOperation(sourceId: String) {
        sourceOperation = DictionarySourceOperation.Processing(sourceId)
        if (::sourceContainer.isInitialized && sourcesLoadedOnce) drawSources()
    }

    private fun finishSourceOperation(sourceId: String, result: DictionaryManagerWriteResult<*>) {
        sourceOperation = when (result) {
            DictionaryManagerWriteResult.Failed -> DictionarySourceOperation.Failed(sourceId)
            is DictionaryManagerWriteResult.Applied, is DictionaryManagerWriteResult.SavedButNotApplied -> null
        }
        if (::sourceContainer.isInitialized && sourcesLoadedOnce) drawSources()
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

    private fun updatePrimaryControls() {
        if (!::addSystemButton.isInitialized) return
        addSystemButton.isEnabled = !busy
        mergePersonalButton.isEnabled = !busy && personalSource != null
        replacePersonalButton.isEnabled = !busy && personalSource != null
        exportPersonalButton.isEnabled = !busy
        reloadButton.isEnabled = !busy
        encodingSpinner.isEnabled = !busy
    }

    private fun encodingLabel(encoding: SkkDictionaryEncoding): String = when (encoding) {
        SkkDictionaryEncoding.UTF8 -> getString(R.string.dictionary_encoding_utf8)
        SkkDictionaryEncoding.EUC_JP -> getString(R.string.dictionary_encoding_eucjp)
        SkkDictionaryEncoding.AUTO -> getString(R.string.dictionary_encoding_auto)
    }

    private fun operationLabel(request: ImportRequest): String = when (request.kind) {
        ImportKind.NEW_SYSTEM -> getString(R.string.dictionary_operation_add_system)
        ImportKind.UPDATE_SYSTEM -> getString(
            R.string.dictionary_operation_update_system,
            requireNotNull(request.name),
        )
        ImportKind.MERGE_PERSONAL -> getString(R.string.dictionary_operation_merge_personal)
        ImportKind.REPLACE_PERSONAL -> getString(R.string.dictionary_operation_replace_personal)
    }

    private fun setStatus(message: Int) {
        if (active) {
            statusView.setText(message)
            statusView.announceForAccessibility(statusView.text)
        }
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
        private const val STATE_GENERATION = "dictionary.import.generation"
        private const val STATE_PICKER = "dictionary.picker.operation"
    }
}

internal enum class DictionarySourceAvailability { AVAILABLE, UNKNOWN }

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
        if (total > maxBytes) throw IllegalArgumentException("辞書ファイルが上限を超えています")
        output.write(buffer, 0, read)
    }
}
