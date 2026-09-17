package jp.hayase.skk.core.keys

import jp.hayase.skk.core.*
import jp.hayase.skk.core.editing.EditCommand
import org.junit.Assert.*
import org.junit.Test

class KeyBindingsTest {
    private fun engine() = BasicSkkEngine(BasicSkkDictionary { List(8) { DictionaryCandidate("候補$it") } })

    @Test fun `同じキーでも対象状態が交わらない操作は保存できる`() {
        val values = KeyBindings.defaults.toMutableMap()
        values[SkkCommand.DIRECT] = KeyGesture("z")
        values[SkkCommand.CONVERT] = KeyGesture("z")
        // 直接入力には単独候補からの確定もあるため、変換とは重複します。
        assertThrows(IllegalArgumentException::class.java) { KeyBindings(values) }
        values[SkkCommand.DIRECT] = KeyBindings.defaults.getValue(SkkCommand.DIRECT)
        values[SkkCommand.COMPLETE] = KeyGesture("l")
        KeyBindings(values).validateLabels("asdfjkl")
    }

    @Test fun `確定取消の欠落と候補ラベルへの割り当てを拒否する`() {
        assertThrows(IllegalArgumentException::class.java) { KeyBindings(KeyBindings.defaults - SkkCommand.CANCEL) }
        val values = KeyBindings.defaults + (SkkCommand.DELETE_CANDIDATE to KeyGesture("a"))
        assertThrows(IllegalArgumentException::class.java) { KeyBindings(values).validateLabels("asdfjkl") }
        KeyBindings().validateLabels("asdfjkl")
        KeyBindings().validateLabels("1234567")
    }

    @Test fun `変更したかな切替キーだけが現在状態で解決される`() {
        val engine = engine()
        val map = KeyBindings(KeyBindings.defaults + (SkkCommand.TOGGLE_KANA to KeyGesture("[")))
        assertNull(map.resolve(KeyGesture("q"), engine.state, engine.currentView))
        assertEquals(BasicSkkAction.ToggleKana, map.resolve(KeyGesture("["), engine.state, engine.currentView))
        engine.dispatch(BasicSkkAction.StartAbbrev)
        assertNull(map.resolve(KeyGesture("["), engine.state, engine.currentView))
    }

    @Test fun `候補一覧のlは直接入力への切替にならない`() {
        val engine = engine()
        engine.dispatch(BasicSkkAction.Text("Ka "))
        val map = KeyBindings()
        assertEquals(BasicSkkAction.ToDirect, map.resolve(KeyGesture("l"), engine.state, engine.currentView))
        repeat(2) { engine.dispatch(BasicSkkAction.ConvertNext) }
        assertTrue(engine.currentView.candidate!!.menu.isNotEmpty())
        assertNull(map.resolve(KeyGesture("l"), engine.state, engine.currentView))
    }

    @Test fun `無効なUnicodeと複数文字を拒否し特殊キーと文字を区別する`() {
        for (text in listOf("", "ab", "\uD800", "\uDC00", "\n")) {
            assertThrows(IllegalArgumentException::class.java) { KeyGesture(text) }
        }
        KeyGesture("😀")
        assertThrows(IllegalArgumentException::class.java) { KeyGesture("x", SpecialKey.ENTER) }
        assertThrows(IllegalArgumentException::class.java) { KeyGesture() }
        assertThrows(IllegalArgumentException::class.java) { KeyGesture("x", ctrl = true, ignoreShift = true) }
        assertThrows(IllegalArgumentException::class.java) { KeyGesture("x", shift = true, ignoreShift = true) }
    }

    @Test fun `標準の生成文字はShift状態を問わず一致し重なる割り当てを拒否する`() {
        val engine = engine()
        val map = KeyBindings()
        for (shift in listOf(false, true)) {
            assertEquals(BasicSkkAction.StartReading,
                map.resolve(KeyGesture("Q", shift = shift), engine.state, engine.currentView))
            assertEquals(BasicSkkAction.StartSuffix,
                map.resolve(KeyGesture(">", shift = shift), engine.state, engine.currentView))
        }

        val overlap = KeyBindings.defaults +
            (SkkCommand.ABBREV to KeyGesture("q", shift = true))
        assertThrows(IllegalArgumentException::class.java) { KeyBindings(overlap) }
    }

    @Test fun `Emacs編集は有効時だけ解決しSKK操作との競合を検査する`() {
        val engine = engine()
        val map = KeyBindings()
        val gesture = KeyGesture("a", ctrl = true)
        assertNull(map.resolve(gesture, engine.state, engine.currentView))
        assertEquals(BasicSkkAction.Edit(EditCommand.HOME),
            map.resolve(gesture, engine.state, engine.currentView, emacsEnabled = true))

        val conflicting = KeyBindings.defaults +
            (SkkCommand.EDIT_HOME to KeyBindings.defaults.getValue(SkkCommand.CANCEL))
        val disabled = KeyBindings(conflicting)
        disabled.validate(emacsEnabled = false)
        assertThrows(IllegalArgumentException::class.java) { disabled.validate(emacsEnabled = true) }

        val reserved = KeyBindings(KeyBindings.defaults + (SkkCommand.EDIT_HOME to KeyGesture("y")))
        reserved.validate(emacsEnabled = false)
        assertThrows(IllegalArgumentException::class.java) { reserved.validate(emacsEnabled = true) }
        assertThrows(IllegalArgumentException::class.java) {
            KeyBindings(KeyBindings.defaults + (SkkCommand.CANCEL to KeyGesture("n")))
        }
    }

    @Test fun `候補中のEmacs前後移動とBSは候補操作を優先する`() {
        val engine = engine()
        engine.dispatch(BasicSkkAction.Text("Ka "))
        val map = KeyBindings()
        assertEquals(BasicSkkAction.ConvertNext, map.resolve(
            KeyGesture("n", ctrl = true), engine.state, engine.currentView, emacsEnabled = true))
        assertEquals(BasicSkkAction.PreviousCandidate, map.resolve(
            KeyGesture("p", ctrl = true), engine.state, engine.currentView, emacsEnabled = true))
        assertEquals(BasicSkkAction.PreviousCandidate, map.resolve(
            KeyGesture("h", ctrl = true), engine.state, engine.currentView, emacsEnabled = true))
        assertEquals(BasicSkkAction.Edit(EditCommand.KILL_LINE), map.resolve(
            KeyGesture("k", ctrl = true), engine.state, engine.currentView, emacsEnabled = true))
    }

    @Test fun `登録保存削除確認と補完巡回を独立状態として解決する`() {
        val engine = engine()
        val map = KeyBindings()
        val edit = KeyGesture("a", ctrl = true)
        val cancel = KeyGesture("g", ctrl = true)

        val registration = engine.state.copy(registrationDepth = 1)
        assertEquals(BasicSkkAction.Edit(EditCommand.HOME),
            map.resolve(edit, registration, engine.currentView, emacsEnabled = true))
        val saving = registration.copy(registrationSaving = true)
        assertEquals(BasicSkkAction.Edit(EditCommand.HOME),
            map.resolve(edit, saving, engine.currentView, emacsEnabled = true))
        assertNull(map.resolve(KeyGesture("q"), saving, engine.currentView, emacsEnabled = true))
        assertEquals(BasicSkkAction.Cancel, map.resolve(cancel, saving, engine.currentView, emacsEnabled = true))

        val deletion = engine.currentView.copy(deletion = CandidateDeletionView(
            "よみ", "候補", null, 1, 1, 0, false, false))
        assertEquals(BasicSkkAction.Edit(EditCommand.HOME),
            map.resolve(edit, engine.state, deletion, emacsEnabled = true))
        assertNull(map.resolve(KeyGesture("q"), engine.state, deletion, emacsEnabled = true))
        assertEquals(BasicSkkAction.Cancel, map.resolve(cancel, engine.state, deletion, emacsEnabled = true))

        val completion = engine.state.copy(completionCycling = true)
        assertEquals(BasicSkkAction.Edit(EditCommand.HOME),
            map.resolve(edit, completion, engine.currentView, emacsEnabled = true))
        assertEquals(BasicSkkAction.CompleteForward, map.resolve(
            KeyGesture(special = SpecialKey.TAB), completion, engine.currentView, emacsEnabled = true))
    }

    @Test fun `旧設定の衝突した新操作を未割当として保持する`() {
        val legacy = KeyBindings.defaults.filterKeys { it !in KeyBindings.optionalCommands } +
            (SkkCommand.EDIT_LEFT to KeyGesture("v", ctrl = true))
        val migrated = KeyBindings.addDefaultsPreservingExisting(legacy)
        assertEquals(KeyGesture("v", ctrl = true), migrated.bindings[SkkCommand.EDIT_LEFT])
        assertFalse(SkkCommand.EDIT_PAGE_DOWN in migrated.bindings)
        assertTrue(SkkCommand.EDIT_CUT in migrated.bindings)
        migrated.validate(emacsEnabled = true)
    }

    @Test fun `C-mは編集中の確定を優先し通常欄では改行する`() {
        val engine = engine()
        val bindings = KeyBindings()
        val cM = KeyGesture("m", ctrl = true)
        assertEquals(BasicSkkAction.Edit(EditCommand.NEWLINE),
            bindings.resolve(cM, engine.state, engine.currentView, true))
        engine.dispatch(BasicSkkAction.StartReading)
        assertEquals(BasicSkkAction.Enter,
            bindings.resolve(cM, engine.state, engine.currentView, true))
    }

    @Test fun `旧版の半角カナ標準キーは引用に移りカスタムC-qは保持する`() {
        val legacy = KeyBindings(KeyBindings.defaults - SkkCommand.QUOTE_NEXT +
            (SkkCommand.HALFWIDTH to KeyGesture("q", ctrl = true)))
        val migrated = KeyBindings.migrateQuoteNext(legacy)
        assertFalse(SkkCommand.HALFWIDTH in migrated.bindings)
        assertEquals(KeyGesture("q", ctrl = true), migrated.bindings[SkkCommand.QUOTE_NEXT])

        val custom = KeyBindings(KeyBindings.defaults - SkkCommand.QUOTE_NEXT +
            (SkkCommand.HALFWIDTH to KeyGesture("z", ctrl = true)) +
            (SkkCommand.EDIT_LEFT to KeyGesture("q", ctrl = true)))
        val preserved = KeyBindings.migrateQuoteNext(custom)
        assertEquals(KeyGesture("z", ctrl = true), preserved.bindings[SkkCommand.HALFWIDTH])
        assertEquals(KeyGesture("q", ctrl = true), preserved.bindings[SkkCommand.EDIT_LEFT])
        assertFalse(SkkCommand.QUOTE_NEXT in preserved.bindings)
    }
}
