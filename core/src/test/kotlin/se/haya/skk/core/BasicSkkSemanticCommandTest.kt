package se.haya.skk.core

import se.haya.skk.core.romaji.RomajiRule
import se.haya.skk.core.romaji.RomanRuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicSkkSemanticCommandTest {
    private class Dictionary(private val values: List<DictionaryCandidate> = emptyList()) : BasicSkkDictionary {
        val queries = mutableListOf<DictionaryQuery>()
        override fun lookup(query: DictionaryQuery): List<DictionaryCandidate> {
            queries += query
            return values
        }
    }

    @Test fun `既存Textは旧コマンドを保ちrawTextはqを規則またはliteralへ渡す`() {
        val legacy = BasicSkkEngine(Dictionary())
        legacy.dispatch(BasicSkkAction.Text("q"))
        assertEquals(InputMode.KATAKANA, legacy.state.mode)

        val qRule = RomanRuleSet.compile(listOf(RomajiRule("q", "ん")))
        val custom = BasicSkkEngine(Dictionary(), RegistrationPolicy(), romanRuleSet = qRule)
        assertEquals("ん", custom.dispatch(BasicSkkAction.Text("q", interpretCommands = false)).commit)
        assertEquals(InputMode.HIRAGANA, custom.state.mode)

        val noQRule = RomanRuleSet.compile(listOf(RomajiRule("a", "あ")))
        val literal = BasicSkkEngine(Dictionary(), RegistrationPolicy(), romanRuleSet = noQRule)
        assertEquals("q", literal.dispatch(BasicSkkAction.Text("q", interpretCommands = false)).commit)
        assertEquals("Q", BasicSkkEngine(Dictionary(), RegistrationPolicy(), romanRuleSet = noQRule)
            .dispatch(BasicSkkAction.Text("Q", interpretCommands = false)).view.composing)
    }

    @Test fun `意味操作は文字配置に依存せず変換とモードを遷移する`() {
        val dictionary = Dictionary(listOf(DictionaryCandidate("蚊"), DictionaryCandidate("科")))
        val engine = BasicSkkEngine(dictionary)
        engine.dispatch(BasicSkkAction.StartReading)
        engine.dispatch(BasicSkkAction.Text("ka", interpretCommands = false))
        assertEquals("蚊", engine.dispatch(BasicSkkAction.ConvertNext).view.candidate?.committedText)
        assertEquals("科", engine.dispatch(BasicSkkAction.ConvertNext).view.candidate?.committedText)
        assertEquals("蚊", engine.dispatch(BasicSkkAction.PreviousCandidate).view.candidate?.committedText)
        assertEquals("蚊", engine.dispatch(BasicSkkAction.Enter).commit)

        engine.dispatch(BasicSkkAction.ToggleKana)
        assertEquals(InputMode.KATAKANA, engine.state.mode)
        engine.dispatch(BasicSkkAction.ToDirect)
        assertEquals(InputMode.DIRECT, engine.state.mode)
        engine.dispatch(BasicSkkAction.Kana)
        engine.dispatch(BasicSkkAction.ToFullwidth)
        assertEquals(InputMode.FULLWIDTH, engine.state.mode)
    }

    @Test fun `読み中の英数意味操作は終端規則を適用してモードを切り替える`() {
        val direct = BasicSkkEngine(Dictionary())
        direct.dispatch(BasicSkkAction.StartReading)
        direct.dispatch(BasicSkkAction.Text("kan", interpretCommands = false))
        assertEquals("かん", direct.dispatch(BasicSkkAction.ToDirect).commit)
        assertEquals(InputMode.DIRECT, direct.state.mode)

        val discarded = BasicSkkEngine(Dictionary())
        discarded.dispatch(BasicSkkAction.StartReading)
        discarded.dispatch(BasicSkkAction.Text("kab", interpretCommands = false))
        assertEquals("か", discarded.dispatch(BasicSkkAction.ToFullwidth).commit)
        assertEquals(InputMode.FULLWIDTH, discarded.state.mode)
    }

    @Test fun `rawバッチは再帰登録へ旧コマンド文字を漏れなく本文として渡す`() {
        val engine = BasicSkkEngine(Dictionary(), RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.Text("Michi "))

        val body = engine.dispatch(BasicSkkAction.Text("q l>", interpretCommands = false))
        assertEquals("q l>", body.view.registration?.body)
        assertNull(body.commit)
        assertEquals(InputMode.HIRAGANA, engine.state.mode)
    }

    @Test fun `rawTextは削除開始と候補前後操作を迂回するが候補labelは選べる`() {
        val values = (1..6).map { DictionaryCandidate("候補$it") }
        val raw = BasicSkkEngine(Dictionary(values), RegistrationPolicy(), deletionEnabled = true)
        raw.dispatch(BasicSkkAction.Text("Ka "))
        val committed = raw.dispatch(BasicSkkAction.Text("x", interpretCommands = false))
        assertEquals("候補1", committed.commit)
        assertEquals("x", committed.view.composing)
        assertNull(committed.view.deletion)

        val labeled = BasicSkkEngine(Dictionary(values))
        labeled.dispatch(BasicSkkAction.Text("Ka "))
        repeat(2) { labeled.dispatch(BasicSkkAction.ConvertNext) }
        assertEquals("候補3", labeled.dispatch(
            BasicSkkAction.Text("a", interpretCommands = false),
        ).commit)
    }

    @Test fun `明示登録は読みと選択候補の正確な戻り先を保持する`() {
        val policy = RegistrationPolicy(enabled = true)
        val reading = BasicSkkEngine(Dictionary(), policy)
        reading.dispatch(BasicSkkAction.Text("Ka"))
        val started = reading.dispatch(BasicSkkAction.RegisterCandidate)
        assertEquals("か", started.view.registration?.readingKey)
        assertEquals("か", reading.dispatch(BasicSkkAction.Enter).view.composing)

        val selected = BasicSkkEngine(Dictionary(listOf(DictionaryCandidate("蚊"))), policy)
        selected.dispatch(BasicSkkAction.Text("Ka"))
        selected.dispatch(BasicSkkAction.ConvertNext)
        assertTrue(selected.dispatch(BasicSkkAction.RegisterCandidate).view.registration != null)
        val restored = selected.dispatch(BasicSkkAction.Enter)
        assertEquals("蚊", restored.view.candidate?.committedText)
        assertNull(restored.commit)
    }

    @Test fun `明示登録でcustom終端pendingを検索keyに使い空Enterで完全復元する`() {
        val rules = RomanRuleSet.compile(listOf(RomajiRule("z", "", terminalOutput = "ん")))
        val engine = BasicSkkEngine(
            Dictionary(), RegistrationPolicy(enabled = true), romanRuleSet = rules,
        )
        engine.dispatch(BasicSkkAction.StartReading)
        engine.dispatch(BasicSkkAction.Text("z", interpretCommands = false))
        assertEquals("z", engine.state.pendingRomaji)

        val registration = engine.dispatch(BasicSkkAction.RegisterCandidate)
        assertEquals("ん", registration.view.registration?.readingKey)
        val restored = engine.dispatch(BasicSkkAction.Enter)
        assertEquals("", engine.state.reading)
        assertEquals("z", engine.state.pendingRomaji)
        assertEquals("z", restored.view.composing)
        assertNull(restored.commit)
    }

    @Test fun `無効状態の意味操作は入力や候補を確定しない`() {
        val engine = BasicSkkEngine(Dictionary())
        listOf(
            BasicSkkAction.ConvertNext,
            BasicSkkAction.PreviousCandidate,
            BasicSkkAction.StartAbbrev,
            BasicSkkAction.StartSuffix,
            BasicSkkAction.RegisterCandidate,
        ).forEach { action ->
            val result = if (action == BasicSkkAction.StartAbbrev || action == BasicSkkAction.StartSuffix) {
                engine.dispatch(BasicSkkAction.ToDirect)
                engine.dispatch(action)
            } else engine.dispatch(action)
            assertNull(result.commit)
            assertNull(result.view.candidate)
        }
    }
}
