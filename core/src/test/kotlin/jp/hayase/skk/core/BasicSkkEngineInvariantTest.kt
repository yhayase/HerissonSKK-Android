package jp.hayase.skk.core

import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class BasicSkkEngineInvariantTest {
    /** N02/N08: 合成キー列で段階をまたいでも本文・表示のカーソルとUTF-16を壊しません。 */
    @Test fun `再現可能な混合操作でUnicode境界と取消の不変条件を保つ`() {
        val actions = listOf("a", "k", "n", "q", "Q", "K", "l", "L", "'", ">", "/", " ",
            "😀", "か\u3099", "👩‍💻", "\u0301", "각", "x").map(BasicSkkAction::Text) +
            listOf(BasicSkkAction.Left, BasicSkkAction.Right, BasicSkkAction.Home, BasicSkkAction.End,
                BasicSkkAction.Backspace, BasicSkkAction.Delete, BasicSkkAction.Enter,
                BasicSkkAction.Cancel, BasicSkkAction.Kana, BasicSkkAction.Halfwidth)
        repeat(32) { seed ->
            val random = Random(seed.toLong())
            val engine = BasicSkkEngine { query ->
                if (query.readingKey.length % 3 == 0) emptyList() else
                    listOf(DictionaryCandidate("候補😀", "合成注釈"), DictionaryCandidate("仮"))
            }
            val history = mutableListOf<BasicSkkAction>()
            repeat(500) { step ->
                val action = actions[random.nextInt(actions.size)]
                history += action
                try {
                    val result = engine.dispatch(action)
                    val state = engine.state
                    EditableBuffer(state.reading + state.okuri, state.cursor)
                    result.commit?.let { EditableBuffer(it) }
                    result.view.composing?.let { text ->
                        EditableBuffer(text, result.view.cursor ?: text.length)
                    }
                    result.view.candidate?.let { EditableBuffer(it.committedText) }
                    if (action == BasicSkkAction.Cancel) assertNull(result.commit)
                    assertEquals(state.phase == InputPhase.SELECTING, result.view.candidate != null)
                } catch (failure: Throwable) {
                    throw AssertionError("seed=$seed step=$step actions=$history", failure)
                }
            }
            repeat(3) { assertNull(engine.dispatch(BasicSkkAction.Cancel).commit) }
            assertEquals(InputPhase.IDLE, engine.state.phase)
            assertNull(engine.currentView.composing)
        }
    }
}
