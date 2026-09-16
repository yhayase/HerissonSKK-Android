package jp.hayase.skk.core.keys

import java.util.Collections
import jp.hayase.skk.core.BasicSkkAction
import jp.hayase.skk.core.BasicSkkState
import jp.hayase.skk.core.BasicSkkView
import jp.hayase.skk.core.InputMode
import jp.hayase.skk.core.InputPhase
import jp.hayase.skk.core.editing.EditCommand

/** Unicode 文字は OS 配列の適用後の値です。特殊キーと区別して保存します。 */
data class KeyGesture(val text: String? = null, val special: SpecialKey? = null,
    val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false,
    val ignoreShift: Boolean = false) {
    init {
        require((text == null) != (special == null)) { "文字または特殊キーを一つ指定します" }
        if (text != null) {
            require(text.codePointCount(0, text.length) == 1 && text.isNotEmpty() &&
                text.codePointAt(0) !in 0xD800..0xDFFF && !Character.isISOControl(text.codePointAt(0))) {
                "キーには一つの有効な文字を指定します"
            }
        }
        require(!ignoreShift || text != null && !ctrl && !alt && !shift) {
            "Shiftを区別しない設定は修飾なしの文字だけに使えます"
        }
    }

    /** 保存した割り当てから、OS 配列適用後の実際のジェスチャーを照合します。 */
    fun matches(actual: KeyGesture): Boolean = text == actual.text && special == actual.special &&
        ctrl == actual.ctrl && alt == actual.alt && (ignoreShift || shift == actual.shift)

    /** 二つの割り当てが同じ実ジェスチャーを受理し得るかを返します。 */
    fun overlaps(other: KeyGesture): Boolean = text == other.text && special == other.special &&
        ctrl == other.ctrl && alt == other.alt &&
        (ignoreShift || other.ignoreShift || shift == other.shift)
}

enum class SpecialKey { ENTER, TAB, ESCAPE, LEFT, RIGHT, HOME, END, BACKSPACE, DELETE }

/** 保存時の競合検査と実行時の配送で共通して使う、相互に排他的な入力状態です。 */
enum class KeyBindingState {
    IDLE, PENDING, READING, ABBREV, CANDIDATE, MENU, DIRECT, FULLWIDTH,
    REGISTRATION_BODY, REGISTRATION_DIRECT, REGISTRATION_FULLWIDTH, REGISTRATION_PENDING, REGISTRATION_READING, REGISTRATION_ABBREV,
    REGISTRATION_CANDIDATE, REGISTRATION_MENU, REGISTRATION_SAVING,
    DELETION_CONFIRMATION, COMPLETION_READING, COMPLETION_ABBREV,
    REGISTRATION_COMPLETION_READING, REGISTRATION_COMPLETION_ABBREV,
}

private val ordinaryStates = setOf(
    KeyBindingState.IDLE, KeyBindingState.PENDING, KeyBindingState.READING, KeyBindingState.ABBREV,
    KeyBindingState.CANDIDATE, KeyBindingState.MENU, KeyBindingState.DIRECT, KeyBindingState.FULLWIDTH,
    KeyBindingState.REGISTRATION_BODY, KeyBindingState.REGISTRATION_DIRECT, KeyBindingState.REGISTRATION_FULLWIDTH,
    KeyBindingState.REGISTRATION_PENDING,
    KeyBindingState.REGISTRATION_READING, KeyBindingState.REGISTRATION_ABBREV,
    KeyBindingState.REGISTRATION_CANDIDATE, KeyBindingState.REGISTRATION_MENU,
    KeyBindingState.COMPLETION_READING, KeyBindingState.COMPLETION_ABBREV,
    KeyBindingState.REGISTRATION_COMPLETION_READING, KeyBindingState.REGISTRATION_COMPLETION_ABBREV,
)
private val idleStates = setOf(KeyBindingState.IDLE, KeyBindingState.REGISTRATION_BODY)
private val pendingStates = setOf(KeyBindingState.PENDING, KeyBindingState.REGISTRATION_PENDING)
private val readingStates = setOf(KeyBindingState.READING, KeyBindingState.REGISTRATION_READING,
    KeyBindingState.COMPLETION_READING, KeyBindingState.REGISTRATION_COMPLETION_READING)
private val abbrevStates = setOf(KeyBindingState.ABBREV, KeyBindingState.REGISTRATION_ABBREV,
    KeyBindingState.COMPLETION_ABBREV, KeyBindingState.REGISTRATION_COMPLETION_ABBREV)
private val candidateStates = setOf(KeyBindingState.CANDIDATE, KeyBindingState.REGISTRATION_CANDIDATE)
private val menuStates = setOf(KeyBindingState.MENU, KeyBindingState.REGISTRATION_MENU)
private val selectionStates = candidateStates + menuStates
private val readingLikeStates = readingStates + abbrevStates
private val editableStates = KeyBindingState.entries.toSet()

/** 適用状態は操作の契約で固定し、キーだけを置き換えます。 */
enum class SkkCommand(
    val title: String,
    val action: BasicSkkAction,
    val states: Set<KeyBindingState>,
    val emacsOnly: Boolean = false,
) {
    KANA("かな入力・確定", BasicSkkAction.Kana, ordinaryStates),
    CANCEL("取消", BasicSkkAction.Cancel, KeyBindingState.entries.toSet()),
    HALFWIDTH("半角カナ", BasicSkkAction.Halfwidth, ordinaryStates),
    ENTER("確定", BasicSkkAction.Enter, ordinaryStates - KeyBindingState.DIRECT),
    TOGGLE_KANA("かな種別切替", BasicSkkAction.ToggleKana,
        idleStates + pendingStates + readingStates + selectionStates),
    START_READING("読み開始", BasicSkkAction.StartReading,
        idleStates + pendingStates + readingStates + selectionStates),
    ABBREV("英字見出し語", BasicSkkAction.StartAbbrev, idleStates + pendingStates + selectionStates),
    SUFFIX("接頭辞・接尾辞", BasicSkkAction.StartSuffix,
        idleStates + pendingStates + readingStates + selectionStates),
    DIRECT("直接入力", BasicSkkAction.ToDirect, idleStates + pendingStates + candidateStates),
    FULLWIDTH("全角英数", BasicSkkAction.ToFullwidth, idleStates + pendingStates + selectionStates),
    CONVERT("変換・次候補", BasicSkkAction.ConvertNext, readingLikeStates + selectionStates),
    PREVIOUS("前候補", BasicSkkAction.PreviousCandidate, selectionStates),
    DELETE_CANDIDATE("候補の削除確認", BasicSkkAction.DeleteCandidate, selectionStates),
    REGISTER("単語登録", BasicSkkAction.RegisterCandidate, readingLikeStates + selectionStates),
    COMPLETE("次の補完", BasicSkkAction.CompleteForward, readingLikeStates),
    COMPLETE_BACK("前の補完", BasicSkkAction.CompleteBackward, readingLikeStates),
    ACCEPT_COMPLETION("動的補完の受諾", BasicSkkAction.AcceptDynamicCompletion,
        readingStates + abbrevStates),
    EDIT_HOME("行頭へ移動", BasicSkkAction.Edit(EditCommand.HOME), editableStates, true),
    EDIT_END("行末へ移動", BasicSkkAction.Edit(EditCommand.END), editableStates, true),
    EDIT_LEFT("一文字戻る", BasicSkkAction.Edit(EditCommand.LEFT), editableStates, true),
    EDIT_RIGHT("一文字進む", BasicSkkAction.Edit(EditCommand.RIGHT), editableStates, true),
    EDIT_UP("前の行", BasicSkkAction.Edit(EditCommand.UP), editableStates, true),
    EDIT_DOWN("次の行", BasicSkkAction.Edit(EditCommand.DOWN), editableStates, true),
    EDIT_BACKSPACE("前の文字を削除", BasicSkkAction.Edit(EditCommand.BACKSPACE), editableStates, true),
    EDIT_DELETE("次の文字を削除", BasicSkkAction.Edit(EditCommand.DELETE), editableStates, true),
    EDIT_KILL_LINE("行末まで削除", BasicSkkAction.Edit(EditCommand.KILL_LINE), editableStates, true),
    EDIT_WORD_BACKWARD("前の単語", BasicSkkAction.Edit(EditCommand.WORD_BACKWARD), editableStates, true),
    EDIT_WORD_FORWARD("次の単語", BasicSkkAction.Edit(EditCommand.WORD_FORWARD), editableStates, true),
}

/** 必須操作を消せない完全な割り当てです。同じ状態で競合する設定は公開しません。 */
class KeyBindings(bindings: Map<SkkCommand, KeyGesture> = defaults) {
    val bindings: Map<SkkCommand, KeyGesture> = Collections.unmodifiableMap(LinkedHashMap(bindings))
    init {
        require(this.bindings.keys == SkkCommand.entries.toSet()) { "すべての操作にキーを割り当てます" }
        validate(emacsEnabled = false)
    }

    /** Emacs 編集が無効なら、その割り当ては競合にも実行にも参加しません。 */
    fun validate(emacsEnabled: Boolean = false) {
        val entries = bindings.entries.filter { emacsEnabled || !it.key.emacsOnly }
        entries.forEach { (command, key) ->
            require(KeyBindingState.DELETION_CONFIRMATION !in command.states || key.ctrl || key.alt ||
                key.text !in setOf("y", "n")) {
                "候補削除確認の y と n は変更できません"
            }
        }
        for ((index, left) in entries.withIndex()) for (right in entries.drop(index + 1)) {
            require(!left.value.overlaps(right.value) || left.key.states.intersect(right.key.states).isEmpty()) {
                "${left.key.title}と${right.key.title}のキーが同じ入力状態で重複しています"
            }
        }
    }

    fun validateLabels(labels: String, emacsEnabled: Boolean = false) {
        validate(emacsEnabled)
        bindings.filterKeys { emacsEnabled || !it.emacsOnly }.forEach { (command, key) ->
            require(menuStates.intersect(command.states).isEmpty() || key.ctrl || key.alt ||
                key.shift && !key.ignoreShift ||
                key.text == null || key.text.singleOrNull() !in labels.toSet()) {
                "${command.title}が候補選択キーと重複しています"
            }
        }
    }

    fun resolve(
        gesture: KeyGesture,
        state: BasicSkkState,
        view: BasicSkkView,
        emacsEnabled: Boolean = false,
    ): BasicSkkAction? {
        val context = context(state, view)
        val command = bindings.entries.firstOrNull { (command, key) ->
            key.matches(gesture) && context in command.states && (emacsEnabled || !command.emacsOnly) &&
                (command != SkkCommand.ACCEPT_COMPLETION || view.completion != null)
        }?.key ?: return null
        return when {
            context in selectionStates && command == SkkCommand.EDIT_DOWN -> BasicSkkAction.ConvertNext
            context in selectionStates &&
                (command == SkkCommand.EDIT_UP || command == SkkCommand.EDIT_BACKSPACE) ->
                BasicSkkAction.PreviousCandidate
            else -> command.action
        }
    }

    private fun context(state: BasicSkkState, view: BasicSkkView): KeyBindingState {
        if (view.deletion != null) return KeyBindingState.DELETION_CONFIRMATION
        if (state.registrationSaving) return KeyBindingState.REGISTRATION_SAVING
        val registration = state.registrationDepth > 0
        if (state.completionCycling) return if (state.phase == InputPhase.ABBREV) {
            if (registration) KeyBindingState.REGISTRATION_COMPLETION_ABBREV else KeyBindingState.COMPLETION_ABBREV
        } else if (registration) KeyBindingState.REGISTRATION_COMPLETION_READING else KeyBindingState.COMPLETION_READING
        return when (state.phase) {
            InputPhase.READING -> if (registration) KeyBindingState.REGISTRATION_READING else KeyBindingState.READING
            InputPhase.ABBREV -> if (registration) KeyBindingState.REGISTRATION_ABBREV else KeyBindingState.ABBREV
            InputPhase.SELECTING -> if ((view.registration?.innerCandidate ?: view.candidate)?.menu?.isNotEmpty() == true) {
                if (registration) KeyBindingState.REGISTRATION_MENU else KeyBindingState.MENU
            } else if (registration) KeyBindingState.REGISTRATION_CANDIDATE else KeyBindingState.CANDIDATE
            InputPhase.IDLE -> when {
                registration && state.mode == InputMode.DIRECT -> KeyBindingState.REGISTRATION_DIRECT
                registration && state.mode == InputMode.FULLWIDTH -> KeyBindingState.REGISTRATION_FULLWIDTH
                registration && state.pendingRomaji.isNotEmpty() -> KeyBindingState.REGISTRATION_PENDING
                registration -> KeyBindingState.REGISTRATION_BODY
                state.mode == InputMode.DIRECT -> KeyBindingState.DIRECT
                state.mode == InputMode.FULLWIDTH -> KeyBindingState.FULLWIDTH
                state.pendingRomaji.isNotEmpty() -> KeyBindingState.PENDING
                else -> KeyBindingState.IDLE
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is KeyBindings && bindings == other.bindings
    override fun hashCode(): Int = bindings.hashCode()

    companion object {
        val defaults: Map<SkkCommand, KeyGesture> = Collections.unmodifiableMap(linkedMapOf(
            SkkCommand.KANA to KeyGesture("j", ctrl = true),
            SkkCommand.CANCEL to KeyGesture("g", ctrl = true),
            SkkCommand.HALFWIDTH to KeyGesture("q", ctrl = true),
            SkkCommand.ENTER to KeyGesture(special = SpecialKey.ENTER),
            SkkCommand.TOGGLE_KANA to KeyGesture("q", ignoreShift = true),
            SkkCommand.START_READING to KeyGesture("Q", ignoreShift = true),
            SkkCommand.ABBREV to KeyGesture("/", ignoreShift = true),
            SkkCommand.SUFFIX to KeyGesture(">", ignoreShift = true),
            SkkCommand.DIRECT to KeyGesture("l", ignoreShift = true),
            SkkCommand.FULLWIDTH to KeyGesture("L", ignoreShift = true),
            SkkCommand.CONVERT to KeyGesture(" ", ignoreShift = true),
            SkkCommand.PREVIOUS to KeyGesture("x", ignoreShift = true),
            SkkCommand.DELETE_CANDIDATE to KeyGesture("X", ignoreShift = true),
            SkkCommand.REGISTER to KeyGesture("r", ctrl = true),
            SkkCommand.COMPLETE to KeyGesture(special = SpecialKey.TAB),
            SkkCommand.COMPLETE_BACK to KeyGesture(special = SpecialKey.TAB, shift = true),
            SkkCommand.ACCEPT_COMPLETION to KeyGesture(special = SpecialKey.RIGHT),
            SkkCommand.EDIT_HOME to KeyGesture("a", ctrl = true),
            SkkCommand.EDIT_END to KeyGesture("e", ctrl = true),
            SkkCommand.EDIT_LEFT to KeyGesture("b", ctrl = true),
            SkkCommand.EDIT_RIGHT to KeyGesture("f", ctrl = true),
            SkkCommand.EDIT_UP to KeyGesture("p", ctrl = true),
            SkkCommand.EDIT_DOWN to KeyGesture("n", ctrl = true),
            SkkCommand.EDIT_BACKSPACE to KeyGesture("h", ctrl = true),
            SkkCommand.EDIT_DELETE to KeyGesture("d", ctrl = true),
            SkkCommand.EDIT_KILL_LINE to KeyGesture("k", ctrl = true),
            SkkCommand.EDIT_WORD_BACKWARD to KeyGesture("b", alt = true),
            SkkCommand.EDIT_WORD_FORWARD to KeyGesture("f", alt = true),
        ))
    }
}
