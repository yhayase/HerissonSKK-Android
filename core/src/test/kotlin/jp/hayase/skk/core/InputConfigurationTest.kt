package jp.hayase.skk.core

import jp.hayase.skk.core.romaji.RomajiRule
import jp.hayase.skk.core.romaji.Romanizer
import jp.hayase.skk.core.romaji.RomanRuleSet
import org.junit.Assert.*
import org.junit.Test

class InputConfigurationTest {
    private val empty = BasicSkkDictionary { emptyList() }
    private fun engine(punctuation: PunctuationConfig = PunctuationConfig(),
        rules: RomanRuleSet = RomanRuleSet.standard) = BasicSkkEngine(empty, RegistrationPolicy(),
            punctuationConfig = punctuation, romanRuleSet = rules)

    @Test fun `SKKで実行できない大文字規則は開始時に拒否する`() {
        val rules = RomanRuleSet.compile(listOf(RomajiRule("A", "あ")))
        assertThrows(IllegalArgumentException::class.java) { engine(rules = rules) }
    }

    @Test fun `句読点の全選択肢をかなモードと登録本文に適用する`() {
        for (period in listOf("。", "．", ".")) for (comma in listOf("、", "，", ",")) {
            val config = PunctuationConfig(period, comma)
            val engine = engine(config)
            assertEquals(period + comma, engine.dispatch(BasicSkkAction.Text(".,")).commit)
            engine.dispatch(BasicSkkAction.Text("q"))
            assertEquals(period + comma, engine.dispatch(BasicSkkAction.Text(".,")).commit)
            val registering = BasicSkkEngine(empty, RegistrationPolicy(enabled = true), punctuationConfig = config)
            registering.dispatch(BasicSkkAction.Text("Michi "))
            assertEquals(period + comma, registering.dispatch(BasicSkkAction.Text(".,")).view.registration?.body)
        }
    }

    @Test fun `句読点と括弧はかなだけに適用しabbrevと直接入力を保持する`() {
        val punctuation = PunctuationConfig("．", "，", false, true)
        val engine = engine(punctuation)
        assertEquals("．，()［］｛｝", engine.dispatch(BasicSkkAction.Text(".,()[]{}")).commit)
        engine.dispatch(BasicSkkAction.Text("q"))
        assertEquals("．，()", engine.dispatch(BasicSkkAction.Text(".,()")).commit)
        engine.dispatch(BasicSkkAction.Text("/"))
        assertEquals(".,()", engine.dispatch(BasicSkkAction.Text(".,()")).view.composing)
        engine.dispatch(BasicSkkAction.Cancel)
        engine.dispatch(BasicSkkAction.Text("l"))
        assertEquals(".,()", engine.dispatch(BasicSkkAction.Text(".,()")).commit)
        engine.dispatch(BasicSkkAction.Kana)
        engine.dispatch(BasicSkkAction.Halfwidth)
        assertEquals(".,()", engine.dispatch(BasicSkkAction.Text(".,()")).commit)
    }

    @Test fun `カスタム規則は復元と取消を経ても標準へ戻らない`() {
        val rules = RomanRuleSet.compile(Romanizer.standardRules.map {
            if (it.input == "ka") it.copy(output = "か\u309a") else it
        } + RomajiRule(";", "っ"))
        val engine = engine(rules = rules)
        assertEquals("か\u309aっ", engine.dispatch(BasicSkkAction.Text("ka;")).commit)
        engine.dispatch(BasicSkkAction.Text("Ka"))
        assertEquals("か\u309a", engine.currentView.composing)
        engine.dispatch(BasicSkkAction.Text(" "))
        assertEquals("か\u309a", engine.currentView.composing)
        engine.dispatch(BasicSkkAction.Cancel)
        assertEquals("か\u309a", engine.dispatch(BasicSkkAction.Text("ka")).commit)
    }

    @Test fun `補完の終端処理はカスタム終端規則を使い候補なしでは巻き戻す`() {
        val rules = RomanRuleSet.compile(listOf(RomajiRule("z", "", terminalOutput = "ん")))
        val engine = engine(rules = rules)
        engine.dispatch(BasicSkkAction.Text("Z"))
        engine.dispatch(BasicSkkAction.CompleteForward)
        assertEquals("z", engine.state.pendingRomaji)
        assertEquals("z", engine.currentView.composing)
        assertEquals("ん", engine.dispatch(BasicSkkAction.Enter).commit)
    }

    @Test fun `数字ラベルとページ幅は選択開始時に固定し全ページへ到達する`() {
        var width = 2
        val dictionary = BasicSkkDictionary { (1..9).map { DictionaryCandidate("候補$it") } }
        val engine = BasicSkkEngine(dictionary, RegistrationPolicy(),
            candidateDisplayConfig = CandidateDisplayConfig("1234567", fixedPageSize = 2),
            candidatePageSizeProvider = { width })
        engine.dispatch(BasicSkkAction.Text("Ka "))
        repeat(3) { engine.dispatch(BasicSkkAction.Text(" ")) }
        assertEquals(listOf('1','2'), engine.currentView.candidate!!.menu.map { it.label })
        assertEquals(listOf("候補4", "候補5"), engine.currentView.candidate!!.menu.map { it.candidate.text })
        width = 7
        assertEquals(2, engine.currentView.candidate!!.menu.size)
        repeat(2) { engine.dispatch(BasicSkkAction.Text(" ")) }
        assertEquals(listOf("候補6", "候補7"), engine.currentView.candidate!!.menu.map { it.candidate.text })
        assertEquals("候補7", engine.dispatch(BasicSkkAction.Text("2")).commit)
        engine.dispatch(BasicSkkAction.Text("Ka "))
        repeat(3) { engine.dispatch(BasicSkkAction.Text(" ")) }
        assertEquals(6, engine.currentView.candidate!!.menu.size)
    }

    @Test fun `自動表示数は幅と文字倍率に従い不正設定は拒否する`() {
        val config = CandidateDisplayConfig(pageMode = CandidatePageMode.AUTO)
        assertEquals(1, config.pageSize(30f, 1f))
        assertEquals(4, config.pageSize(400f, 1f))
        assertEquals(2, config.pageSize(400f, 2f))
        assertEquals(7, config.pageSize(2000f, 1f))
        assertEquals(1, config.pageSize(Float.NaN, 1f))
        assertThrows(IllegalArgumentException::class.java) { CandidateDisplayConfig("xx", fixedPageSize = 1) }
        assertThrows(IllegalArgumentException::class.java) { CandidateDisplayConfig("12", fixedPageSize = 3) }
        assertThrows(IllegalArgumentException::class.java) { PunctuationConfig(period = "不正") }
    }
}
