package jp.hayase.skk.input

import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import android.view.inputmethod.InputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import jp.hayase.skk.core.BasicSkkAction
import jp.hayase.skk.core.BasicSkkDictionary
import jp.hayase.skk.core.BasicSkkEngine
import jp.hayase.skk.core.BasicSkkView
import jp.hayase.skk.core.BasicSkkEffect
import jp.hayase.skk.core.BasicSkkResult
import jp.hayase.skk.core.CompletionConfig
import jp.hayase.skk.core.editing.EditCommand
import jp.hayase.skk.core.InputPhase
import jp.hayase.skk.core.RegistrationPolicy
import jp.hayase.skk.core.RegistrationSaveRequest
import jp.hayase.skk.core.RegistrationSaveOutcome
import jp.hayase.skk.core.RegistrationSaveCompletion
import jp.hayase.skk.core.RegistrationSaveFailure
import jp.hayase.skk.core.CandidateCommitRequest
import jp.hayase.skk.core.CandidateDeletionRequest
import jp.hayase.skk.core.CandidateDeletionOutcome
import jp.hayase.skk.core.CandidateDeletionCompletion
import jp.hayase.skk.core.dictionary.DictionaryReadScope
import jp.hayase.skk.core.dictionary.DeferredDictionaryReadException
import jp.hayase.skk.dictionary.BuiltinDictionary

/**
 * 入力接続をセッションに固定し、終了後の新しい要求を送りません。
 * 送信済みの標準キーは非同期に配送されるため、配送時のフォーカスは Android に依存します。
 */
class EditorSession(
    val generation: Long,
    private val connection: InputConnection,
    val protectedInput: Boolean,
    val learningAllowed: Boolean,
    initialStart: Int,
    initialEnd: Int,
    dictionary: BasicSkkDictionary = BuiltinDictionary.dictionary,
    private val registrationSaver: ((RegistrationSaveRequest, (RegistrationSaveOutcome) -> Unit) -> Unit)? = null,
    private val onStateChanged: () -> Unit = {},
    private val candidateLearner: ((CandidateCommitRequest, (RegistrationSaveOutcome) -> Unit) -> Unit)? = null,
    private val candidateDeleter: ((CandidateDeletionRequest, (CandidateDeletionOutcome) -> Unit) -> Unit)? = null,
    completionConfig: CompletionConfig = CompletionConfig(),
    romanRuleSet: jp.hayase.skk.core.romaji.RomanRuleSet = jp.hayase.skk.core.romaji.RomanRuleSet.standard,
    punctuationConfig: jp.hayase.skk.core.PunctuationConfig = jp.hayase.skk.core.PunctuationConfig(),
    candidateDisplayConfig: jp.hayase.skk.core.CandidateDisplayConfig = jp.hayase.skk.core.CandidateDisplayConfig(),
    candidatePageSizeProvider: () -> Int = { candidateDisplayConfig.fixedPageSize },
    private val emacsEnabled: Boolean = false,
    private val callbackExecutor: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
    private val dictionaryExecutor: Executor = DICTIONARY_EXECUTOR,
    editPortFactory: (InputConnection, Long, () -> EditorEditState, Executor,
        (EditorEditState, Int) -> Boolean) -> EditorEditPort = { input, token, state, callbacks, before ->
        EditorEditPort(input, token, state, callbacks, beforeRequest = before)
    },
) {
    val engine = BasicSkkEngine(dictionary, RegistrationPolicy(
        enabled = registrationSaver != null,
        sessionGeneration = generation,
        savingAllowed = learningAllowed,
    ), learningEnabled = candidateLearner != null, deletionEnabled = candidateDeleter != null,
        completionConfig = completionConfig, romanRuleSet = romanRuleSet,
        punctuationConfig = punctuationConfig, candidateDisplayConfig = candidateDisplayConfig,
        candidatePageSizeProvider = candidatePageSizeProvider)
    var view = BasicSkkView(null, null, null)
        private set
    var notice: String? = null
        private set
    var active = true
        private set
    var failed = false
        private set
    private var selectionStart = initialStart
    private var selectionEnd = initialEnd
    private var composingStart = -1
    private var composingEnd = -1
    private var hasEditorComposition = false
    private val compositionMarkersRequested = candidateDisplayConfig.showCompositionMarkers && !protectedInput
    private var lastEditorText = ""
    private var lastEditorMarkerLength = 0

    private var dictionaryRevision = 0L
    private var pendingReadScope: DictionaryReadScope? = null
    var dictionaryReadPending = false
        private set
    private val queuedDictionaryOperations = ArrayDeque<() -> Boolean>()

    /** 未処理のキーは辞書の到着後に最新の候補状態で解釈します。 */
    fun deferKey(operation: () -> Boolean): Boolean {
        if (!dictionaryReadPending) return false
        enqueueDictionaryOperation(operation)
        return true
    }

    /** 既に受け取った待機キーが確定後に素通しとなる場合、同じ入力接続へ配送します。 */
    fun replayUnhandledKey(event: KeyEvent): Boolean {
        if (!active || failed) return true
        preserveTextInternal(invalidateQueuedInput = false)
        connection.sendKeyEvent(event)
        connection.sendKeyEvent(KeyEvent.changeAction(event, KeyEvent.ACTION_UP))
        return true
    }

    private fun enqueueDictionaryOperation(operation: () -> Boolean) {
        queuedDictionaryOperations.addLast(operation)
    }

    private fun invalidateDictionaryRead() {
        dictionaryRevision++
        pendingReadScope?.close()
        pendingReadScope = null
        dictionaryReadPending = false
        queuedDictionaryOperations.clear()
    }

    private fun dictionaryOperation(attempt: Int = 0, scope: DictionaryReadScope? = null,
        operation: () -> Boolean): Boolean {
        if (dictionaryReadPending) {
            enqueueDictionaryOperation(operation)
            return true
        }
        val readScope = scope ?: DictionaryReadScope()
        var deferred = false
        return try {
            readScope.run(operation)
        } catch (pending: DeferredDictionaryReadException) {
            if (attempt >= MAX_DICTIONARY_RETRIES) {
                invalidateDictionaryRead()
                notice = "辞書の検索を完了できませんでした。入力をやり直してください"
                onStateChanged()
                return true
            }
            deferred = true
            pendingReadScope = readScope
            dictionaryReadPending = true
            val revision = dictionaryRevision
            try {
                dictionaryExecutor.execute {
                    val failure = runCatching { pending.load() }.exceptionOrNull()
                    callbackExecutor.execute callback@{
                        if (!active || failed || revision != dictionaryRevision) return@callback
                        dictionaryReadPending = false
                        pendingReadScope = null
                        if (failure != null) {
                            readScope.close()
                            invalidateDictionaryRead()
                            notice = "辞書の検索に失敗しました。入力をやり直してください"
                        } else {
                            dictionaryOperation(attempt + 1, readScope, operation)
                            while (!dictionaryReadPending && queuedDictionaryOperations.isNotEmpty()) {
                                dictionaryOperation(operation = queuedDictionaryOperations.removeFirst())
                            }
                        }
                        onStateChanged()
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                invalidateDictionaryRead()
                notice = "辞書の検索を開始できませんでした"
            }
            true
        } finally {
            if (!deferred) readScope.close()
        }
    }

    private fun marker(): String {
        if (!compositionMarkersRequested || selectionStart < 0 || readEditorSnapshot() == null) return ""
        return when {
            view.registration != null -> "▽"
            view.candidate != null -> "▼"
            engine.state.phase == InputPhase.READING || engine.state.phase == InputPhase.ABBREV -> "▽"
            else -> ""
        }
    }

    private fun readEditorSnapshot(): ExtractedText? =
        connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 8192 }, 0)
            ?.takeIf { it.text != null && it.startOffset >= 0 && it.partialStartOffset < 0 &&
                it.selectionStart in 0..it.text.length && it.selectionEnd in 0..it.text.length &&
                it.startOffset.toLong() + it.text.length <= Int.MAX_VALUE }

    private data class Selection(val start: Int, val end: Int, val composingStart: Int, val composingEnd: Int)
    private val expectedSelections = ArrayDeque<Selection>()

    private data class ExternalSelection(val revision: Long, val target: Int)
    private val editState = AtomicReference(EditorEditState(true, generation, initialStart, initialEnd, 0,
        protectedInput = protectedInput))
    private val externalSelections = ArrayDeque<ExternalSelection>()
    private var externalPending = false
    private val queuedExternalCommands = ArrayDeque<EditCommand>()
    private var editNoticeShown = false
    private val editPort = editPortFactory(connection, generation, { editState.get() }, callbackExecutor) { initial, target ->
        synchronized(externalSelections) {
            if (editState.get() != initial) false else {
                externalSelections.addLast(ExternalSelection(initial.revision, target))
                // 遅延通知を次の操作まで保持し、通知のない入力先でも履歴を制限します。
                while (externalSelections.size > 64) externalSelections.removeFirst()
                true
            }
        }
    }

    private fun publishEditState(invalidate: Boolean = false) {
        synchronized(externalSelections) {
            if (invalidate) {
                editPort.resetNativeNavigation()
                externalSelections.clear()
                queuedExternalCommands.clear()
            }
            editState.updateAndGet { old -> EditorEditState(active && !failed, generation, selectionStart,
                selectionEnd, old.revision + if (invalidate) 1 else 0, protectedInput,
                hasComposition || expectedSelections.isNotEmpty()) }
        }
    }

    val displayedComposition: String
        get() = view.candidate?.committedText ?: view.composing.orEmpty()
    val hasComposition: Boolean
        get() = displayedComposition.isNotEmpty() || engine.state.phase != InputPhase.IDLE || view.registration != null

    fun handle(action: BasicSkkAction): Boolean {
        if (!active || protectedInput || failed) return false
        if (action is BasicSkkAction.Edit && !emacsEnabled) return false
        if (action == BasicSkkAction.Cancel) invalidateDictionaryRead()
        return dictionaryOperation { handleReady(action) }
    }

    private fun handleReady(action: BasicSkkAction): Boolean {
        if (action is BasicSkkAction.Edit && !hasComposition) {
            if (externalPending) {
                // 事後確認中の連打は版を変えず、本文を持たないコマンドだけを待機させます。
                if (queuedExternalCommands.size < MAX_QUEUED_EXTERNAL_COMMANDS) {
                    queuedExternalCommands.addLast(action.command)
                }
            } else {
                submitExternal(action.command)
            }
            return true
        }
        publishEditState(invalidate = true)
        val result = engine.dispatch(action)
        return applyResult(result).also { publishEditState() }
    }

    private fun submitExternal(command: EditCommand) {
        publishEditState()
        val revision = editState.get().revision
        externalPending = true
        editPort.submit(command) { result ->
            externalPending = false
            if (!active || failed) return@submit
            when (result.outcome) {
                EditorEditResult.Outcome.APPLIED, EditorEditResult.Outcome.NO_CHANGE -> {
                    if (editState.get().revision != revision) {
                        queuedExternalCommands.clear()
                        return@submit
                    }
                    selectionStart = result.expectedSelectionStart ?: selectionStart
                    selectionEnd = result.expectedSelectionEnd ?: selectionEnd
                    publishEditState()
                    // 選択を確定した後で次の一件を新しく取得・検証します。変更要求は再送しません。
                    if (queuedExternalCommands.isNotEmpty()) {
                        submitExternal(queuedExternalCommands.removeFirst())
                    }
                }
                EditorEditResult.Outcome.NATIVE_ISSUED -> {
                    synchronized(externalSelections) { externalSelections.clear() }
                    if (editState.get().revision != revision) {
                        queuedExternalCommands.clear()
                        return@submit
                    }
                    publishEditState()
                    if (queuedExternalCommands.isNotEmpty()) {
                        submitExternal(queuedExternalCommands.removeFirst())
                    }
                }
                else -> {
                    synchronized(externalSelections) { externalSelections.clear() }
                    val canContinue = result.outcome == EditorEditResult.Outcome.FAILED_OR_UNKNOWN &&
                        result.reason in setOf(EditorEditResult.Reason.POSTCHECK_MISMATCH,
                            EditorEditResult.Reason.API_REJECTED) && editState.get().revision == revision
                    if (!canContinue) queuedExternalCommands.clear()
                    if (!editNoticeShown) {
                        editNoticeShown = true
                        notice = "この入力欄ではこの編集操作を利用できません"
                    }
                    // 入力先の変換や遅延で直前の結果を確認できなくても、後続の明示的な要求は
                    // 新しく取得・照合します。直前の操作は再送せず、別入力で版が変われば破棄します。
                    if (canContinue && queuedExternalCommands.isNotEmpty()) {
                        submitExternal(queuedExternalCommands.removeFirst())
                    }
                }
            }
            onStateChanged()
        }
    }

    private fun applyResult(result: BasicSkkResult): Boolean {
        if (!result.handled) return false
        publishEditState(invalidate = true)
        view = result.view
        notice = result.notice
        connection.beginBatchEdit()
        try {
            result.commit?.let { text ->
                expectReplacement(text.length, composing = false)
                if (!connection.commitText(text, 1)) return fail()
                hasEditorComposition = false
                lastEditorText = ""
                lastEditorMarkerLength = 0
            }
            val prefix = marker()
            val text = prefix + displayedComposition
            if (text.isNotEmpty() || hasEditorComposition) {
                expectReplacement(text.length, composing = text.isNotEmpty())
                if (!connection.setComposingText(text, 1)) return fail()
                hasEditorComposition = text.isNotEmpty()
                lastEditorText = text
                lastEditorMarkerLength = prefix.length
                if (text.isEmpty()) {
                    connection.finishComposingText()
                } else if (!placeInternalCursor(text)) {
                    return fail()
                }
            }
        } finally {
            connection.endBatchEdit()
        }
        result.effects.forEach { effect ->
            when (effect) {
                is BasicSkkEffect.SaveRegistration -> {
                    registrationSaver?.invoke(effect.request) { outcome ->
                        if (active && !failed && effect.request.token.sessionGeneration == generation) {
                            dictionaryOperation {
                                applyResult(engine.completeRegistration(RegistrationSaveCompletion(effect.request.token, outcome)))
                            }
                            onStateChanged()
                        }
                    }
                }
                is BasicSkkEffect.LearnCandidate -> {
                    if (result.commit != null && learningAllowed) {
                        var completed = false
                        candidateLearner?.invoke(effect.request) { outcome ->
                            if (!completed) {
                                completed = true
                                val intentionallySuppressed = outcome is RegistrationSaveOutcome.Failed &&
                                    outcome.reason == RegistrationSaveFailure.POLICY_REJECTED
                                if (active && !failed && outcome != RegistrationSaveOutcome.Applied && !intentionallySuppressed) {
                                    notice = when (outcome) {
                                        RegistrationSaveOutcome.SavedButNotApplied -> "学習は保存されましたが、検索辞書を更新できません。辞書を再読込してください"
                                        is RegistrationSaveOutcome.Failed -> "候補の学習を保存できませんでした。入力した文字は保持します"
                                        else -> null
                                    }
                                    onStateChanged()
                                }
                            }
                        }
                    }
                }
                is BasicSkkEffect.DeleteCandidate -> {
                    candidateDeleter?.invoke(effect.request) { outcome ->
                        if (active && !failed && effect.request.token.sessionGeneration == generation) {
                            if (dictionaryOperation {
                                applyResult(engine.completeCandidateDeletion(
                                    CandidateDeletionCompletion(effect.request.token, outcome),
                                ))
                            }) onStateChanged()
                        }
                    }
                }
            }
        }
        publishEditState()
        return true
    }

    /** 将来の非同期検索も、この世代と読みが一致する場合だけ結果を採用します。 */
    fun acceptsResult(token: Long, reading: String): Boolean =
        active && !failed && token == generation && displayedComposition == reading

    fun onSelection(start: Int, end: Int, candidatesStart: Int, candidatesEnd: Int): Boolean {
        if (!active || failed) return false
        if (editPort.isNativeNavigation && !hasComposition &&
            candidatesStart == -1 && candidatesEnd == -1) {
            // ネイティブ移動の通知は途中の位置へ遅れて届くことがあり、連打を取り消しません。
            // 続くネイティブ移動はこの位置から計画せず、入力先が保持する選択位置へ適用します。
            synchronized(externalSelections) { externalSelections.clear() }
            selectionStart = start
            selectionEnd = end
            publishEditState()
            return false
        }
        synchronized(externalSelections) {
            val revision = editState.get().revision
            val index = if (candidatesStart == -1 && candidatesEnd == -1 && start == end)
                externalSelections.indexOfFirst { it.revision == revision && it.target == start } else -1
            if (index >= 0) {
                val hasNewerRequest = index < externalSelections.lastIndex
                repeat(index + 1) { externalSelections.removeFirst() }
                // 古い要求の通知で、より新しい要求が使う選択位置を巻き戻しません。
                if (!hasNewerRequest) {
                    selectionStart = start
                    selectionEnd = end
                    publishEditState()
                }
                return false
            }
        }
        publishEditState(invalidate = true)
        val observed = Selection(start, end, candidatesStart, candidatesEnd)
        if (selectionStart < 0 && hasEditorComposition && candidatesStart >= 0 &&
            start == end && end in candidatesStart..candidatesEnd &&
            candidatesEnd - candidatesStart == lastEditorText.length) {
            selectionStart = start
            selectionEnd = end
            composingStart = candidatesStart
            composingEnd = candidatesEnd
            publishEditState()
            return false
        }
        // 終了済み span の外部移動が過去の自分の通知と同じ位置でも、実際の選択を優先します。
        val markerFinishedExternally = lastEditorMarkerLength > 0 && candidatesStart == -1 &&
            candidatesEnd == -1 && readEditorSnapshot()?.let {
                it.startOffset + it.selectionStart == start && it.startOffset + it.selectionEnd == end
            } == true
        val index = if (markerFinishedExternally) -1 else expectedSelections.indexOf(observed)
        if (index >= 0) {
            val hasNewerExpectedSelection = index < expectedSelections.lastIndex
            repeat(index + 1) { expectedSelections.removeFirst() }
            if (!hasNewerExpectedSelection) {
                selectionStart = start
                selectionEnd = end
                composingStart = candidatesStart
                composingEnd = candidatesEnd
            }
            publishEditState()
            return false
        }
        if (observed == Selection(selectionStart, selectionEnd, composingStart, composingEnd)) return false
        // 外部移動時は実際の本文を照合して表示記号だけを除き、本文と移動先を保持します。
        selectionStart = start
        selectionEnd = end
        preserveText()
        publishEditState()
        return true
    }

    fun preserveText() = preserveTextInternal(invalidateQueuedInput = true)

    private fun preserveTextInternal(invalidateQueuedInput: Boolean) {
        if (invalidateQueuedInput) invalidateDictionaryRead()
        if (!active) return
        publishEditState(invalidate = true)
        val finish = hasEditorComposition || composingStart >= 0
        val markerRemoved = removeCompositionMarker()
        clearCoreComposition()
        if (!markerRemoved) notice = "入力先の変更を確認できないため、表示記号を除去できませんでした"
        expectedSelections.clear()
        composingStart = -1
        composingEnd = -1
        hasEditorComposition = false
        lastEditorText = ""
        lastEditorMarkerLength = 0
        if (finish) connection.finishComposingText()
        publishEditState()
    }

    /** 終了済みの composing span へ本文を再挿入せず、照合できた表示記号だけを除きます。 */
    private fun removeCompositionMarker(): Boolean {
        if (lastEditorMarkerLength == 0) return true
        if (composingStart < 0) return false
        val snapshot = readEditorSnapshot() ?: return false
        val text = snapshot.text
        val relativeStart = composingStart - snapshot.startOffset
        if (relativeStart < 0 || relativeStart + lastEditorText.length > text.length ||
            !text.subSequence(relativeStart, relativeStart + lastEditorText.length).toString()
                .equals(lastEditorText)) return false
        val liveStart = snapshot.startOffset + snapshot.selectionStart
        val liveEnd = snapshot.startOffset + snapshot.selectionEnd
        val markerEnd = composingStart + lastEditorMarkerLength
        fun adjusted(position: Int): Int = when {
            position <= composingStart -> position
            position <= markerEnd -> composingStart
            else -> position - lastEditorMarkerLength
        }
        connection.beginBatchEdit()
        try {
            if (!connection.finishComposingText()) return false
            if (!connection.setSelection(composingStart, markerEnd)) return false
            if (!connection.commitText("", 1)) {
                connection.setSelection(liveStart, liveEnd)
                return false
            }
            selectionStart = adjusted(liveStart)
            selectionEnd = adjusted(liveEnd)
            return connection.setSelection(selectionStart, selectionEnd)
        } finally {
            connection.endBatchEdit()
        }
    }

    fun close() {
        if (!active) return
        preserveText()
        active = false
        publishEditState(invalidate = true)
        editPort.close()
    }

    private fun placeInternalCursor(text: String): Boolean {
        val requested = if (view.candidate != null) text.length else
            view.cursor?.plus(lastEditorMarkerLength) ?: text.length
        if (requested !in 0..text.length) return false
        if (requested == text.length || composingStart < 0 || composingEnd < composingStart) return true
        val absolute = composingStart + requested
        if (absolute !in composingStart..composingEnd) return false
        selectionStart = absolute
        selectionEnd = absolute
        expectedSelections.addLast(Selection(absolute, absolute, composingStart, composingEnd))
        trimExpectedSelections()
        return connection.setSelection(absolute, absolute)
    }

    private fun clearCoreComposition() {
        view = engine.resetComposition()
        notice = null
    }

    private fun expectReplacement(length: Int, composing: Boolean) {
        val start = if (composingStart >= 0) composingStart else minOf(selectionStart, selectionEnd)
        if (start >= 0) {
            selectionStart = start + length
            selectionEnd = selectionStart
            composingStart = if (composing) start else -1
            composingEnd = if (composing) selectionStart else -1
            expectedSelections.addLast(Selection(selectionStart, selectionEnd, composingStart, composingEnd))
            trimExpectedSelections()
        }
    }

    private fun trimExpectedSelections() {
        // 通知が来ない入力先でも無制限に履歴を保持しません。
        while (expectedSelections.size > 64) expectedSelections.removeFirst()
    }

    private fun fail(): Boolean {
        failed = true
        preserveText()
        // 一部の出力が成功した可能性があるため、同じキーを再配送しません。
        return true
    }

    companion object {
        private const val MAX_QUEUED_EXTERNAL_COMMANDS = 64
        private const val MAX_DICTIONARY_RETRIES = 64
        private val DICTIONARY_EXECUTOR = Executors.newSingleThreadExecutor { task ->
            Thread(task, "skk-dictionary-read").apply { isDaemon = true }
        }
    }
}
