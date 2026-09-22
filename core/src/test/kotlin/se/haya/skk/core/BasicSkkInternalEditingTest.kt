package se.haya.skk.core

import se.haya.skk.core.editing.EditCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicSkkInternalEditingTest {
    private class Dictionary(private val values: List<DictionaryCandidate> = emptyList()) : BasicSkkDictionary {
        override fun lookup(query: DictionaryQuery) = values
    }

    private fun abbrev(text: String): BasicSkkEngine = BasicSkkEngine(Dictionary()).also {
        it.dispatch(BasicSkkAction.StartAbbrev)
        it.dispatch(BasicSkkAction.Text(text, interpretCommands = false))
    }

    @Test fun `読みの全編集操作をpure Editingの結果として適用する`() {
        val engine = abbrev("日本語 abc_def-12\n短\nabcd")
        engine.dispatch(BasicSkkAction.Edit(EditCommand.HOME))
        engine.dispatch(BasicSkkAction.Edit(EditCommand.UP))
        assertEquals("日本語 abc_def-12\n短\nabcd".indexOf("短"), engine.state.cursor)
        engine.dispatch(BasicSkkAction.Edit(EditCommand.UP))
        assertEquals(0, engine.state.cursor)
        engine.dispatch(BasicSkkAction.Edit(EditCommand.END))
        engine.dispatch(BasicSkkAction.Edit(EditCommand.WORD_BACKWARD))
        assertEquals("日本語 abc_def-12".indexOf("12"), engine.state.cursor)
        engine.dispatch(BasicSkkAction.Edit(EditCommand.WORD_FORWARD))
        engine.dispatch(BasicSkkAction.Edit(EditCommand.KILL_LINE))
        engine.dispatch(BasicSkkAction.Edit(EditCommand.DELETE))
        engine.dispatch(BasicSkkAction.Edit(EditCommand.LEFT))
        engine.dispatch(BasicSkkAction.Edit(EditCommand.RIGHT))
        engine.dispatch(BasicSkkAction.Edit(EditCommand.BACKSPACE))
        assertTrue(engine.state.reading.startsWith("日本語 abc_def-1"))
    }

    @Test fun `縦移動は短い行を越えて目標列を保ち非縦操作で破棄する`() {
        val engine = abbrev("abcd\nx\nwxyz")
        engine.dispatch(BasicSkkAction.Edit(EditCommand.HOME))
        repeat(3) { engine.dispatch(BasicSkkAction.Edit(EditCommand.RIGHT)) }
        engine.dispatch(BasicSkkAction.Edit(EditCommand.UP))
        assertEquals(6, engine.state.cursor)
        engine.dispatch(BasicSkkAction.Edit(EditCommand.UP))
        assertEquals(3, engine.state.cursor)
        engine.dispatch(BasicSkkAction.Edit(EditCommand.LEFT))
        engine.dispatch(BasicSkkAction.Edit(EditCommand.DOWN))
        engine.dispatch(BasicSkkAction.Edit(EditCommand.DOWN))
        assertEquals(9, engine.state.cursor)
    }

    @Test fun `未消化ローマ字はBSなら削除し他操作なら対象に応じて終端化する`() {
        val reading = BasicSkkEngine(Dictionary())
        reading.dispatch(BasicSkkAction.StartReading)
        reading.dispatch(BasicSkkAction.Text("n", interpretCommands = false))
        reading.dispatch(BasicSkkAction.Edit(EditCommand.RIGHT))
        assertEquals("ん", reading.state.reading)
        assertEquals("", reading.state.pendingRomaji)

        val backspace = BasicSkkEngine(Dictionary())
        backspace.dispatch(BasicSkkAction.Text("n", interpretCommands = false))
        assertNull(backspace.dispatch(BasicSkkAction.Edit(EditCommand.BACKSPACE)).commit)
        assertEquals("", backspace.state.pendingRomaji)

        val idle = BasicSkkEngine(Dictionary())
        idle.dispatch(BasicSkkAction.Text("n", interpretCommands = false))
        val committed = idle.dispatch(BasicSkkAction.Edit(EditCommand.DELETE))
        assertEquals("ん", committed.commit)
        assertEquals(InputPhase.IDLE, idle.state.phase)
    }

    @Test fun `登録内のpendingを本文へ吸収してその一打で停止する`() {
        val engine = BasicSkkEngine(Dictionary(), RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.StartReading)
        engine.dispatch(BasicSkkAction.Text("mi", interpretCommands = false))
        engine.dispatch(BasicSkkAction.RegisterCandidate)
        engine.dispatch(BasicSkkAction.Text("文", interpretCommands = false))
        engine.dispatch(BasicSkkAction.Text("n", interpretCommands = false))
        val edited = engine.dispatch(BasicSkkAction.Edit(EditCommand.LEFT))
        assertEquals("文ん", edited.view.registration?.body)
        assertEquals(2, edited.view.registration?.cursor)
        assertNull(edited.commit)
    }

    @Test fun `登録内のpending終端化が本文上限を超える場合は原子的に戻す`() {
        val engine = BasicSkkEngine(Dictionary(), RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.StartReading)
        engine.dispatch(BasicSkkAction.Text("mi", interpretCommands = false))
        engine.dispatch(BasicSkkAction.RegisterCandidate)
        engine.dispatch(BasicSkkAction.Text("あ".repeat(65_536), interpretCommands = false))
        engine.dispatch(BasicSkkAction.Text("n", interpretCommands = false))

        val refused = engine.dispatch(BasicSkkAction.Edit(EditCommand.LEFT))
        assertEquals(65_536, refused.view.registration?.body?.length)
        assertEquals("n", engine.state.pendingRomaji)
        assertTrue(refused.notice.orEmpty().contains("65,536"))
    }

    @Test fun `動的補完は編集で破棄し手動補完は現在値を受諾して編集する`() {
        val dictionary = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun complete(query: CompletionQuery) = listOf("にほん")
        }
        val dynamic = BasicSkkEngine(
            dictionary, RegistrationPolicy(), completionConfig = CompletionConfig(dynamicEnabled = true),
        )
        dynamic.dispatch(BasicSkkAction.StartReading)
        assertTrue(dynamic.dispatch(BasicSkkAction.Text("ni", interpretCommands = false)).view.completion != null)
        val discarded = dynamic.dispatch(BasicSkkAction.Edit(EditCommand.LEFT))
        assertNull(discarded.view.completion)
        assertEquals(0, dynamic.state.cursor)
        assertEquals("に", dynamic.state.reading)

        val manual = BasicSkkEngine(dictionary)
        manual.dispatch(BasicSkkAction.StartReading)
        manual.dispatch(BasicSkkAction.Text("ni", interpretCommands = false))
        manual.dispatch(BasicSkkAction.CompleteForward)
        assertTrue(manual.state.completionCycling)
        manual.dispatch(BasicSkkAction.Edit(EditCommand.HOME))
        assertFalse(manual.state.completionCycling)
        assertEquals("にほん", manual.state.reading)
        assertEquals(0, manual.state.cursor)
    }

    @Test fun `送り境界より前の削除は境界を移し語幹が空なら送りを解除する`() {
        val shifted = BasicSkkEngine(Dictionary())
        shifted.dispatch(BasicSkkAction.Text("KakiKu", interpretCommands = false))
        shifted.dispatch(BasicSkkAction.Edit(EditCommand.HOME))
        shifted.dispatch(BasicSkkAction.Edit(EditCommand.DELETE))
        assertEquals("き", shifted.state.reading)
        assertEquals("く", shifted.state.okuri)

        val emptyStem = BasicSkkEngine(Dictionary())
        emptyStem.dispatch(BasicSkkAction.Text("KaKu", interpretCommands = false))
        emptyStem.dispatch(BasicSkkAction.Edit(EditCommand.HOME))
        emptyStem.dispatch(BasicSkkAction.Edit(EditCommand.DELETE))
        assertEquals("く", emptyStem.state.reading)
        assertEquals("", emptyStem.state.okuri)
        assertNull(emptyStem.state.okuriConsonant)
    }

    @Test fun `候補中の編集は候補を確定せず保持する`() {
        val engine = BasicSkkEngine(Dictionary(listOf(DictionaryCandidate("蚊"))))
        engine.dispatch(BasicSkkAction.Text("Ka"))
        engine.dispatch(BasicSkkAction.ConvertNext)
        val result = engine.dispatch(BasicSkkAction.Edit(EditCommand.KILL_LINE))
        assertNull(result.commit)
        assertEquals("蚊", result.view.candidate?.committedText)
        assertTrue(result.handled)
        assertTrue(result.notice.orEmpty().isNotEmpty())
    }

    @Test fun `内部対象がなければ外部編集のため未処理を返す`() {
        val result = BasicSkkEngine(Dictionary()).dispatch(BasicSkkAction.Edit(EditCommand.HOME))
        assertFalse(result.handled)
        assertNull(result.commit)
    }

    @Test fun `候補中の意味モード操作は候補確定後に再帰適用する`() {
        fun selected() = BasicSkkEngine(Dictionary(listOf(DictionaryCandidate("蚊")))).also {
            it.dispatch(BasicSkkAction.Text("Ka"))
            it.dispatch(BasicSkkAction.ConvertNext)
        }
        assertEquals(InputMode.DIRECT, selected().run {
            assertEquals("蚊", dispatch(BasicSkkAction.ToDirect).commit); state.mode
        })
        assertEquals(InputMode.FULLWIDTH, selected().run {
            assertEquals("蚊", dispatch(BasicSkkAction.ToFullwidth).commit); state.mode
        })
        val abbrev = selected()
        assertEquals("蚊", abbrev.dispatch(BasicSkkAction.StartAbbrev).commit)
        assertEquals(InputPhase.ABBREV, abbrev.state.phase)
    }
}
