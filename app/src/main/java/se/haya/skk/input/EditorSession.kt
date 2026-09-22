package se.haya.skk.input

import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import android.view.inputmethod.InputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.core.BasicSkkDictionary
import se.haya.skk.core.BasicSkkEngine
import se.haya.skk.core.BasicSkkView
import se.haya.skk.core.BasicSkkEffect
import se.haya.skk.core.BasicSkkResult
import se.haya.skk.core.CompletionConfig
import se.haya.skk.core.editing.EditCommand
import se.haya.skk.core.InputPhase
import se.haya.skk.core.RegistrationPolicy
import se.haya.skk.core.RegistrationSaveRequest
import se.haya.skk.core.RegistrationSaveOutcome
import se.haya.skk.core.RegistrationSaveCompletion
import se.haya.skk.core.RegistrationSaveFailure
import se.haya.skk.core.CandidateCommitRequest
import se.haya.skk.core.CandidateDeletionRequest
import se.haya.skk.core.CandidateDeletionOutcome
import se.haya.skk.core.CandidateDeletionCompletion
import se.haya.skk.core.dictionary.DictionaryReadScope
import se.haya.skk.core.dictionary.DeferredDictionaryReadException
import se.haya.skk.dictionary.BuiltinDictionary

/**
 * 入力接続をセッションに固定し、終了後の新しい要求を送りません。
 * 送信済みの標準キーは非同期に配送されるため、配送時のフォーカスは Android に依存します。
 */
class EditorSession(
    val generation: Long,
    connection: InputConnection,
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
    romanRuleSet: se.haya.skk.core.romaji.RomanRuleSet = se.haya.skk.core.romaji.RomanRuleSet.standard,
    punctuationConfig: se.haya.skk.core.PunctuationConfig = se.haya.skk.core.PunctuationConfig(),
    candidateDisplayConfig: se.haya.skk.core.CandidateDisplayConfig = se.haya.skk.core.CandidateDisplayConfig(),
    candidatePageSizeProvider: () -> Int = { candidateDisplayConfig.fixedPageSize },
    candidatePageCapacityProvider: ((List<se.haya.skk.core.DictionaryCandidate>, Int) -> Int)? = null,
    private val emacsEnabled: Boolean = false,
    private val internalEmacsEnabled: Boolean = false,
    private val touchEditorAction: Int = android.view.inputmethod.EditorInfo.IME_ACTION_NONE,
    private val touchEditorMultiline: Boolean = true,
    private val touchEditorNoEnterAction: Boolean = false,
    private val confirmOnlyEnter: Boolean = false,
    private val copyKilledText: (String) -> Boolean = { true },
    private val ownsKilledText: (String) -> Boolean = { true },
    private val callbackExecutor: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
    private val dictionaryExecutor: Executor = DICTIONARY_EXECUTOR,
    editPortFactory: (InputConnection, Long, () -> EditorEditState, Executor,
        (EditorEditState, Int) -> Boolean) -> EditorEditPort = { input, token, state, callbacks, before ->
        EditorEditPort(input, token, state, callbacks, beforeRequest = before,
            copyKilledText = copyKilledText, ownsKilledText = ownsKilledText)
    },
) {
    private var connection = connection
    private val editPortFactory = editPortFactory
    private val predictionRevision = AtomicReference(AsyncPredictionRevision(generation, 0, 0))
    private val predictionDictionary = AsyncPredictionDictionary(
        dictionary, dictionaryExecutor, callbackExecutor, predictionRevision::get,
    ) { revision, _ -> refreshPredictionIfCurrent(revision) }
    val engine = BasicSkkEngine(predictionDictionary, RegistrationPolicy(
        enabled = registrationSaver != null,
        sessionGeneration = generation,
        savingAllowed = learningAllowed,
    ), learningEnabled = candidateLearner != null, deletionEnabled = candidateDeleter != null,
        completionConfig = completionConfig, romanRuleSet = romanRuleSet,
        punctuationConfig = punctuationConfig, candidateDisplayConfig = candidateDisplayConfig,
        candidatePageSizeProvider = candidatePageSizeProvider,
        candidatePageCapacityProvider = candidatePageCapacityProvider)
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
    private var touchCandidatePresentation = false

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

    /** 待機中に先行操作が入力段階を変えるため、画面操作の意味を実行時に決め直します。 */
    private fun deferTouchSemantic(operation: () -> Boolean): Boolean {
        editPort.breakKillChain()
        if (dictionaryReadPending) {
            enqueueDictionaryOperation(operation)
            return true
        }
        if (!externalPending) return false
        if (queuedExternalCommands.size < MAX_QUEUED_EXTERNAL_COMMANDS) {
            queuedExternalCommands.addLast(operation)
        }
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
        if (queuedDictionaryOperations.size >= MAX_QUEUED_DICTIONARY_OPERATIONS) {
            notice = "辞書の検索待ち入力が上限に達しました。追加の入力を受け付けられません"
            onStateChanged()
            return
        }
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
    private val contextSelections = ArrayDeque<Selection>()
    private var externalPending = false
    private val queuedExternalCommands = ArrayDeque<() -> Boolean>()
    private var dispatchingTouch = false
    private var editNoticeShown = false
    private var connectionEpoch = 0L
    private var editPort = createEditPort(connection)
    private var cachedConfigurationCheckpoint: ConfigurationCheckpoint? = null

    private fun createEditPort(input: InputConnection): EditorEditPort =
        editPortFactory(input, generation, { editState.get() }, callbackExecutor) { initial, target ->
        synchronized(externalSelections) {
            if (editState.get() != initial) false else {
                externalSelections.addLast(ExternalSelection(initial.revision, target))
                // 遅延通知を次の操作まで保持し、通知のない入力先でも履歴を制限します。
                while (externalSelections.size > 64) externalSelections.removeFirst()
                true
            }
        }
    }

    class ConfigurationCheckpoint internal constructor(
        val sessionGeneration: Long,
        internal val connectionEpoch: Long,
        val selectionStart: Int,
        val selectionEnd: Int,
        val composingStart: Int,
        val composingEnd: Int,
        internal val composingText: String,
        internal val snapshotStart: Int,
        internal val snapshotText: String,
    )

    /** 構成変更開始時には旧接続が無効な場合があるため、直前の自前更新後に検証した状態を返します。 */
    fun configurationCheckpoint(): ConfigurationCheckpoint? =
        cachedConfigurationCheckpoint?.takeIf(::matchesConfigurationCheckpoint)

    private fun checkpointFrom(extracted: ExtractedText): ConfigurationCheckpoint? {
        if (!canCacheConfigurationCheckpoint()) return null
        val snapshotText = extracted.text.toString()
        val snapshotStart = extracted.startOffset
        val relativeStart = composingStart - snapshotStart
        val relativeEnd = composingEnd - snapshotStart
        if (relativeStart < 0 || relativeEnd > snapshotText.length ||
            snapshotText.substring(relativeStart, relativeEnd) != lastEditorText ||
            snapshotStart.toLong() + extracted.selectionStart != selectionStart.toLong() ||
            snapshotStart.toLong() + extracted.selectionEnd != selectionEnd.toLong()
        ) return null
        return ConfigurationCheckpoint(generation, connectionEpoch, selectionStart, selectionEnd,
            composingStart, composingEnd, lastEditorText, snapshotStart, snapshotText)
    }

    private fun canCacheConfigurationCheckpoint(): Boolean =
        active && !failed && !protectedInput && !externalPending && hasEditorComposition &&
            composingStart >= 0 && composingEnd >= composingStart &&
            composingEnd - composingStart == lastEditorText.length && lastEditorText.isNotEmpty()

    private fun refreshConfigurationCheckpoint() {
        cachedConfigurationCheckpoint = if (canCacheConfigurationCheckpoint()) {
            configurationExtractedText(connection)?.let(::checkpointFrom)
        } else null
    }

    /** 構成変更中に Android が送る、本文不変で composing span だけ失った通知かを判定します。 */
    fun matchesConfigurationSpanLoss(
        checkpoint: ConfigurationCheckpoint,
        start: Int,
        end: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ): Boolean = matchesConfigurationCheckpoint(checkpoint) && start == checkpoint.selectionStart &&
        end == checkpoint.selectionEnd && candidatesStart == -1 && candidatesEnd == -1

    /**
     * 同じ入力欄だとサービスが検証した新接続へ、本文を書き直さず composing 範囲を移します。
     * 本文・選択・取得窓が一致しない場合は何も変更しません。
     */
    fun rebindAfterConfiguration(
        checkpoint: ConfigurationCheckpoint,
        newConnection: InputConnection,
    ): Boolean {
        if (!matchesConfigurationCheckpoint(checkpoint) || externalPending) return false
        val extracted = configurationExtractedText(newConnection) ?: return false
        val text = extracted.text.toString()
        if (extracted.startOffset != checkpoint.snapshotStart || text != checkpoint.snapshotText) return false
        val windowStart = extracted.startOffset.toLong()
        val windowEnd = windowStart + text.length
        val composingStart = checkpoint.composingStart.toLong()
        val composingEnd = checkpoint.composingEnd.toLong()
        if (composingStart < windowStart || composingEnd > windowEnd) return false
        val relativeStart = (composingStart - windowStart).toInt()
        val relativeEnd = (composingEnd - windowStart).toInt()
        if (text.substring(relativeStart, relativeEnd) != checkpoint.composingText) return false
        val absoluteSelectionStart = windowStart + extracted.selectionStart
        val absoluteSelectionEnd = windowStart + extracted.selectionEnd
        if (absoluteSelectionStart != checkpoint.selectionStart.toLong() ||
            absoluteSelectionEnd != checkpoint.selectionEnd.toLong()) return false
        val accepted = try {
            newConnection.beginBatchEdit()
            try {
                newConnection.setComposingRegion(checkpoint.composingStart, checkpoint.composingEnd)
            } finally {
                newConnection.endBatchEdit()
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) return false
        editPort.close()
        connectionEpoch++
        connection = newConnection
        synchronized(externalSelections) { externalSelections.clear() }
        queuedExternalCommands.clear()
        expectedSelections.clear()
        editPort = createEditPort(newConnection)
        publishEditState(invalidate = true)
        cachedConfigurationCheckpoint = checkpointFrom(extracted)
        return true
    }

    private fun matchesConfigurationCheckpoint(checkpoint: ConfigurationCheckpoint): Boolean =
        active && !failed && !protectedInput && checkpoint.sessionGeneration == generation &&
            checkpoint.connectionEpoch == connectionEpoch && hasEditorComposition &&
            selectionStart == checkpoint.selectionStart && selectionEnd == checkpoint.selectionEnd &&
            composingStart == checkpoint.composingStart && composingEnd == checkpoint.composingEnd &&
            lastEditorText == checkpoint.composingText

    private fun configurationExtractedText(input: InputConnection): ExtractedText? {
        val extracted = try {
            input.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = MAX_REBIND_TEXT_CHARS }, 0)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val text = extracted.text ?: return null
        if (text.length > MAX_REBIND_TEXT_CHARS || extracted.startOffset < 0 ||
            extracted.partialStartOffset >= 0 || extracted.selectionStart !in 0..text.length ||
            extracted.selectionEnd !in 0..text.length ||
            extracted.startOffset.toLong() + text.length > Int.MAX_VALUE
        ) return null
        return extracted
    }

    private fun publishEditState(invalidate: Boolean = false) {
        synchronized(externalSelections) {
            if (invalidate) {
                editPort.resetNativeNavigation()
                externalSelections.clear()
                contextSelections.clear()
                if (!dispatchingTouch || !active || failed) queuedExternalCommands.clear()
            }
            editState.updateAndGet { old -> EditorEditState(active && !failed, generation, selectionStart,
                selectionEnd, old.revision + if (invalidate) 1 else 0, protectedInput,
                hasComposition || expectedSelections.isNotEmpty()) }
        }
    }

    val displayedComposition: String
        get() = if (touchCandidatePresentation && view.candidate != null) view.composing.orEmpty()
            else view.candidate?.committedText ?: view.composing.orEmpty()
    val hasComposition: Boolean
        get() = displayedComposition.isNotEmpty() || engine.state.phase != InputPhase.IDLE || view.registration != null

    /** 遅延した座標通知を現在の入力接続・セッションへ結び付けるための期待値です。 */
    val expectedCursorSelection: Pair<Int, Int>
        get() = selectionStart to selectionEnd
    val expectedCursorComposition: Pair<Int, String>?
        get() = if (hasEditorComposition && composingStart >= 0) composingStart to lastEditorText else null

    // 物理キーの通常入力は独立した入力として保留中の外部編集を無効化します。
    // タッチ入力同士の順序保証は handleTouch の待機列で行います。
    fun handle(action: BasicSkkAction): Boolean {
        if (action != BasicSkkAction.Edit(EditCommand.KILL_LINE) && action != BasicSkkAction.RefreshPrediction) editPort.breakKillChain()
        if (!active || protectedInput || failed) return false
        if (action is BasicSkkAction.Edit && !acceptsEmacsEdit()) return false
        if (action == BasicSkkAction.Cancel) invalidateDictionaryRead()
        return dictionaryOperation { handleReady(action) }
    }

    /** 通常入力の確定後だけ、同じ Enter を入力欄の改行・アクションとして続けます。 */
    fun handleHardwareEnter(): Boolean {
        if (!active || protectedInput || failed) return false
        if (dictionaryReadPending) {
            enqueueDictionaryOperation { handleHardwareEnter() }
            return true
        }
        val forwards = shouldForwardConfirmedEnter()
        val handled = handle(BasicSkkAction.Enter)
        if (handled && forwards && active && !failed) dispatchEditorEnter()
        return handled
    }

    /** タッチ操作を、このセッションに固定した接続と EditorInfo 契約へ配送します。 */
    fun handleTouch(action: BasicSkkAction): Boolean {
        return handleTouch(action, forwardConfirmedEnter = true)
    }

    private fun handleTouch(action: BasicSkkAction, forwardConfirmedEnter: Boolean): Boolean {
        if (action != BasicSkkAction.Edit(EditCommand.KILL_LINE)) editPort.breakKillChain()
        if (!active || failed) return false
        if (externalPending) {
            if (queuedExternalCommands.size < MAX_QUEUED_EXTERNAL_COMMANDS) {
                queuedExternalCommands.addLast { handleTouch(action, forwardConfirmedEnter) }
            }
            return true
        }
        if (action == BasicSkkAction.Cancel) invalidateDictionaryRead()
        return dictionaryOperation {
            dispatchingTouch = true
            try { handleTouchReady(action, forwardConfirmedEnter) } finally { dispatchingTouch = false }
        }
    }

    /** 画面候補では入力先に読みを残し、物理候補では従来どおり選択候補を表示します。 */
    fun setTouchCandidatePresentation(enabled: Boolean): Boolean {
        if (touchCandidatePresentation == enabled) return true
        touchCandidatePresentation = enabled
        if (!active || failed || protectedInput) return true
        return handleTouch(BasicSkkAction.SetTouchPrediction(enabled))
    }

    /** 右下キーは未確定状態だけを確定し、その同じ押下を入力先アクションへ続けません。 */
    fun handleTouchPrimary(): Boolean {
        if (deferTouchSemantic(::handleTouchPrimary)) return true
        if (!active || failed) return false
        if (protectedInput) return dispatchEditorEnter()
        val action = when {
            view.prediction != null -> view.prediction!!.let {
                BasicSkkAction.CommitPrediction(0, it.items.firstOrNull(), it)
            }
            view.registration?.innerCandidate != null || view.candidate != null -> touchPageHeadAction()
            view.registration != null || engine.state.phase != InputPhase.IDLE ||
                engine.state.pendingRomaji.isNotEmpty() -> BasicSkkAction.Enter
            else -> return dispatchEditorEnter()
        }
        return handleTouch(action, forwardConfirmedEnter = false)
    }

    /** 画面の文字は通常変換中の候補ラベルとして解釈せず、ページ先頭確定後に一度だけ処理します。 */
    fun handleTouchText(text: String): Boolean {
        // 検索待ちの間に候補状態が変わり得るため、低水準のTextへ確定せず画面操作として再評価します。
        if (deferTouchSemantic { handleTouchText(text) }) return true
        if (view.candidate == null && view.registration?.innerCandidate == null) {
            return handleTouch(BasicSkkAction.Text(text))
        }
        return when (text) {
            " " -> {
                val candidate = view.registration?.innerCandidate ?: view.candidate
                handleTouch(if (candidate?.canNextPage == true) BasicSkkAction.NextCandidatePage
                    else BasicSkkAction.RegisterCandidate)
            }
            "x" -> {
                val candidate = view.registration?.innerCandidate ?: view.candidate
                handleTouch(if (candidate?.canPreviousPage == true) BasicSkkAction.PreviousCandidatePage
                    else BasicSkkAction.Cancel)
            }
            else -> handleTouch(touchPageHeadAction()) &&
                handleTouch(BasicSkkAction.Text(text))
        }
    }

    fun handleTouchSpaceOrConvert(): Boolean {
        if (deferTouchSemantic(::handleTouchSpaceOrConvert)) return true
        return when {
            view.prediction != null -> handleTouch(BasicSkkAction.ConvertNext)
            view.candidate != null || view.registration?.innerCandidate != null -> handleTouchText(" ")
            protectedInput || engine.state.phase == InputPhase.IDLE ->
                handleTouch(BasicSkkAction.Text(" ", interpretCommands = false))
            else -> handleTouch(BasicSkkAction.ConvertNext)
        }
    }

    /** 通常変換中の画面BackspaceはC-gとして読みへ戻し、読みの文字は次の押下で削除します。 */
    fun handleTouchBackspace(): Boolean {
        if (deferTouchSemantic(::handleTouchBackspace)) return true
        return handleTouch(
            if (view.candidate != null || view.registration?.innerCandidate != null) BasicSkkAction.Cancel
            else BasicSkkAction.Backspace,
        )
    }

    /** 登録見出しの取消は、内側の読みや候補より現在の登録階層を優先して破棄します。 */
    fun cancelTouchRegistration(): Boolean = handleTouch(BasicSkkAction.CancelRegistrationFrame)

    fun saveRegistration(expected: se.haya.skk.core.RegistrationView): Boolean {
        if (deferTouchSemantic { saveRegistration(expected) }) return true
        if (!active || failed || protectedInput || view.registration !== expected || expected.saving ||
            expected.body.isEmpty() || engine.state.phase != InputPhase.IDLE ||
            engine.state.pendingRomaji.isNotEmpty() || view.deletion != null) return false
        return handleTouch(BasicSkkAction.Enter, forwardConfirmedEnter = false)
    }

    fun cancelRegistration(expected: se.haya.skk.core.RegistrationView): Boolean {
        if (deferTouchSemantic { cancelRegistration(expected) }) return true
        if (!active || failed || protectedInput || view.registration !== expected || expected.saving) return false
        return handleTouch(BasicSkkAction.CancelRegistrationFrame, forwardConfirmedEnter = false)
    }

    /** 古い描画の座標を別の登録状態へ適用せず、確定済み本文のカーソルだけを移動します。 */
    fun moveRegistrationCursor(position: Int, expected: se.haya.skk.core.RegistrationView): Boolean {
        if (deferTouchSemantic { moveRegistrationCursor(position, expected) }) return true
        if (!active || failed || protectedInput || view.registration !== expected) return false
        advancePredictionInputRevision()
        return applyResult(engine.moveRegistrationCursor(position))
    }

    /** モード選択は未確定内容の確定成功を確認してから適用し、失敗時は切替を続けません。 */
    fun handleTouchSetInputMode(mode: se.haya.skk.core.InputMode): Boolean {
        if (deferTouchSemantic { handleTouchSetInputMode(mode) }) return true
        if (!active || failed || protectedInput || view.deletion != null || engine.state.registrationSaving) return false
        val confirmation = when {
            view.candidate != null || view.registration?.innerCandidate != null -> touchPageHeadAction()
            view.prediction != null || engine.state.phase != InputPhase.IDLE ||
                engine.state.pendingRomaji.isNotEmpty() -> BasicSkkAction.Enter
            else -> null
        }
        if (confirmation != null && !handleTouch(confirmation, forwardConfirmedEnter = false)) return false
        if (!active || failed || view.candidate != null || view.registration?.innerCandidate != null ||
            view.prediction != null ||
            engine.state.phase != InputPhase.IDLE || engine.state.pendingRomaji.isNotEmpty()) return false
        return handleTouch(BasicSkkAction.SetInputMode(mode))
    }

    private fun touchPageHeadAction(): BasicSkkAction {
        val candidate = view.registration?.innerCandidate ?: view.candidate
        val item = candidate?.pageItems?.firstOrNull()
        return if (item == null) BasicSkkAction.CommitPageHead
            else BasicSkkAction.SelectCandidate(item.index, item.candidate)
    }

    private fun handleTouchReady(action: BasicSkkAction, forwardConfirmedEnter: Boolean): Boolean {
        if (!active || failed) return false
        if (protectedInput) return handleProtectedTouch(action)
        if (action is BasicSkkAction.Edit && !acceptsEmacsEdit()) return false
        val forwards = forwardConfirmedEnter && action == BasicSkkAction.Enter && shouldForwardConfirmedEnter()
        if (handleReady(action)) {
            if (forwards && active && !failed) dispatchEditorEnter()
            return true
        }
        val external = when (action) {
            BasicSkkAction.Left -> EditCommand.LEFT
            BasicSkkAction.Right -> EditCommand.RIGHT
            BasicSkkAction.Backspace -> EditCommand.BACKSPACE
            BasicSkkAction.Delete -> EditCommand.DELETE
            else -> null
        }
        if (external != null) {
            submitExternal(external)
            return true
        }
        if (action == BasicSkkAction.Enter) return dispatchEditorEnter()
        return false
    }

    private fun handleProtectedTouch(action: BasicSkkAction): Boolean = when (action) {
        is BasicSkkAction.Text -> connection.commitText(action.text, 1)
        BasicSkkAction.Backspace -> connection.deleteSurroundingTextInCodePoints(1, 0)
        BasicSkkAction.Delete -> connection.deleteSurroundingTextInCodePoints(0, 1)
        BasicSkkAction.Left -> sendTouchNavigation(KeyEvent.KEYCODE_DPAD_LEFT)
        BasicSkkAction.Right -> sendTouchNavigation(KeyEvent.KEYCODE_DPAD_RIGHT)
        BasicSkkAction.Enter -> dispatchEditorEnter()
        else -> false
    }

    private fun sendTouchNavigation(keyCode: Int): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val down = connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        val up = connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        return down || up
    }

    private fun dispatchEditorEnter(): Boolean =
        if (!touchEditorNoEnterAction &&
            touchEditorAction != android.view.inputmethod.EditorInfo.IME_ACTION_NONE &&
            touchEditorAction != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
            connection.performEditorAction(touchEditorAction)
        } else if (!touchEditorMultiline) {
            sendTouchNavigation(KeyEvent.KEYCODE_ENTER)
        } else {
            connection.commitText("\n", 1)
        }

    private fun shouldForwardConfirmedEnter(): Boolean =
        engine.state.registrationDepth == 0 && !engine.state.registrationSaving &&
            view.deletion == null && !confirmOnlyEnter &&
            (engine.state.phase != InputPhase.IDLE || engine.state.pendingRomaji.isNotEmpty())

    private fun handleReady(action: BasicSkkAction): Boolean {
        if (action != BasicSkkAction.Edit(EditCommand.KILL_LINE) && action != BasicSkkAction.RefreshPrediction) {
            editPort.breakKillChain()
        }
        if (action == BasicSkkAction.Edit(EditCommand.NEWLINE) && acceptsEmacsEdit()) {
            if (externalPending) {
                if (queuedExternalCommands.size < MAX_QUEUED_EXTERNAL_COMMANDS) {
                    queuedExternalCommands.addLast { handle(action) }
                }
                return true
            }
            if (handleHardwareEnter()) return true
            // 素通しの物理 Enter と同じキーを、C-m の修飾状態を引き継がず送ります。
            editPort.expectNativeEnter()
            connection.clearMetaKeyStates(KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or
                KeyEvent.META_SHIFT_MASK or KeyEvent.META_META_MASK)
            sendTouchNavigation(KeyEvent.KEYCODE_ENTER)
            return true
        }
        if (action is BasicSkkAction.Edit && !acceptsEmacsEdit()) return false
        if (action is BasicSkkAction.Edit && !hasComposition && !ownsInternalEditing()) {
            if (!emacsEnabled) return false
            if (externalPending) {
                // 事後確認中の連打は版を変えず、後続のタッチ入力と同じ順序で待機します。
                if (queuedExternalCommands.size < MAX_QUEUED_EXTERNAL_COMMANDS) {
                    queuedExternalCommands.addLast { handle(action) }
                }
            } else {
                submitExternal(action.command)
            }
            return true
        }
        if (action != BasicSkkAction.RefreshPrediction) advancePredictionInputRevision()
        publishEditState(invalidate = true)
        val deleteCommittedCandidate = action == BasicSkkAction.Backspace &&
            engine.state.registrationDepth == 0 && view.candidate?.menu?.isEmpty() == true
        val result = engine.dispatch(action)
        val handled = applyResult(result)
        if (handled && deleteCommittedCandidate && active && !failed) {
            result.commit?.let { committed ->
                val plan = se.haya.skk.core.editing.Editing.plan(
                    se.haya.skk.core.editing.EditSnapshot(committed, committed.length),
                    EditCommand.BACKSPACE) as? se.haya.skk.core.editing.EditResult.Ready
                val removed = plan?.plan?.deletedRange
                if (removed != null) {
                    // 確定した候補の末尾一書記素だけを、同じ接続で確定の直後に削除します。
                    // 非同期の本文取得へ依存せず、読み直しや再送もしません。
                    val length = removed.end - removed.start
                    if (selectionStart >= length) {
                        selectionStart -= length
                        selectionEnd = selectionStart
                        expectedSelections.addLast(Selection(selectionStart, selectionEnd, -1, -1))
                        trimExpectedSelections()
                    }
                    if (!connection.deleteSurroundingText(length, 0)) fail()
                }
            }
        }
        publishEditState()
        return handled
    }

    private fun acceptsEmacsEdit(): Boolean = emacsEnabled || internalEmacsEnabled && ownsInternalEditing()

    private fun ownsInternalEditing(): Boolean =
        (engine.state.phase != InputPhase.IDLE || engine.state.pendingRomaji.isNotEmpty() ||
            engine.state.registrationDepth > 0 || engine.state.registrationSaving ||
            view.registration != null || view.deletion != null)

    private fun advancePredictionInputRevision() {
        predictionRevision.updateAndGet { it.copy(input = it.input + 1) }
    }

    /** 辞書公開版の変更後に、現在の読みだけを新しい版へ問い合わせ直します。 */
    fun refreshPredictionDictionary() {
        if (!active || failed) return
        predictionRevision.updateAndGet { it.copy(dictionary = it.dictionary + 1) }
        refreshPredictionStateOnly()
    }

    private fun refreshPredictionIfCurrent(revision: AsyncPredictionRevision) {
        if (!active || failed || predictionRevision.get() != revision) return
        refreshPredictionStateOnly()
        onStateChanged()
    }

    private fun refreshPredictionStateOnly() {
        val result = engine.dispatch(BasicSkkAction.RefreshPrediction)
        if (!result.handled) return
        view = result.view
        notice = result.notice
        publishEditState()
    }

    private fun submitExternal(command: EditCommand) {
        publishEditState()
        val revision = editState.get().revision
        val submittedConnectionEpoch = connectionEpoch
        externalPending = true
        editPort.submit(command) { result ->
            if (submittedConnectionEpoch != connectionEpoch) return@submit
            val contextRequest = editPort.isContextRequestPending
            editPort.finishContextRequest()
            if (contextRequest) {
                val observed = contextSelections.toList()
                contextSelections.clear()
                val known = result.expectedSelectionStart != null && result.expectedSelectionEnd != null
                if (known && observed.all { it.start == result.expectedSelectionStart &&
                        it.end == result.expectedSelectionEnd && it.composingStart == -1 && it.composingEnd == -1 }) {
                    // 要求中の通知が事後取得と一致した場合だけ、自分の編集として受け取ります。
                    selectionStart = result.expectedSelectionStart!!
                    selectionEnd = result.expectedSelectionEnd!!
                    if (observed.isNotEmpty()) synchronized(externalSelections) {
                        externalSelections.removeAll { it.revision == revision && it.target == selectionStart }
                    }
                    publishEditState()
                } else {
                    queuedExternalCommands.clear()
                    observed.forEach { onSelection(it.start, it.end, it.composingStart, it.composingEnd) }
                }
            }
            externalPending = false
            if (!active || failed) return@submit
            when (result.outcome) {
                EditorEditResult.Outcome.APPLIED, EditorEditResult.Outcome.NO_CHANGE -> {
                    if (editState.get().revision != revision) {
                        queuedExternalCommands.clear()
                        return@submit
                    }
                    notice = if (result.reason == EditorEditResult.Reason.END_OF_BUFFER) "End of buffer" else null
                    selectionStart = result.expectedSelectionStart ?: selectionStart
                    selectionEnd = result.expectedSelectionEnd ?: selectionEnd
                    publishEditState()
                    // 選択を確定した後で次の一件を新しく取得・検証します。変更要求は再送しません。
                    drainExternalCommands()
                }
                EditorEditResult.Outcome.NATIVE_ISSUED -> {
                    if (!contextRequest) synchronized(externalSelections) { externalSelections.clear() }
                    if (editState.get().revision != revision) {
                        queuedExternalCommands.clear()
                        return@submit
                    }
                    publishEditState()
                    drainExternalCommands()
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
                    if (canContinue) drainExternalCommands()
                }
            }
            onStateChanged()
        }
    }

    private fun drainExternalCommands() {
        while (active && !failed && !externalPending && queuedExternalCommands.isNotEmpty()) {
            queuedExternalCommands.removeFirst().invoke()
        }
    }

    private fun applyResult(result: BasicSkkResult): Boolean {
        if (!result.handled) return false
        cachedConfigurationCheckpoint = null
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
        refreshConfigurationCheckpoint()
        result.effects.forEach { effect ->
            when (effect) {
                is BasicSkkEffect.SaveRegistration -> {
                    registrationSaver?.invoke(effect.request) { outcome ->
                        if (active && !failed && effect.request.token.sessionGeneration == generation) {
                            advancePredictionInputRevision()
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
                            advancePredictionInputRevision()
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
            editPort.acknowledgeNativeSelection()
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
        if (editPort.isContextRequestPending && !hasComposition && contextSelections.size < 64) {
            contextSelections.addLast(Selection(start, end, candidatesStart, candidatesEnd))
            return false
        }
        // 自分が発行した外部編集の通知でない限り、位置が同じでも待機中の編集計画を失効します。
        // 構成変更用スナップショットは本文の変化が判明した分岐だけで別途破棄します。
        publishEditState(invalidate = true)
        val observed = Selection(start, end, candidatesStart, candidatesEnd)
        if (selectionStart < 0 && hasEditorComposition && candidatesStart >= 0 &&
            start == end && end in candidatesStart..candidatesEnd &&
            candidatesEnd - candidatesStart == lastEditorText.length) {
            cachedConfigurationCheckpoint = null
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
        cachedConfigurationCheckpoint = null
        selectionStart = start
        selectionEnd = end
        preserveText()
        publishEditState()
        return true
    }

    fun breakKillChain() = editPort.breakKillChain()

    fun preserveText() = preserveTextInternal(invalidateQueuedInput = true)

    private fun preserveTextInternal(invalidateQueuedInput: Boolean) {
        if (invalidateQueuedInput) invalidateDictionaryRead()
        if (!active) return
        cachedConfigurationCheckpoint = null
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
        predictionDictionary.close()
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
        advancePredictionInputRevision()
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
        private const val MAX_QUEUED_DICTIONARY_OPERATIONS = 1024
        private const val MAX_DICTIONARY_RETRIES = 64
        private const val MAX_REBIND_TEXT_CHARS = 16_384
        private val DICTIONARY_EXECUTOR = Executors.newSingleThreadExecutor { task ->
            Thread(task, "skk-dictionary-read").apply { isDaemon = true }
        }
    }
}
