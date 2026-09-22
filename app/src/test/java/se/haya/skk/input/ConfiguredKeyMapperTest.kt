package se.haya.skk.input

import android.view.KeyCharacterMap
import android.view.KeyEvent
import se.haya.skk.core.*
import se.haya.skk.core.keys.*
import se.haya.skk.core.editing.EditCommand
import se.haya.skk.core.romaji.AzikRules
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class ConfiguredKeyMapperTest {
    private fun key(char: Char, meta: Int = 0) = object : KeyEvent(0, 0, ACTION_DOWN, KEYCODE_UNKNOWN, 0, meta) {
        override fun getUnicodeChar(metaState: Int) = char.code
    }
    private fun engine() = BasicSkkEngine(BasicSkkDictionary { List(8) { DictionaryCandidate("候補$it") } })

    @Test fun `C-qは次キー引用となり半角カナは再割当できる`() {
        val engine = engine()
        val mapper = HardwareKeyMapper()
        val cQ = key('q', KeyEvent.META_CTRL_ON)
        assertEquals(HardwareKeyMapper.Decoded.QuoteNext,
            mapper.decodeConfigured(cQ, engine.state, engine.currentView, KeyBindings(), true))
        val custom = KeyBindings(KeyBindings.defaults - SkkCommand.QUOTE_NEXT +
            (SkkCommand.HALFWIDTH to KeyGesture("z", ctrl = true)))
        assertEquals(HardwareKeyMapper.Decoded.Pass,
            mapper.decodeConfigured(cQ, engine.state, engine.currentView, custom, true))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Halfwidth),
            mapper.decodeConfigured(key('z', KeyEvent.META_CTRL_ON), engine.state,
                engine.currentView, custom, true))

        val shifted = KeyBindings(KeyBindings.defaults +
            (SkkCommand.HALFWIDTH to KeyGesture("Q", ctrl = true, shift = true)))
        val cShiftQ = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q, 0,
            KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON, KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Halfwidth),
            mapper.decodeConfigured(cShiftQ, engine.state, engine.currentView, shifted, true))
    }

    @Test fun `zの記号規則は既定コマンドより先に文字として渡す`() {
        for (reading in listOf(false, true)) for (second in listOf('l', 'L', '/', ' ')) {
            val engine = engine()
            if (reading) engine.dispatch(BasicSkkAction.StartReading)
            engine.dispatch(BasicSkkAction.Text("z", interpretCommands = false))
            val decoded = HardwareKeyMapper().decodeConfigured(key(second), engine.state,
                engine.currentView, KeyBindings(), false)
            assertEquals("reading=$reading second=$second",
                HardwareKeyMapper.Decoded.Action(BasicSkkAction.Text(second.toString(), false)), decoded)
        }
    }

    @Test fun `標準割当の実キー列は旧コマンド文法と同じ結果になる`() {
        for (sequence in listOf("nihon", "nqkaq", "Qka ", "Ka   a", "Ka lABC", "Ka LABC", "Ka /API ", "Dai>", "Ka >kai")) {
            val original = engine()
            val configured = engine()
            val mapper = HardwareKeyMapper()
            for (char in sequence) {
                val expected = original.dispatch(BasicSkkAction.Text(char.toString()))
                val decoded = mapper.decodeConfigured(key(char), configured.state, configured.currentView, KeyBindings(), false)
                if (decoded == HardwareKeyMapper.Decoded.Pass && original.state.mode == InputMode.DIRECT) continue
                assertTrue("$sequence / $char", decoded is HardwareKeyMapper.Decoded.Action)
                val actual = configured.dispatch((decoded as HardwareKeyMapper.Decoded.Action).action)
                assertEquals("$sequence / $char", expected.commit, actual.commit)
                assertEquals("$sequence / $char", expected.view, actual.view)
                assertEquals("$sequence / $char", original.state, configured.state)
            }
        }
    }

    @Test fun `AZIKのqとlを含む継続規則と角括弧エスケープが切替に奪われない`() {
        val rules = AzikRules.ruleSet
        val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() }, RegistrationPolicy(), romanRuleSet = rules)
        val bindings = KeyBindings(KeyBindings.defaults + (SkkCommand.TOGGLE_KANA to KeyGesture("[", ignoreShift = true)))
        val mapper = HardwareKeyMapper()
        fun type(value: String) = buildString {
            value.forEach { char ->
                val decoded = mapper.decodeConfigured(key(char), engine.state, engine.currentView, bindings, false, rules)
                assertTrue(decoded is HardwareKeyMapper.Decoded.Action)
                append(engine.dispatch((decoded as HardwareKeyMapper.Decoded.Action).action).commit.orEmpty())
            }
        }
        assertEquals("んこん[ぁ", type("qklx[xxa"))
        assertEquals("", type("["))
        assertEquals(InputMode.KATAKANA, engine.state.mode)
        assertEquals("ン", type("q"))
        assertEquals("", type("l"))
        assertEquals(InputMode.DIRECT, engine.state.mode)
    }

    @Test fun `Emacsは有効時だけ解決し候補の前後と受諾を分ける`() {
        val engine = engine()
        val mapper = HardwareKeyMapper()
        fun decode(char: Char, enabled: Boolean) = mapper.decodeConfigured(key(char, KeyEvent.META_CTRL_ON),
            engine.state, engine.currentView, KeyBindings(), enabled)
        assertEquals(HardwareKeyMapper.Decoded.Pass, decode('f', false))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.RIGHT)), decode('f', true))
        engine.dispatch(BasicSkkAction.Text("Ka "))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.ConvertNext), decode('n', true))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Backspace), decode('h', true))
        repeat(2) { engine.dispatch(BasicSkkAction.ConvertNext) }
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.PreviousCandidate), decode('h', true))
    }

    @Test fun `C-bはEmacs有効時に読みと直接入力の編集へ配送する`() {
        val engine = engine()
        val mapper = HardwareKeyMapper()
        val ctrlB = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, 0,
            KeyEvent.META_CTRL_ON, KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
        fun decode(enabled: Boolean) = mapper.decodeConfigured(ctrlB, engine.state, engine.currentView,
            KeyBindings(), enabled)

        engine.dispatch(BasicSkkAction.StartReading)
        engine.dispatch(BasicSkkAction.Text("ni", interpretCommands = false))
        val original = engine.state.cursor
        assertEquals(HardwareKeyMapper.Decoded.Pass, decode(false))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.LEFT)), decode(true))
        val moved = engine.dispatch((decode(true) as HardwareKeyMapper.Decoded.Action).action)
        assertNull(moved.commit)
        assertEquals(original - 1, engine.state.cursor)
        assertEquals("に", engine.state.reading)

        engine.dispatch(BasicSkkAction.Cancel)
        engine.dispatch(BasicSkkAction.ToDirect)
        assertEquals(InputMode.DIRECT, engine.state.mode)
        assertEquals(HardwareKeyMapper.Decoded.Pass, decode(false))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.LEFT)), decode(true))
    }

    @Test fun `内部 Emacs だけを有効にすると未確定中だけ編集キーを受け付ける`() {
        val engine = engine()
        val mapper = HardwareKeyMapper()
        val ctrlB = key('b', KeyEvent.META_CTRL_ON)
        fun decode() = mapper.decodeConfigured(ctrlB, engine.state, engine.currentView, KeyBindings(), false,
            internalEmacsEnabled = true)

        assertEquals(HardwareKeyMapper.Decoded.Pass, decode())
        engine.dispatch(BasicSkkAction.Text("n"))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.LEFT)), decode())
        engine.dispatch(BasicSkkAction.StartReading)
        engine.dispatch(BasicSkkAction.Text("ni", interpretCommands = false))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.LEFT)), decode())
    }

    @Test fun `画面移動と文書端と選択操作のキーを解決する`() {
        val engine = engine()
        val mapper = HardwareKeyMapper()
        val bindings = KeyBindings()
        fun decoded(code: Int, meta: Int = 0) = mapper.decodeConfigured(
            KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0), engine.state, engine.currentView, bindings, true)
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.PAGE_DOWN)),
            decoded(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.PAGE_UP)),
            decoded(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.BUFFER_END)),
            decoded(KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_CTRL_ON))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.BUFFER_START)),
            mapper.decodeConfigured(key('<', KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON),
                engine.state, engine.currentView, bindings, true))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.BUFFER_END)),
            mapper.decodeConfigured(key('>', KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON),
                engine.state, engine.currentView, bindings, true))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.CUT)),
            decoded(KeyEvent.KEYCODE_W, KeyEvent.META_CTRL_ON))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.COPY)),
            decoded(KeyEvent.KEYCODE_W, KeyEvent.META_ALT_ON))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.NEWLINE)),
            decoded(KeyEvent.KEYCODE_M, KeyEvent.META_CTRL_ON))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.PASTE)),
            decoded(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.UNDO)),
            mapper.decodeConfigured(key('/', KeyEvent.META_CTRL_ON), engine.state,
                engine.currentView, bindings, true))
        engine.dispatch(BasicSkkAction.StartReading)
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Enter),
            decoded(KeyEvent.KEYCODE_M, KeyEvent.META_CTRL_ON))
    }

    @Test fun `物理キーのAlt Shift記号とCtrl mを編集操作へ配送する`() {
        val engine = engine()
        val mapper = HardwareKeyMapper()
        val bindings = KeyBindings()
        fun shifted(code: Int, symbol: Char) = object : KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0,
            KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_SHIFT_ON,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0) {
            override fun getUnicodeChar(metaState: Int): Int =
                if (metaState and KeyEvent.META_ALT_ON != 0) 0 else symbol.code
        }
        fun decode(event: KeyEvent) = mapper.decodeConfigured(event, engine.state,
            engine.currentView, bindings, true)
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.BUFFER_START)),
            decode(shifted(KeyEvent.KEYCODE_COMMA, '<')))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.BUFFER_END)),
            decode(shifted(KeyEvent.KEYCODE_PERIOD, '>')))
        val ctrlM = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_M, 0,
            KeyEvent.META_CTRL_ON, KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Edit(EditCommand.NEWLINE)), decode(ctrlM))
        engine.dispatch(BasicSkkAction.Text("Ka"))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Enter), decode(ctrlM))
    }

    @Test fun `登録の直接入力でも英字を入力先へ漏らさない`() {
        val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() }, RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.Text("Ka l"))
        val mapper = HardwareKeyMapper()
        for (char in "ql/ABC") {
            val decoded = mapper.decodeConfigured(key(char), engine.state, engine.currentView, KeyBindings(), false)
            assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Text(char.toString(), false)), decoded)
            val result = engine.dispatch((decoded as HardwareKeyMapper.Decoded.Action).action)
            assertNull(result.commit)
        }
        assertEquals("ql/ABC", engine.currentView.registration!!.body)
    }

    @Test fun `登録内の候補一覧でもlは一覧ラベルを選び直接入力に切り替えない`() {
        val engine = BasicSkkEngine(BasicSkkDictionary { List(12) { DictionaryCandidate("候補$it") } },
            RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.Text("Ka"))
        engine.dispatch(BasicSkkAction.RegisterCandidate)
        engine.dispatch(BasicSkkAction.Text("Ka   "))
        assertNull(engine.currentView.candidate)
        assertTrue(engine.currentView.registration!!.innerCandidate!!.menu.isNotEmpty())
        val decoded = HardwareKeyMapper().decodeConfigured(key('l'), engine.state, engine.currentView, KeyBindings(), false)
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Text("l", false)), decoded)
        val result = engine.dispatch((decoded as HardwareKeyMapper.Decoded.Action).action)
        assertEquals("候補8", result.view.registration!!.body)
        assertNull(result.commit)
        assertEquals(InputMode.HIRAGANA, engine.state.mode)
    }

    @Test fun `AltGrとデッドキーの生成文字を再度コマンドとして解釈しない`() {
        val engine = engine()
        val mapper = HardwareKeyMapper()
        val alt = key('q', KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_CTRL_ON)
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Text("q", false)),
            mapper.decodeConfigured(alt, engine.state, engine.currentView, KeyBindings(), true))
        val accent = object : KeyEvent(0, 0, ACTION_DOWN, KEYCODE_APOSTROPHE, 0, 0) {
            override fun getUnicodeChar(metaState: Int) = KeyCharacterMap.COMBINING_ACCENT or 0x00b4
        }
        assertEquals(HardwareKeyMapper.Decoded.Wait,
            mapper.decodeConfigured(accent, engine.state, engine.currentView, KeyBindings(), true))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Text("é", false)),
            mapper.decodeConfigured(key('e'), engine.state, engine.currentView, KeyBindings(), true))
    }

    @Test fun `削除確認と保存中のMetaショートカットを入力先へ漏らさない`() {
        val engine = engine()
        val mapper = HardwareKeyMapper()
        val event = key('a', KeyEvent.META_META_ON)
        assertEquals(HardwareKeyMapper.Decoded.Pass,
            mapper.decodeConfigured(event, engine.state, engine.currentView, KeyBindings(), true))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Enter), mapper.decodeConfigured(event,
            engine.state.copy(registrationSaving = true), engine.currentView, KeyBindings(), true))
        val confirmation = engine.currentView.copy(deletion = CandidateDeletionView(
            "よみ", "候補", null, 1, 1, 0, false, false))
        assertEquals(HardwareKeyMapper.Decoded.Action(BasicSkkAction.Enter),
            mapper.decodeConfigured(event, engine.state, confirmation, KeyBindings(), true))
    }
}
