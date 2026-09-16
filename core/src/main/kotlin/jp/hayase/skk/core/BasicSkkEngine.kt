package jp.hayase.skk.core

import jp.hayase.skk.core.romaji.KanaTransforms
import jp.hayase.skk.core.romaji.Romanizer
import jp.hayase.skk.core.dictionary.DictionaryUnavailableException
import jp.hayase.skk.core.dictionary.DictionaryUnavailableReason
import jp.hayase.skk.core.dictionary.CandidateSelection

/** フェーズ 2 の基本入力で利用する入力モードです。 */
enum class InputMode { HIRAGANA, KATAKANA, HALFWIDTH, DIRECT, FULLWIDTH }

/** 入力先とは独立してコアが所有する入力段階です。 */
enum class InputPhase { IDLE, READING, ABBREV, SELECTING }

/** 同期辞書へ渡す、表示用文字列を含まない検索条件です。 */
data class DictionaryQuery(
    val readingKey: String,
    val okuri: String? = null,
    val abbrev: Boolean = false,
)

/** 注釈と送り条件は候補本文から分離して保持します。 */
data class DictionaryCandidate(
    val text: String,
    val annotation: String? = null,
    val okuriCondition: String? = null,
    val learningTarget: NumericLearningTarget? = null,
    val selection: CandidateSelection? = null,
)

/** 数値展開後の表示候補を、永続化用の元辞書候補へ結びます。 */
data class NumericLearningTarget(
    val query: DictionaryQuery,
    val templateText: String,
    val annotation: String?,
    val okuriCondition: String?,
)

/** 登録本文を永続化する前に固定した、入力先へ確定する語幹です。 */
data class RegistrationPreparation(val committedStem: String)

/** 入力セッション中に固定する補完機能の設定です。 */
data class CompletionConfig(
    val manualEnabled: Boolean = true,
    val dynamicEnabled: Boolean = false,
)

/** 入力済み部分と、入力先へまだ反映しない提案部分を分離した表示です。 */
data class DynamicCompletionView(val prefix: String, val suffix: String)

/**
 * フェーズ 2 用の同期辞書境界です。
 *
 * 実装はメモリー上のデータだけを参照し、この呼び出し内でディスク I/O を行いません。
 * 辞書の優先順位、送り条件、重複を解決した最終表示順で候補を返します。
 */
fun interface BasicSkkDictionary {
    fun lookup(query: DictionaryQuery): List<DictionaryCandidate>

    /** 公開済みのメモリー内スナップショットから、前方一致する見出し語を返します。 */
    fun complete(query: CompletionQuery): List<String> = emptyList()

    /** 未登録語を保存する見出し語です。通常辞書は元の読みをそのまま使います。 */
    fun registrationQuery(original: DictionaryQuery): DictionaryQuery = original

    /** 登録本文から保存成功後に確定する語幹を作ります。 */
    fun prepareRegistration(original: DictionaryQuery, templateText: String): RegistrationPreparation =
        RegistrationPreparation(templateText)
}

/** Android のキー表から正規化して渡す基本操作です。 */
sealed interface BasicSkkAction {
    data class Text(val text: String) : BasicSkkAction
    data object Enter : BasicSkkAction
    data object Kana : BasicSkkAction
    data object Cancel : BasicSkkAction
    data object Backspace : BasicSkkAction
    data object Halfwidth : BasicSkkAction
    data object Left : BasicSkkAction
    data object Right : BasicSkkAction
    data object Home : BasicSkkAction
    data object End : BasicSkkAction
    data object Delete : BasicSkkAction
    data object DeleteCandidate : BasicSkkAction
    data object CompleteForward : BasicSkkAction
    data object CompleteBackward : BasicSkkAction
    data object AcceptDynamicCompletion : BasicSkkAction
}

data class CandidateView(
    val selected: DictionaryCandidate,
    val committedText: String,
    val index: Int,
    val total: Int,
    val menu: List<LabeledCandidate> = emptyList(),
)

data class LabeledCandidate(val label: Char, val candidate: DictionaryCandidate, val committedText: String)

/** 入力先 composition と候補 UI を一度に更新する完全なスナップショットです。 */
data class BasicSkkView(
    val composing: String?,
    val cursor: Int?,
    val candidate: CandidateView?,
    val registration: RegistrationView? = null,
    val deletion: CandidateDeletionView? = null,
    val completion: DynamicCompletionView? = null,
)

data class BasicSkkState(
    val mode: InputMode,
    val phase: InputPhase,
    val reading: String,
    val okuriConsonant: Char?,
    val okuri: String,
    val pendingRomaji: String,
    val cursor: Int,
    val candidateIndex: Int?,
    val registrationDepth: Int = 0,
    val registrationSaving: Boolean = false,
)

data class BasicSkkResult(
    val handled: Boolean,
    val commit: String? = null,
    val view: BasicSkkView,
    val notice: String? = null,
    val effects: List<BasicSkkEffect> = emptyList(),
)

/**
 * 基本入力と opt-in の登録状態を扱う、Android に依存しない SKK エンジンです。
 *
 * 登録・学習の永続化は効果と完了通知に分離します。削除と非同期検索は後続の実装です。
 */
class BasicSkkEngine(
    private val dictionary: BasicSkkDictionary,
    private val registrationPolicy: RegistrationPolicy,
    private val learningEnabled: Boolean = false,
    private val deletionEnabled: Boolean = false,
    private val completionConfig: CompletionConfig = CompletionConfig(),
) {
    constructor(dictionary: BasicSkkDictionary) : this(dictionary, RegistrationPolicy())

    private var mode = InputMode.HIRAGANA
    private var phase = InputPhase.IDLE
    private var buffer = EditableBuffer()
    private var readingStartMode = InputMode.HIRAGANA
    private var okuriBoundary: Int? = null
    private var okuriConsonant: Char? = null
    private var romanizer = Romanizer()
    private var candidates: List<DictionaryCandidate> = emptyList()
    private var candidateIndex = 0
    private var pendingTargetsOkuri = false
    private var selectionReturnState: ReadingSnapshot? = null
    private var selectionQuery: DictionaryQuery? = null
    private var selectionRegistrationQuery: DictionaryQuery? = null
    private val registrations = mutableListOf<RegistrationFrame>()
    private var nextFrameId = 1L
    private var nextOperationId = 1L
    private var deletion: DeletionState? = null
    private var completionCycle: CompletionCycle? = null
    private var dynamicCompletion: String? = null

    val state: BasicSkkState
        get() {
            val boundary = okuriBoundary
            return BasicSkkState(
                mode = mode,
                phase = phase,
                reading = if (boundary == null) buffer.text else buffer.text.substring(0, boundary),
                okuriConsonant = okuriConsonant,
                okuri = if (boundary == null) "" else buffer.text.substring(boundary),
                pendingRomaji = romanizer.pending,
                cursor = buffer.cursor,
                candidateIndex = candidateIndex.takeIf { phase == InputPhase.SELECTING },
                registrationDepth = registrations.size,
                registrationSaving = registrations.lastOrNull()?.savingToken != null,
            )
        }

    /** キー処理を発生させずに現在の表示全体を取得します。 */
    val currentView: BasicSkkView get() = view()

    fun dispatch(action: BasicSkkAction): BasicSkkResult {
        if (deletion != null) return dispatchCandidateDeletion(action)
        if (registrations.lastOrNull()?.savingToken != null) return dispatchRegistration(action)
        completionCycle?.let { return dispatchCompletionCycle(action, it) }
        when (action) {
            BasicSkkAction.CompleteForward -> return result(startManualCompletion(forward = true))
            BasicSkkAction.CompleteBackward -> return result(startManualCompletion(forward = false))
            BasicSkkAction.AcceptDynamicCompletion -> return result(acceptDynamicCompletion())
            else -> Unit
        }
        if (action is BasicSkkAction.Text) return afterInput(dispatchText(action.text))
        if (action == BasicSkkAction.DeleteCandidate) return afterInput(result(startCandidateDeletion()))
        if (registrations.isNotEmpty()) return afterInput(dispatchRegistration(action))
        val outcome = when (action) {
            is BasicSkkAction.Text -> error("文字入力は先に処理済みです")
            BasicSkkAction.Enter -> onEnter(false)
            BasicSkkAction.Kana -> onEnter(true)
            BasicSkkAction.Cancel -> onCancel()
            BasicSkkAction.Backspace -> onBackspace()
            BasicSkkAction.Halfwidth -> onHalfwidth()
            BasicSkkAction.Left -> onMove { it.moveLeft() }
            BasicSkkAction.Right -> onMove { it.moveRight() }
            BasicSkkAction.Home -> onMove { it.moveHome() }
            BasicSkkAction.End -> onMove { it.moveEnd() }
            BasicSkkAction.Delete -> onDelete()
            BasicSkkAction.DeleteCandidate -> error("候補削除は先に処理済みです")
            BasicSkkAction.CompleteForward,
            BasicSkkAction.CompleteBackward,
            BasicSkkAction.AcceptDynamicCompletion,
            -> error("補完操作は先に処理済みです")
        }
        refreshDynamicCompletion()
        return result(outcome)
    }

    private fun dispatchText(text: String): BasicSkkResult {
        // バッチ全体を先に検証し、不正な UTF-16 で途中まで状態を変更しません。
        EditableBuffer(text)
        var outcome = Outcome(true)
        var registrationStart = registrations.takeIf { it.isNotEmpty() }?.let { snapshotRuntime() }
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            var end = index + Character.charCount(codePoint)
            if (codePoint > 0x7f) {
                while (end < text.length) {
                    val next = text.codePointAt(end)
                    if (next <= 0x7f) break
                    end += Character.charCount(next)
                }
            }
            val value = text.substring(index, end)
            val part = if (deletion != null) {
                dispatchCandidateDeletion(BasicSkkAction.Text(value)).asOutcome()
            } else if (registrations.isEmpty()) {
                onText(value)
            } else {
                dispatchRegistration(BasicSkkAction.Text(value)).asOutcome()
            }
            if (part.notice == BODY_LIMIT_NOTICE) {
                restoreRuntime(checkNotNull(registrationStart))
                return result(outcome.then(Outcome(true, notice = BODY_LIMIT_NOTICE)))
            }
            outcome = outcome.then(part)
            if (registrationStart == null && registrations.isNotEmpty()) registrationStart = snapshotRuntime()
            index = end
        }
        return result(outcome)
    }

    private fun BasicSkkResult.asOutcome() = Outcome(
        handled = handled,
        commit = commit.orEmpty(),
        notice = notice,
        effects = effects,
    )

    private fun afterInput(result: BasicSkkResult): BasicSkkResult {
        refreshDynamicCompletion()
        return result.copy(view = view())
    }

    private fun startManualCompletion(forward: Boolean): Outcome {
        dynamicCompletion = null
        if (!completionConfig.manualEnabled) return Outcome(false)
        if (phase != InputPhase.READING && phase != InputPhase.ABBREV) {
            return Outcome(true, notice = "読み入力中だけ見出し語を補完できます")
        }
        if (okuriBoundary != null || okuriConsonant != null || okuriText().isNotEmpty()) {
            return Outcome(true, notice = "送りがある読みは補完できません")
        }
        if (buffer.cursor != buffer.text.length) {
            return Outcome(true, notice = "読みの末尾で補完してください")
        }
        val original = snapshotReading()
        if (romanizer.pending.isNotEmpty()) {
            if (romanizer.pending != "n") {
                return Outcome(true, notice = "未入力のローマ字を完成してから補完してください")
            }
            finishPendingIntoBuffer()
        }
        if (buffer.text.isEmpty()) {
            restoreReadingForCompletion(original)
            return Outcome(true)
        }
        val prefix = buffer.text
        val values = try {
            dictionary.complete(CompletionQuery(prefix, abbrev = phase == InputPhase.ABBREV)).toList()
        } catch (_: RuntimeException) {
            restoreReadingForCompletion(original)
            return Outcome(true, notice = COMPLETION_FAILURE_NOTICE)
        }
        if (values.isEmpty()) {
            restoreReadingForCompletion(original)
            return Outcome(true, notice = "補完候補がありません")
        }
        if (!validCompletionValues(values, prefix, CompletionQuery.MAX_RESULTS)) {
            restoreReadingForCompletion(original)
            return Outcome(true, notice = COMPLETION_FAILURE_NOTICE)
        }
        completionCycle = CompletionCycle(original, values, 0)
        applyCompletion(values.first())
        return Outcome(true, notice = if (forward) null else "これより前の補完候補はありません")
    }

    private fun dispatchCompletionCycle(action: BasicSkkAction, cycle: CompletionCycle): BasicSkkResult {
        return when (action) {
            BasicSkkAction.CompleteForward -> {
                if (cycle.index == cycle.values.lastIndex) {
                    result(Outcome(true, notice = "これより後の補完候補はありません"))
                } else {
                    cycle.index++
                    applyCompletion(cycle.values[cycle.index])
                    result(Outcome(true))
                }
            }
            BasicSkkAction.CompleteBackward -> {
                if (cycle.index == 0) {
                    result(Outcome(true, notice = "これより前の補完候補はありません"))
                } else {
                    cycle.index--
                    applyCompletion(cycle.values[cycle.index])
                    result(Outcome(true))
                }
            }
            BasicSkkAction.Cancel -> {
                completionCycle = null
                restoreReadingForCompletion(cycle.original)
                dynamicCompletion = null
                result(Outcome(true))
            }
            else -> {
                completionCycle = null
                dispatch(action)
            }
        }
    }

    private fun applyCompletion(value: String) {
        buffer = EditableBuffer(value, value.length)
        romanizer.reset()
        pendingTargetsOkuri = false
        dynamicCompletion = null
    }

    private fun acceptDynamicCompletion(): Outcome {
        val value = dynamicCompletion ?: return Outcome(false)
        if (phase != InputPhase.READING && phase != InputPhase.ABBREV || buffer.cursor != buffer.text.length) {
            dynamicCompletion = null
            return Outcome(false)
        }
        buffer = EditableBuffer(value, value.length)
        romanizer.reset()
        pendingTargetsOkuri = false
        dynamicCompletion = null
        return Outcome(true)
    }

    private fun refreshDynamicCompletion() {
        dynamicCompletion = null
        if (!completionConfig.dynamicEnabled || completionCycle != null || deletion != null) return
        if (registrations.lastOrNull()?.savingToken != null) return
        if (phase != InputPhase.READING && phase != InputPhase.ABBREV) return
        if (okuriBoundary != null || okuriConsonant != null || okuriText().isNotEmpty()) return
        if (romanizer.pending.isNotEmpty() || buffer.cursor != buffer.text.length || buffer.text.isEmpty()) return
        dynamicCompletion = try {
            val prefix = buffer.text
            val values = dictionary.complete(CompletionQuery(
                prefix = buffer.text,
                abbrev = phase == InputPhase.ABBREV,
                limit = 1,
                scope = CompletionScope.PERSONAL_ONLY,
            )).toList()
            values.singleOrNull()?.takeIf {
                validCompletionValues(values, prefix, 1)
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 辞書ポート実装が境界契約を破っても、読みや表示へ不正な値を取り込みません。 */
    private fun validCompletionValues(values: List<String>, prefix: String, limit: Int): Boolean {
        if (values.size > limit) return false
        var total = 0L
        for (value in values) {
            if (value.length <= prefix.length || value.length > CompletionQuery.MAX_RESULT_CHARS ||
                !value.startsWith(prefix)
            ) return false
            if (runCatching { EditableBuffer(value) }.isFailure) return false
            total += value.length.toLong()
            if (total > CompletionQuery.MAX_TOTAL_RESULT_CHARS) return false
        }
        return true
    }

    private fun restoreReadingForCompletion(saved: ReadingSnapshot) {
        phase = saved.phase
        buffer = EditableBuffer(saved.text, saved.cursor)
        okuriBoundary = saved.okuriBoundary
        okuriConsonant = saved.okuriConsonant
        romanizer = Romanizer().also {
            check(it.feed(saved.pendingRomaji).isEmpty()) { "未消化ローマ字を復元できません" }
        }
        pendingTargetsOkuri = saved.pendingTargetsOkuri
        candidates = emptyList()
        candidateIndex = 0
        selectionReturnState = null
        selectionQuery = null
        selectionRegistrationQuery = null
    }

    private fun startCandidateDeletion(): Outcome {
        if (phase != InputPhase.SELECTING) return Outcome(false)
        if (!deletionEnabled) return Outcome(true, notice = "候補削除は利用できません")
        if (!registrationPolicy.savingAllowed) {
            return Outcome(true, notice = "この入力欄では候補を削除できません")
        }
        val candidate = candidates[candidateIndex]
        val selection = candidate.selection
            ?: return Outcome(true, notice = "この候補は削除できません")
        deletion = DeletionState(
            query = checkNotNull(selectionQuery) { "候補の検索条件がありません" },
            candidate = candidate,
            selection = selection,
        )
        return Outcome(true, notice = DELETION_HELP_NOTICE)
    }

    private fun dispatchCandidateDeletion(action: BasicSkkAction): BasicSkkResult {
        val current = checkNotNull(deletion)
        val pending = current.token != null
        if (action == BasicSkkAction.Cancel) {
            deletion = null
            return if (pending) {
                restoreSelectionReturnState()
                result(Outcome(true, notice = "削除は完了する可能性があります"))
            } else {
                result(Outcome(true))
            }
        }
        if (pending) return result(Outcome(true, notice = "候補を削除しています"))
        if (action is BasicSkkAction.Text && action.text == "n") {
            deletion = null
            return result(Outcome(true))
        }
        if (action is BasicSkkAction.Text && action.text == "y") {
            val token = CandidateDeletionToken(nextOperationId++, registrationPolicy.sessionGeneration)
            current.token = token
            val request = CandidateDeletionRequest(
                token = token,
                query = current.query,
                displayedText = current.candidate.text,
                annotation = current.candidate.annotation,
                selection = current.selection,
            )
            return result(Outcome(true, effects = listOf(BasicSkkEffect.DeleteCandidate(request))))
        }
        return result(Outcome(true, notice = DELETION_HELP_NOTICE))
    }

    private fun refreshCandidatesAfterDeletion(current: DeletionState): BasicSkkResult {
        val refreshed = try {
            dictionary.lookup(current.query).toList()
        } catch (_: Exception) {
            return removeFrozenOrigins(
                current,
                "候補は削除されましたが、検索辞書を更新できません。辞書を再読込してください",
            )
        }
        replaceCandidatesWithoutRegistration(refreshed)
        return result(Outcome(true))
    }

    private fun refreshCandidatesAfterFailure(notice: String): BasicSkkResult {
        val query = checkNotNull(selectionQuery)
        val refreshed = runCatching { dictionary.lookup(query).toList() }.getOrNull()
        if (refreshed != null) replaceCandidatesWithoutRegistration(refreshed)
        return result(Outcome(true, notice = notice))
    }

    private fun removeFrozenOrigins(current: DeletionState, notice: String): BasicSkkResult {
        val deleted = current.selection.origins.toSet()
        val remaining = candidates.filter { candidate ->
            candidate.selection?.origins?.none { it in deleted } ?: true
        }
        replaceCandidatesWithoutRegistration(remaining)
        return result(Outcome(true, notice = notice))
    }

    private fun replaceCandidatesWithoutRegistration(replacement: List<DictionaryCandidate>) {
        candidates = replacement
        if (candidates.isEmpty()) {
            restoreSelectionReturnState()
        } else {
            candidateIndex = candidateIndex.coerceAtMost(candidates.lastIndex)
            phase = InputPhase.SELECTING
        }
    }

    /** 非同期保存の結果を、要求元のフレームがまだ生きている場合だけ適用します。 */
    fun completeRegistration(completion: RegistrationSaveCompletion): BasicSkkResult {
        val frame = registrations.lastOrNull()
        if (frame == null || frame.savingToken != completion.token) return result(Outcome(false))
        return when (val outcome = completion.outcome) {
            is RegistrationSaveOutcome.Failed -> {
                frame.savingToken = null
                frame.savingCommittedText = null
                result(Outcome(true, notice = outcome.reason.notice))
            }
            RegistrationSaveOutcome.Applied,
            RegistrationSaveOutcome.SavedButNotApplied,
            -> completeSavedRegistration(
                frame,
                if (outcome == RegistrationSaveOutcome.SavedButNotApplied) {
                    "登録は保存されましたが、検索辞書を更新できません。辞書を再読込してください"
                } else null,
            )
        }
    }

    /** 削除完了を、同じ入力セッションで待機中の要求へだけ適用します。 */
    fun completeCandidateDeletion(completion: CandidateDeletionCompletion): BasicSkkResult {
        val current = deletion ?: return result(Outcome(false))
        if (current.token != completion.token) return result(Outcome(false))
        deletion = null
        return when (val outcome = completion.outcome) {
            CandidateDeletionOutcome.Applied -> refreshCandidatesAfterDeletion(current)
            CandidateDeletionOutcome.SavedButNotApplied -> removeFrozenOrigins(
                current,
                "候補は削除されましたが、検索辞書を更新できません。辞書を再読込してください",
            )
            is CandidateDeletionOutcome.Failed -> when (outcome.reason) {
                CandidateDeletionFailure.CONFLICT -> refreshCandidatesAfterFailure(
                    "辞書が更新されたため削除できません。候補を確認してもう一度操作してください",
                )
                CandidateDeletionFailure.CAPACITY -> result(Outcome(true, notice = "辞書の容量が不足しているため削除できません"))
                CandidateDeletionFailure.POLICY_REJECTED -> result(Outcome(true, notice = "個人データを保存しない設定のため削除できません"))
                CandidateDeletionFailure.GENERAL -> result(Outcome(true, notice = "候補を削除できませんでした"))
            }
        }
    }

    /** セッション終了時に再帰登録と保存待ちを一度で破棄し、遅い完了を無効化します。 */
    fun resetComposition(): BasicSkkView {
        deletion = null
        registrations.clear()
        clearComposition()
        return view()
    }

    private fun dispatchRegistration(action: BasicSkkAction): BasicSkkResult {
        val before = snapshotRuntime()
        val frame = registrations.last()
        if (frame.savingToken != null) {
            return if (action == BasicSkkAction.Cancel) {
                abandonSavingFrame(frame)
            } else {
                result(Outcome(true, notice = "登録を保存しています"))
            }
        }
        if (innerIsClean()) {
            when (action) {
                BasicSkkAction.Enter -> return if (frame.body.text.isEmpty()) {
                    restoreRegistrationReturn(frame)
                } else {
                    requestRegistrationSave(frame)
                }
                BasicSkkAction.Kana -> return result(Outcome(true))
                BasicSkkAction.Cancel -> return abandonRegistrationFrame(frame)
                BasicSkkAction.Backspace -> return editRegistrationBody(frame) { it.backspace() }
                BasicSkkAction.Delete -> return editRegistrationBody(frame) { it.delete() }
                BasicSkkAction.Left -> return editRegistrationBody(frame) { it.moveLeft() }
                BasicSkkAction.Right -> return editRegistrationBody(frame) { it.moveRight() }
                BasicSkkAction.Home -> return editRegistrationBody(frame) { it.moveHome() }
                BasicSkkAction.End -> return editRegistrationBody(frame) { it.moveEnd() }
                is BasicSkkAction.Text -> Unit
                BasicSkkAction.Halfwidth -> Unit
                BasicSkkAction.DeleteCandidate -> Unit
                BasicSkkAction.CompleteForward,
                BasicSkkAction.CompleteBackward,
                BasicSkkAction.AcceptDynamicCompletion,
                -> Unit
            }
        }

        val targetFrameId = registrations.last().id
        val next = dispatchInner(action)
        if (!absorbRegistrationCommit(targetFrameId, next.commit)) {
            restoreRuntime(before)
            return result(Outcome(true, notice = BODY_LIMIT_NOTICE))
        }
        return result(next.copy(commit = ""))
    }

    private fun dispatchInner(action: BasicSkkAction): Outcome = when (action) {
        is BasicSkkAction.Text -> onText(action.text)
        BasicSkkAction.Enter -> onEnter(false)
        BasicSkkAction.Kana -> onEnter(true)
        BasicSkkAction.Cancel -> onCancel()
        BasicSkkAction.Backspace -> onBackspace()
        BasicSkkAction.Halfwidth -> onHalfwidth()
        BasicSkkAction.Left -> onMove { it.moveLeft() }
        BasicSkkAction.Right -> onMove { it.moveRight() }
        BasicSkkAction.Home -> onMove { it.moveHome() }
        BasicSkkAction.End -> onMove { it.moveEnd() }
        BasicSkkAction.Delete -> onDelete()
        BasicSkkAction.DeleteCandidate -> startCandidateDeletion()
        BasicSkkAction.CompleteForward -> startManualCompletion(forward = true)
        BasicSkkAction.CompleteBackward -> startManualCompletion(forward = false)
        BasicSkkAction.AcceptDynamicCompletion -> acceptDynamicCompletion()
    }

    private fun innerIsClean(): Boolean = phase == InputPhase.IDLE && romanizer.pending.isEmpty()

    private fun editRegistrationBody(
        frame: RegistrationFrame,
        edit: (EditableBuffer) -> Boolean,
    ): BasicSkkResult {
        if (edit(frame.body)) frame.revision++
        return result(Outcome(true))
    }

    private fun absorbRegistrationCommit(frameId: Long, commit: String): Boolean {
        if (commit.isEmpty()) return true
        val frame = registrations.find { it.id == frameId } ?: return true
        if (frame.body.text.length + commit.length > MAX_REGISTRATION_BODY) return false
        frame.body.insert(commit)
        frame.revision++
        return true
    }

    private fun requestRegistrationSave(frame: RegistrationFrame): BasicSkkResult {
        check(frame.body.text.isNotEmpty())
        if (!registrationPolicy.savingAllowed) {
            return result(Outcome(true, notice = "この入力欄では単語を登録できません"))
        }
        val committedText = try {
            dictionary.prepareRegistration(frame.originalQuery, frame.body.text).committedStem +
                frame.query.okuri.orEmpty()
        } catch (_: jp.hayase.skk.core.numeric.NumericLookupException) {
            return result(Outcome(true, notice = NUMERIC_FAILURE_NOTICE))
        }
        val parent = registrations.getOrNull(registrations.lastIndex - 1)
        if (parent != null && parent.body.text.length + committedText.length > MAX_REGISTRATION_BODY) {
            return result(Outcome(true, notice = BODY_LIMIT_NOTICE))
        }
        val token = RegistrationSaveToken(
            operationId = nextOperationId++,
            frameId = frame.id,
            frameRevision = frame.revision,
            parentFrameId = frame.parentId,
            sessionGeneration = registrationPolicy.sessionGeneration,
        )
        frame.savingToken = token
        frame.savingCommittedText = committedText
        val request = RegistrationSaveRequest(
            token = token,
            readingKey = frame.query.readingKey,
            candidateText = frame.body.text,
            okuriCondition = frame.query.okuri,
            committedText = committedText,
        )
        return result(Outcome(true, effects = listOf(BasicSkkEffect.SaveRegistration(request))))
    }

    private fun completeSavedRegistration(frame: RegistrationFrame, notice: String?): BasicSkkResult {
        check(registrations.lastOrNull() === frame)
        val committedText = checkNotNull(frame.savingCommittedText) { "登録確定文字列がありません" }
        registrations.removeAt(registrations.lastIndex)
        mode = frame.returnState.readingStartMode
        clearComposition()
        val parent = registrations.lastOrNull()
        if (parent != null) {
            check(parent.id == frame.parentId)
            check(parent.body.text.length + committedText.length <= MAX_REGISTRATION_BODY)
            parent.body.insert(committedText)
            parent.revision++
            return result(Outcome(true, notice = notice))
        }
        return result(Outcome(true, commit = committedText, notice = notice))
    }

    private fun restoreRegistrationReturn(frame: RegistrationFrame): BasicSkkResult {
        check(registrations.lastOrNull() === frame)
        registrations.removeAt(registrations.lastIndex)
        restoreEngine(frame.returnState)
        return result(Outcome(true))
    }

    private fun abandonRegistrationFrame(frame: RegistrationFrame): BasicSkkResult {
        check(registrations.lastOrNull() === frame)
        registrations.removeAt(registrations.lastIndex)
        mode = frame.returnState.readingStartMode
        clearComposition()
        return result(Outcome(true))
    }

    private fun abandonSavingFrame(frame: RegistrationFrame): BasicSkkResult {
        abandonRegistrationFrame(frame)
        return result(Outcome(true, notice = "保存処理は完了する可能性があります"))
    }

    private fun startRegistration(
        originalQuery: DictionaryQuery,
        query: DictionaryQuery,
        returnState: EngineSnapshot,
        editor: EditorReadingView,
    ): Outcome {
        if (registrations.size >= MAX_REGISTRATION_DEPTH) {
            return Outcome(true, notice = "単語登録は16段までです")
        }
        val parentId = registrations.lastOrNull()?.id
        registrations += RegistrationFrame(
            id = nextFrameId++,
            parentId = parentId,
            originalQuery = originalQuery,
            query = query,
            returnState = returnState,
            body = EditableBuffer(),
            editorComposition = registrations.firstOrNull()?.editorComposition ?: editor.text,
            editorCursor = registrations.firstOrNull()?.editorCursor ?: editor.cursor,
        )
        clearComposition()
        return Outcome(true)
    }

    private fun onText(text: String): Outcome {
        // 先に全体を検証し、不正な UTF-16 で状態を部分更新しません。
        EditableBuffer(text)
        var outcome = Outcome(true)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (codePoint <= 0x7f) {
                outcome = outcome.then(onCharacter(codePoint.toChar()))
                index++
            } else {
                val start = index
                do {
                    index += Character.charCount(text.codePointAt(index))
                } while (index < text.length && text.codePointAt(index) > 0x7f)
                outcome = outcome.then(onLiteral(text.substring(start, index)))
            }
        }
        return outcome
    }

    private fun onLiteral(text: String): Outcome = when (phase) {
        InputPhase.SELECTING -> commitCandidate(candidateIndex).then(onLiteral(text))
        InputPhase.ABBREV -> {
            insertBuffer(text)
            Outcome(true)
        }
        InputPhase.READING -> {
            finishPendingIntoBuffer()
            insertBuffer(text)
            Outcome(true)
        }
        InputPhase.IDLE -> {
            val pending = finishIdlePending()
            Outcome(
                handled = true,
                commit = pending + when (mode) {
                    InputMode.FULLWIDTH -> KanaTransforms.toFullwidthAscii(text)
                    InputMode.KATAKANA -> KanaTransforms.hiraganaToKatakana(text)
                    InputMode.HALFWIDTH -> KanaTransforms.toHalfwidthKana(text)
                    else -> text
                },
            )
        }
    }

    private fun onCharacter(character: Char): Outcome {
        if (phase == InputPhase.SELECTING) {
            if (character == 'X' && deletionEnabled) return startCandidateDeletion()
            if (character == ' ') return selectNext()
            if (character == 'x') return selectPrevious()
            menuIndexFor(character)?.let { return commitCandidate(it) }
            if (character == '>') return commitCandidate(candidateIndex).then(startSuffix())
            return commitCandidate(candidateIndex).then(onCharacter(character))
        }
        if (phase == InputPhase.ABBREV) {
            if (character == ' ') return lookup()
            insertBuffer(character.toString())
            return Outcome(true)
        }
        if (phase == InputPhase.READING) {
            return when (character) {
                ' ' -> lookup()
                'q' -> commitReadingAsToggledKana()
                'Q' -> commitRawReading().then(startReading())
                '>' -> {
                    finishPendingIntoBuffer()
                    insertBuffer(">")
                    lookup()
                }
                else -> inputReadingCharacter(character)
            }
        }
        return when {
            character == 'q' && mode.isKana -> {
                val pending = finishIdlePending()
                mode = when (mode) {
                    InputMode.HIRAGANA -> InputMode.KATAKANA
                    InputMode.KATAKANA, InputMode.HALFWIDTH -> InputMode.HIRAGANA
                    else -> mode
                }
                Outcome(true, pending)
            }
            character == 'Q' && mode.isKana -> finishIdlePending().let { Outcome(true, it).then(startReading()) }
            character == '/' && mode.isKana -> finishIdlePending().let { Outcome(true, it).then(startAbbrev()) }
            character == '>' && mode.isKana -> finishIdlePending().let { Outcome(true, it).then(startSuffix()) }
            character == 'l' && mode.isKana -> {
                val commit = finishIdlePending(); mode = InputMode.DIRECT; Outcome(true, commit)
            }
            character == 'L' && mode.isKana -> {
                val commit = finishIdlePending(); mode = InputMode.FULLWIDTH; Outcome(true, commit)
            }
            character.isUpperCase() && mode.isKana -> finishIdlePending().let {
                Outcome(true, it).then(startReading()).then(inputReadingCharacter(character))
            }
            mode == InputMode.DIRECT -> Outcome(true, character.toString())
            mode == InputMode.FULLWIDTH -> Outcome(true, KanaTransforms.toFullwidthAscii(character.toString()))
            character.isRomajiInput -> {
                val output = romanizer.feed(character.lowercaseChar().toString())
                Outcome(true, renderKana(output, mode))
            }
            else -> Outcome(true, finishIdlePending() + character)
        }
    }

    private fun inputReadingCharacter(character: Char): Outcome {
        if (character.isUpperCase() && okuriBoundary == null && buffer.cursor > 0) {
            finishPendingIntoBuffer()
            okuriBoundary = buffer.cursor
            okuriConsonant = character.lowercaseChar()
            pendingTargetsOkuri = true
        }
        if (character.isRomajiInput) {
            if (romanizer.pending.isEmpty()) {
                pendingTargetsOkuri = okuriBoundary?.let { buffer.cursor >= it } == true
            }
            val output = romanizer.feed(character.lowercaseChar().toString())
            insertBuffer(output, pendingTargetsOkuri)
            if (romanizer.pending.isEmpty()) pendingTargetsOkuri = false
        } else {
            finishPendingIntoBuffer()
            insertBuffer(character.toString())
        }
        return if (okuriBoundary != null && okuriText().isNotEmpty() && romanizer.pending.isEmpty()) lookup() else Outcome(true)
    }

    private fun onEnter(kana: Boolean): Outcome {
        val outcome = when (phase) {
            InputPhase.SELECTING -> commitCandidate(candidateIndex)
            InputPhase.READING -> commitRawReading()
            InputPhase.ABBREV -> commitRawReading()
            InputPhase.IDLE -> {
                val commit = finishIdlePending()
                if (commit.isEmpty()) Outcome(kana) else Outcome(true, commit)
            }
        }
        if (kana) mode = InputMode.HIRAGANA
        return outcome
    }

    private fun onCancel(): Outcome = when (phase) {
        InputPhase.SELECTING -> {
            restoreSelectionReturnState(); Outcome(true)
        }
        InputPhase.READING, InputPhase.ABBREV -> {
            clearComposition(); Outcome(true)
        }
        InputPhase.IDLE -> if (romanizer.pending.isNotEmpty()) {
            romanizer.reset(); Outcome(true)
        } else Outcome(mode.isKana)
    }

    private fun onBackspace(): Outcome {
        if (phase == InputPhase.SELECTING) return selectPrevious()
        if (romanizer.backspacePending()) return Outcome(true)
        if (phase == InputPhase.IDLE) return Outcome(false)
        val oldText = buffer.text
        val oldCursor = buffer.cursor
        if (buffer.backspace()) {
            adjustBoundaryAfterBackspace(oldText, oldCursor)
            return Outcome(true)
        }
        if (buffer.text.isEmpty()) clearComposition()
        return Outcome(true)
    }

    private fun onDelete(): Outcome {
        if (phase == InputPhase.SELECTING) return Outcome(true)
        if (phase == InputPhase.IDLE && romanizer.pending.isEmpty()) return Outcome(false)
        finishPendingIntoBufferOrCommit()?.let { return it }
        val oldText = buffer.text
        val oldCursor = buffer.cursor
        val changed = buffer.delete()
        if (changed) adjustBoundaryForDeletion(oldText, oldCursor, buffer.text.length - oldText.length)
        return Outcome(phase != InputPhase.IDLE || changed)
    }

    private fun onMove(move: (EditableBuffer) -> Boolean): Outcome {
        if (phase == InputPhase.SELECTING) return Outcome(true)
        if (phase == InputPhase.IDLE && romanizer.pending.isEmpty()) return Outcome(false)
        finishPendingIntoBufferOrCommit()?.let { return it }
        move(buffer)
        return Outcome(phase != InputPhase.IDLE)
    }

    private fun onHalfwidth(): Outcome {
        if (mode == InputMode.DIRECT || mode == InputMode.FULLWIDTH) return Outcome(false)
        if (phase == InputPhase.SELECTING) return commitCandidate(candidateIndex).then(toggleHalfwidthMode())
        if (phase == InputPhase.ABBREV) {
            val value = if (buffer.text.all { it.code in 0x21..0x7e || it == ' ' }) {
                KanaTransforms.toFullwidthAscii(buffer.text)
            } else buffer.text.map { if (it in '！'..'～') (it.code - 0xfee0).toChar() else if (it == '　') ' ' else it }.joinToString("")
            clearComposition()
            return Outcome(true, value)
        }
        if (phase == InputPhase.READING) {
            finishPendingIntoBuffer()
            val value = KanaTransforms.toHalfwidthKana(buffer.text)
            clearComposition()
            return Outcome(true, value)
        }
        val commit = finishIdlePending()
        return Outcome(true, commit).then(toggleHalfwidthMode())
    }

    private fun toggleHalfwidthMode(): Outcome {
        mode = if (mode == InputMode.HALFWIDTH) InputMode.HIRAGANA else InputMode.HALFWIDTH
        return Outcome(true)
    }

    private fun startReading(): Outcome {
        clearComposition()
        phase = InputPhase.READING
        readingStartMode = mode
        return Outcome(true)
    }

    private fun startAbbrev(): Outcome {
        clearComposition()
        phase = InputPhase.ABBREV
        readingStartMode = mode
        return Outcome(true)
    }

    private fun startSuffix(): Outcome {
        startReading()
        insertBuffer(">")
        return Outcome(true)
    }

    private fun lookup(): Outcome {
        val returnState = snapshotReading()
        finishPendingIntoBuffer()
        val stem = stemText()
        if (stem.isEmpty()) return Outcome(true)
        val query = DictionaryQuery(
            readingKey = if (okuriBoundary == null) stem else stem + okuriConsonant,
            okuri = okuriText().nullIfEmpty(),
            abbrev = phase == InputPhase.ABBREV,
        )
        val registrationQuery: DictionaryQuery
        candidates = try {
            registrationQuery = dictionary.registrationQuery(query)
            dictionary.lookup(query).toList()
        } catch (unavailable: DictionaryUnavailableException) {
            selectionReturnState = returnState
            restoreSelectionReturnState()
            return Outcome(true, notice = when (unavailable.reason) {
                DictionaryUnavailableReason.INITIALIZING -> "辞書を準備しています。読みを保持しました。準備後にもう一度変換してください"
                DictionaryUnavailableReason.FAILED -> "辞書を読み込めません。読みを保持しました。設定から再読込してください"
            })
        } catch (_: jp.hayase.skk.core.numeric.NumericLookupException) {
            selectionReturnState = returnState
            restoreSelectionReturnState()
            return Outcome(true, notice = NUMERIC_FAILURE_NOTICE)
        }
        candidateIndex = 0
        selectionReturnState = returnState
        selectionQuery = query
        selectionRegistrationQuery = registrationQuery
        return if (candidates.isEmpty()) {
            restoreSelectionReturnState()
            if (registrationPolicy.enabled) {
                startRegistration(query, registrationQuery, snapshotEngine(), editorReadingView(returnState))
            }
            else Outcome(true, notice = "単語登録はまだ利用できません")
        } else {
            phase = InputPhase.SELECTING
            Outcome(true)
        }
    }

    private fun selectNext(): Outcome {
        if (candidateIndex + 1 < candidates.size) {
            candidateIndex++
            return Outcome(true)
        }
        if (registrationPolicy.enabled) {
            val query = checkNotNull(selectionQuery) { "候補の検索条件がありません" }
            val registrationQuery = checkNotNull(selectionRegistrationQuery) { "登録の検索条件がありません" }
            val editor = editorReadingView(checkNotNull(selectionReturnState))
            return startRegistration(query, registrationQuery, snapshotEngine(), editor)
        }
        restoreSelectionReturnState()
        return Outcome(true, notice = "単語登録はまだ利用できません")
    }

    private fun selectPrevious(): Outcome {
        if (candidateIndex > 0) {
            candidateIndex--
        } else {
            restoreSelectionReturnState()
        }
        return Outcome(true)
    }

    private fun snapshotReading() = ReadingSnapshot(
        phase = phase,
        text = buffer.text,
        cursor = buffer.cursor,
        okuriBoundary = okuriBoundary,
        okuriConsonant = okuriConsonant,
        pendingRomaji = romanizer.pending,
        pendingTargetsOkuri = pendingTargetsOkuri,
    )

    private fun restoreSelectionReturnState() {
        val saved = checkNotNull(selectionReturnState) { "候補選択前の読み状態がありません" }
        phase = saved.phase
        buffer = EditableBuffer(saved.text, saved.cursor)
        okuriBoundary = saved.okuriBoundary
        okuriConsonant = saved.okuriConsonant
        romanizer = Romanizer().also {
            check(it.feed(saved.pendingRomaji).isEmpty()) { "未消化ローマ字を復元できません" }
        }
        pendingTargetsOkuri = saved.pendingTargetsOkuri
        candidates = emptyList()
        candidateIndex = 0
        selectionReturnState = null
        selectionQuery = null
        selectionRegistrationQuery = null
    }

    private fun commitCandidate(index: Int): Outcome {
        val candidate = candidates[index]
        val committed = candidate.text + okuriText()
        val effects = if (learningEnabled && registrationPolicy.savingAllowed && registrations.isEmpty()) {
            listOf(BasicSkkEffect.LearnCandidate(CandidateCommitRequest(
                operationId = nextOperationId++,
                sessionGeneration = registrationPolicy.sessionGeneration,
                query = candidate.learningTarget?.query ?: checkNotNull(selectionQuery),
                candidate = candidate.learningTarget?.let {
                    DictionaryCandidate(it.templateText, it.annotation, it.okuriCondition)
                } ?: candidate,
            )))
        } else emptyList()
        clearComposition()
        mode = readingStartMode
        return Outcome(true, committed, effects = effects)
    }

    private fun commitRawReading(): Outcome {
        finishPendingIntoBuffer()
        val committed = renderKana(buffer.text, readingStartMode)
        clearComposition()
        mode = readingStartMode
        return Outcome(true, committed)
    }

    private fun commitReadingAsToggledKana(): Outcome {
        finishPendingIntoBuffer()
        val rendered = when (readingStartMode) {
            InputMode.HIRAGANA -> KanaTransforms.hiraganaToKatakana(buffer.text)
            InputMode.KATAKANA -> buffer.text
            InputMode.HALFWIDTH -> KanaTransforms.hiraganaToKatakana(buffer.text)
            else -> buffer.text
        }
        clearComposition()
        mode = readingStartMode
        return Outcome(true, rendered)
    }

    private fun finishIdlePending(): String {
        val output = renderKana(romanizer.finish(), mode)
        return output
    }

    private fun finishPendingIntoBuffer() {
        insertBuffer(romanizer.finish(), pendingTargetsOkuri)
        pendingTargetsOkuri = false
    }

    private fun finishPendingIntoBufferOrCommit(): Outcome? {
        if (romanizer.pending.isEmpty()) return null
        return if (phase == InputPhase.IDLE) Outcome(true, finishIdlePending()) else {
            finishPendingIntoBuffer(); null
        }
    }

    private fun insertBuffer(value: String, targetsOkuri: Boolean = false) {
        if (value.isEmpty()) return
        val oldCursor = buffer.cursor
        val boundary = okuriBoundary
        buffer.insert(value)
        if (boundary != null && (oldCursor < boundary || oldCursor == boundary && !targetsOkuri)) {
            okuriBoundary = boundary + value.length
        }
    }

    private fun adjustBoundaryAfterBackspace(oldText: String, oldCursor: Int) {
        val boundary = okuriBoundary ?: return
        val removed = oldText.length - buffer.text.length
        if (oldCursor <= boundary) okuriBoundary = (boundary - removed).coerceAtLeast(0)
        repairBoundaryAfterEdit()
    }

    private fun repairBoundaryAfterEdit() {
        val boundary = okuriBoundary ?: return
        if (buffer.text.length < boundary) okuriBoundary = buffer.text.length
        if (okuriText().isEmpty()) {
            okuriBoundary = null
            okuriConsonant = null
        }
    }

    private fun adjustBoundaryForDeletion(oldText: String, oldCursor: Int, delta: Int) {
        val boundary = okuriBoundary ?: return
        if (oldCursor < boundary) okuriBoundary = (boundary + delta).coerceAtLeast(oldCursor)
        repairBoundaryAfterEdit()
    }

    private fun stemText(): String = okuriBoundary?.let { buffer.text.substring(0, it) } ?: buffer.text
    private fun okuriText(): String = okuriBoundary?.let { buffer.text.substring(it) } ?: ""
    private fun menuIndexFor(label: Char): Int? {
        if (candidateIndex < INLINE_CANDIDATES) return null
        val offset = LABELS.indexOf(label)
        if (offset < 0) return null
        val pageStart = INLINE_CANDIDATES + ((candidateIndex - INLINE_CANDIDATES) / LABELS.length) * LABELS.length
        return (pageStart + offset).takeIf { it < candidates.size }
    }

    private fun view(): BasicSkkView {
        val candidate = if (phase == InputPhase.SELECTING) {
            val selected = candidates[candidateIndex]
            val menu = if (candidateIndex >= INLINE_CANDIDATES) {
                val start = INLINE_CANDIDATES + ((candidateIndex - INLINE_CANDIDATES) / LABELS.length) * LABELS.length
                candidates.drop(start).take(LABELS.length).mapIndexed { offset, value ->
                    LabeledCandidate(LABELS[offset], value, value.text + okuriText())
                }
            } else emptyList()
            CandidateView(selected, selected.text + okuriText(), candidateIndex, candidates.size, menu)
        } else null
        val renderedPrefix = renderKana(buffer.text.substring(0, buffer.cursor), readingStartMode)
        val renderedSuffix = renderKana(buffer.text.substring(buffer.cursor), readingStartMode)
        val composing = when (phase) {
            InputPhase.IDLE -> romanizer.pending.takeIf { it.isNotEmpty() }?.let { renderKana(it, mode) }
            InputPhase.ABBREV -> buffer.text.substring(0, buffer.cursor) + romanizer.pending + buffer.text.substring(buffer.cursor)
            InputPhase.READING, InputPhase.SELECTING -> renderedPrefix + romanizer.pending + renderedSuffix
        }
        val cursor = when (phase) {
            InputPhase.IDLE -> null
            InputPhase.ABBREV -> buffer.cursor + romanizer.pending.length
            InputPhase.READING, InputPhase.SELECTING -> renderedPrefix.length + romanizer.pending.length
        }
        val deletionView = deletion?.let { current ->
            val personal = current.selection.origins.count { it.personal }
            CandidateDeletionView(
                readingKey = current.query.readingKey,
                candidateText = current.candidate.text,
                okuri = current.query.okuri,
                originCount = current.selection.origins.size,
                personalOriginCount = personal,
                systemOriginCount = current.selection.origins.size - personal,
                numericTemplate = current.selection.numericTemplate,
                saving = current.token != null,
            )
        }
        val completionView = dynamicCompletion?.let { proposed ->
            val prefix = if (phase == InputPhase.ABBREV) buffer.text else renderKana(buffer.text, readingStartMode)
            val completed = if (phase == InputPhase.ABBREV) proposed else renderKana(proposed, readingStartMode)
            completed.takeIf { it.startsWith(prefix) && it.length > prefix.length }
                ?.let { DynamicCompletionView(prefix, completed.substring(prefix.length)) }
        }
        val inner = BasicSkkView(
            composing, cursor, candidate, deletion = deletionView, completion = completionView,
        )
        val frame = registrations.lastOrNull() ?: return inner
        val root = registrations.first()
        return BasicSkkView(
            composing = root.editorComposition,
            cursor = root.editorCursor,
            candidate = null,
            registration = RegistrationView(
                depth = registrations.size,
                readingKey = frame.query.readingKey,
                body = frame.body.text,
                cursor = frame.body.cursor,
                innerComposing = inner.composing,
                innerCursor = inner.cursor,
                innerCandidate = inner.candidate,
                saving = frame.savingToken != null,
            ),
            deletion = deletionView,
            completion = completionView,
        )
    }

    private fun result(outcome: Outcome): BasicSkkResult = BasicSkkResult(
        handled = outcome.handled,
        commit = outcome.commit.nullIfEmpty(),
        view = view(),
        notice = outcome.notice,
        effects = outcome.effects,
    )

    private fun editorReadingView(snapshot: ReadingSnapshot): EditorReadingView {
        val prefix = renderKana(snapshot.text.substring(0, snapshot.cursor), readingStartMode)
        val suffix = renderKana(snapshot.text.substring(snapshot.cursor), readingStartMode)
        return EditorReadingView(prefix + snapshot.pendingRomaji + suffix, prefix.length + snapshot.pendingRomaji.length)
    }

    private fun snapshotEngine() = EngineSnapshot(
        mode = mode,
        phase = phase,
        text = buffer.text,
        cursor = buffer.cursor,
        readingStartMode = readingStartMode,
        okuriBoundary = okuriBoundary,
        okuriConsonant = okuriConsonant,
        pendingRomaji = romanizer.pending,
        candidates = candidates,
        candidateIndex = candidateIndex,
        pendingTargetsOkuri = pendingTargetsOkuri,
        selectionReturnState = selectionReturnState,
        selectionQuery = selectionQuery,
        selectionRegistrationQuery = selectionRegistrationQuery,
        deletion = deletion?.frozenCopy(),
        completionCycle = completionCycle?.frozenCopy(),
        dynamicCompletion = dynamicCompletion,
    )

    private fun restoreEngine(snapshot: EngineSnapshot) {
        mode = snapshot.mode
        phase = snapshot.phase
        buffer = EditableBuffer(snapshot.text, snapshot.cursor)
        readingStartMode = snapshot.readingStartMode
        okuriBoundary = snapshot.okuriBoundary
        okuriConsonant = snapshot.okuriConsonant
        romanizer = Romanizer().also {
            check(it.feed(snapshot.pendingRomaji).isEmpty()) { "未消化ローマ字を復元できません" }
        }
        candidates = snapshot.candidates
        candidateIndex = snapshot.candidateIndex
        pendingTargetsOkuri = snapshot.pendingTargetsOkuri
        selectionReturnState = snapshot.selectionReturnState
        selectionQuery = snapshot.selectionQuery
        selectionRegistrationQuery = snapshot.selectionRegistrationQuery
        deletion = snapshot.deletion?.frozenCopy()
        completionCycle = snapshot.completionCycle?.frozenCopy()
        dynamicCompletion = snapshot.dynamicCompletion
    }

    private fun snapshotRuntime() = RuntimeSnapshot(
        engine = snapshotEngine(),
        frames = registrations.map { it.frozenCopy() },
        nextFrameId = nextFrameId,
        nextOperationId = nextOperationId,
    )

    private fun restoreRuntime(snapshot: RuntimeSnapshot) {
        restoreEngine(snapshot.engine)
        registrations.clear()
        registrations += snapshot.frames.map { it.frozenCopy() }
        nextFrameId = snapshot.nextFrameId
        nextOperationId = snapshot.nextOperationId
    }

    private fun clearComposition() {
        phase = InputPhase.IDLE
        buffer = EditableBuffer()
        okuriBoundary = null
        okuriConsonant = null
        romanizer.reset()
        candidates = emptyList()
        candidateIndex = 0
        pendingTargetsOkuri = false
        selectionReturnState = null
        selectionQuery = null
        selectionRegistrationQuery = null
        deletion = null
        completionCycle = null
        dynamicCompletion = null
    }

    private data class ReadingSnapshot(
        val phase: InputPhase,
        val text: String,
        val cursor: Int,
        val okuriBoundary: Int?,
        val okuriConsonant: Char?,
        val pendingRomaji: String,
        val pendingTargetsOkuri: Boolean,
    )

    private data class EngineSnapshot(
        val mode: InputMode,
        val phase: InputPhase,
        val text: String,
        val cursor: Int,
        val readingStartMode: InputMode,
        val okuriBoundary: Int?,
        val okuriConsonant: Char?,
        val pendingRomaji: String,
        val candidates: List<DictionaryCandidate>,
        val candidateIndex: Int,
        val pendingTargetsOkuri: Boolean,
        val selectionReturnState: ReadingSnapshot?,
        val selectionQuery: DictionaryQuery?,
        val selectionRegistrationQuery: DictionaryQuery?,
        val deletion: DeletionState?,
        val completionCycle: CompletionCycle?,
        val dynamicCompletion: String?,
    )

    private data class RegistrationFrame(
        val id: Long,
        val parentId: Long?,
        val originalQuery: DictionaryQuery,
        val query: DictionaryQuery,
        val returnState: EngineSnapshot,
        val body: EditableBuffer,
        val editorComposition: String,
        val editorCursor: Int,
        var revision: Long = 0,
        var savingToken: RegistrationSaveToken? = null,
        var savingCommittedText: String? = null,
    ) {
        fun frozenCopy() = copy(body = EditableBuffer(body.text, body.cursor))
    }

    private data class DeletionState(
        val query: DictionaryQuery,
        val candidate: DictionaryCandidate,
        val selection: CandidateSelection,
        var token: CandidateDeletionToken? = null,
    ) {
        fun frozenCopy() = copy()
    }

    private data class CompletionCycle(
        val original: ReadingSnapshot,
        val values: List<String>,
        var index: Int,
    ) {
        fun frozenCopy() = copy(values = values.toList())
    }

    private data class EditorReadingView(val text: String, val cursor: Int)
    private data class RuntimeSnapshot(
        val engine: EngineSnapshot,
        val frames: List<RegistrationFrame>,
        val nextFrameId: Long,
        val nextOperationId: Long,
    )

    private data class Outcome(
        val handled: Boolean,
        val commit: String = "",
        val notice: String? = null,
        val effects: List<BasicSkkEffect> = emptyList(),
    ) {
        fun then(next: Outcome) = Outcome(
            handled || next.handled,
            commit + next.commit,
            next.notice ?: notice,
            effects + next.effects,
        )
    }

    private companion object {
        const val INLINE_CANDIDATES = 3
        const val LABELS = "asdfjkl"
        const val MAX_REGISTRATION_DEPTH = 16
        const val MAX_REGISTRATION_BODY = 65_536
        const val BODY_LIMIT_NOTICE = "登録本文は65,536 UTF-16コード単位までです"
        const val NUMERIC_FAILURE_NOTICE = "数値を展開できません。読みを保持しました。入力を確認してください"
        const val DELETION_HELP_NOTICE = "候補を削除する場合は y、戻る場合は n または取消を押してください"
        const val COMPLETION_FAILURE_NOTICE = "見出し語を補完できません。読みを保持しました"
        val InputMode.isKana: Boolean get() = this == InputMode.HIRAGANA || this == InputMode.KATAKANA || this == InputMode.HALFWIDTH
        val Char.isRomajiInput: Boolean get() = this == '\'' || isLetter() && code < 128

        fun renderKana(text: String, mode: InputMode): String = when (mode) {
            InputMode.HIRAGANA -> text
            InputMode.KATAKANA -> KanaTransforms.hiraganaToKatakana(text)
            InputMode.HALFWIDTH -> KanaTransforms.toHalfwidthKana(text)
            else -> text
        }

        fun String.nullIfEmpty(): String? = ifEmpty { null }

        val RegistrationSaveFailure.notice: String
            get() = when (this) {
                RegistrationSaveFailure.CAPACITY -> "辞書の容量が不足しているため保存できません"
                RegistrationSaveFailure.CONFLICT -> "辞書が更新されたため保存できません。もう一度登録してください"
                RegistrationSaveFailure.POLICY_REJECTED -> "個人データを保存しない設定のため登録できません"
                RegistrationSaveFailure.GENERAL -> "単語を保存できません。もう一度試してください"
            }
    }
}
