package jp.hayase.skk.input

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import android.view.inputmethod.InputConnection
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
import jp.hayase.skk.dictionary.BuiltinDictionary

/** 入力接続をセッションに固定し、後から別の入力欄へ出力しません。 */
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
                else -> {
                    synchronized(externalSelections) { externalSelections.clear() }
                    queuedExternalCommands.clear()
                    if (!editNoticeShown) {
                        editNoticeShown = true
                        notice = "この入力欄ではこの編集操作を利用できません"
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
            }
            val text = displayedComposition
            if (text.isNotEmpty() || hasEditorComposition) {
                expectReplacement(text.length, composing = text.isNotEmpty())
                if (!connection.setComposingText(text, 1)) return fail()
                hasEditorComposition = text.isNotEmpty()
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
                            applyResult(engine.completeRegistration(RegistrationSaveCompletion(effect.request.token, outcome)))
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
                            val completion = engine.completeCandidateDeletion(
                                CandidateDeletionCompletion(effect.request.token, outcome),
                            )
                            if (applyResult(completion)) onStateChanged()
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
            candidatesEnd - candidatesStart == displayedComposition.length) {
            selectionStart = start
            selectionEnd = end
            composingStart = candidatesStart
            composingEnd = candidatesEnd
            publishEditState()
            return false
        }
        val index = expectedSelections.indexOf(observed)
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
        // 外部移動後は範囲を編集せず、既に表示された文字をその位置に残します。
        preserveText()
        selectionStart = start
        selectionEnd = end
        publishEditState()
        return true
    }

    fun preserveText() {
        if (!active) return
        publishEditState(invalidate = true)
        val finish = hasEditorComposition || composingStart >= 0
        clearCoreComposition()
        expectedSelections.clear()
        composingStart = -1
        composingEnd = -1
        hasEditorComposition = false
        if (finish) connection.finishComposingText()
        publishEditState()
    }

    fun close() {
        if (!active) return
        preserveText()
        active = false
        publishEditState(invalidate = true)
        editPort.close()
    }

    private fun placeInternalCursor(text: String): Boolean {
        val requested = if (view.candidate != null) text.length else view.cursor ?: text.length
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
    }
}
