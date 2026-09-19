package jp.hayase.skk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicSkkEngineTest {
    private val lookups = mutableListOf<DictionaryQuery>()
    private val entries = mapOf(
        DictionaryQuery("にほん") to listOf(
            DictionaryCandidate("日本", "国名"), DictionaryCandidate("二本"), DictionaryCandidate("ニホン"),
            DictionaryCandidate("日本橋", "地名"), DictionaryCandidate("日本語"), DictionaryCandidate("日本海"),
            DictionaryCandidate("日本刀"), DictionaryCandidate("日本酒"), DictionaryCandidate("日本人"),
            DictionaryCandidate("日本国"), DictionaryCandidate("日本製"), DictionaryCandidate("日本一"),
        ),
        DictionaryQuery("かk", "く") to listOf(DictionaryCandidate("書", "筆記", "く")),
        DictionaryQuery("おおk", "く") to listOf(
            DictionaryCandidate("多", okuriCondition = "く"), DictionaryCandidate("大"),
            DictionaryCandidate("大", okuriCondition = "き"),
        ),
        DictionaryQuery("API", abbrev = true) to listOf(DictionaryCandidate("エーピーアイ", "略語")),
        DictionaryQuery("だい>") to listOf(DictionaryCandidate("第", "接頭辞")),
        DictionaryQuery(">かい") to listOf(DictionaryCandidate("回", "接尾辞")),
        DictionaryQuery("kにほん") to listOf(DictionaryCandidate("仮")),
        DictionaryQuery("にほんk", "k") to listOf(DictionaryCandidate("仮")),
    )
    private fun engine() = BasicSkkEngine { query ->
        lookups += query
        entries[query].orEmpty()
    }

    private fun BasicSkkEngine.type(text: String): String =
        text.mapNotNull { dispatch(BasicSkkAction.Text(it.toString())).commit }.joinToString("")

    @Test fun `K01 基本ローマ字と終端と未消化削除を扱う`() {
        val engine = engine()
        assertEquals("かな", engine.dispatch(BasicSkkAction.Text("kana")).commit)
        assertEquals("きって", engine.dispatch(BasicSkkAction.Text("kitte")).commit)
        assertNull(engine.dispatch(BasicSkkAction.Text("n")).commit)
        assertEquals("ん", engine.dispatch(BasicSkkAction.Kana).commit)
        engine.dispatch(BasicSkkAction.Text("k"))
        assertTrue(engine.dispatch(BasicSkkAction.Backspace).handled)
        assertEquals("", engine.state.pendingRomaji)
    }

    @Test fun `K01 apostropheはnの規則入力として通常入力と見出し語で消費する`() {
        val idle = engine()
        assertEquals("ん", idle.dispatch(BasicSkkAction.Text("n'")).commit)
        assertEquals("", idle.state.pendingRomaji)

        val reading = engine()
        reading.type("Kan'i")
        assertEquals("かんい", reading.state.reading)
        assertEquals("", reading.state.pendingRomaji)
        assertEquals("かんい", reading.currentView.composing)
    }

    @Test fun `K02 五つの入力モードと確定境界を扱う`() {
        val engine = engine()
        engine.dispatch(BasicSkkAction.Text("q"))
        assertEquals(InputMode.KATAKANA, engine.state.mode)
        assertEquals("カナ", engine.dispatch(BasicSkkAction.Text("kana")).commit)
        engine.dispatch(BasicSkkAction.Kana)
        engine.dispatch(BasicSkkAction.Halfwidth)
        assertEquals("ｶﾅ", engine.dispatch(BasicSkkAction.Text("kana")).commit)
        engine.dispatch(BasicSkkAction.Kana)
        engine.dispatch(BasicSkkAction.Text("l"))
        assertEquals("abc", engine.dispatch(BasicSkkAction.Text("abc")).commit)
        engine.dispatch(BasicSkkAction.Kana)
        engine.dispatch(BasicSkkAction.Text("L"))
        assertEquals("ａｂｃ", engine.dispatch(BasicSkkAction.Text("abc")).commit)
    }

    @Test fun `K02 見出し語のqは全体を変換して一度確定する`() {
        val engine = engine()
        engine.dispatch(BasicSkkAction.Text("Kana"))
        val result = engine.dispatch(BasicSkkAction.Text("q"))
        assertEquals("カナ", result.commit)
        assertEquals(InputPhase.IDLE, engine.state.phase)
        assertEquals(InputMode.HIRAGANA, engine.state.mode)
    }

    @Test fun `モード切替とQは切替前の残余を一度だけ終端処理する`() {
        val engine = engine()
        engine.dispatch(BasicSkkAction.Text("q"))
        engine.dispatch(BasicSkkAction.Text("n"))
        assertEquals("ン", engine.dispatch(BasicSkkAction.Enter).commit)
        assertEquals(InputMode.KATAKANA, engine.state.mode)
        assertFalse(engine.dispatch(BasicSkkAction.Enter).handled)

        engine.dispatch(BasicSkkAction.Kana)
        engine.dispatch(BasicSkkAction.Text("k"))
        val reading = engine.dispatch(BasicSkkAction.Text("Q"))
        assertEquals("k", reading.commit)
        assertEquals(InputPhase.READING, engine.state.phase)
        assertEquals("", reading.view.composing)
    }

    @Test fun `通常入力の残余は現在モードでリテラルより先に確定する`() {
        val hiragana = engine()
        hiragana.dispatch(BasicSkkAction.Text("k"))
        assertEquals("k😀", hiragana.dispatch(BasicSkkAction.Text("😀")).commit)
        assertFalse(hiragana.dispatch(BasicSkkAction.Enter).handled)

        val punctuation = engine()
        punctuation.dispatch(BasicSkkAction.Text("n"))
        assertEquals("ん。", punctuation.dispatch(BasicSkkAction.Text(".")).commit)
        assertFalse(punctuation.dispatch(BasicSkkAction.Enter).handled)

        val katakana = engine()
        katakana.dispatch(BasicSkkAction.Text("q"))
        katakana.dispatch(BasicSkkAction.Text("n"))
        assertEquals("ン😀", katakana.dispatch(BasicSkkAction.Text("😀")).commit)

        val halfwidth = engine()
        halfwidth.dispatch(BasicSkkAction.Halfwidth)
        halfwidth.dispatch(BasicSkkAction.Text("n"))
        assertEquals("ﾝ｡", halfwidth.dispatch(BasicSkkAction.Text(".")).commit)
    }

    @Test fun `K03 Qと大文字開始で見出し語を検索してEnterで一度確定する`() {
        for (actions in listOf("Nihon", "Qnihon")) {
            val engine = engine()
            engine.type(actions)
            assertEquals(InputPhase.READING, engine.state.phase)
            val conversion = engine.dispatch(BasicSkkAction.Text(" "))
            assertEquals("日本", conversion.view.candidate?.committedText)
            assertEquals("国名", conversion.view.candidate?.selected?.annotation)
            assertEquals("日本", engine.dispatch(BasicSkkAction.Enter).commit)
            assertFalse(engine.dispatch(BasicSkkAction.Enter).handled)
        }
    }

    @Test fun `K04 送りキーと実際の送りを分けて一度だけ付ける`() {
        val engine = engine()
        engine.type("KaKu")
        assertEquals(DictionaryQuery("かk", "く"), lookups.last())
        assertEquals("か", engine.state.reading)
        assertEquals("く", engine.state.okuri)
        assertEquals("書く", engine.dispatch(BasicSkkAction.Enter).commit)
        assertEquals(InputPhase.IDLE, engine.state.phase)
    }

    @Test fun `送り候補の取消後qは送り開始子音を含めず全体を変換する`() {
        val engine = engine()
        engine.type("KaKu")
        engine.dispatch(BasicSkkAction.Cancel)
        assertEquals("カク", engine.dispatch(BasicSkkAction.Text("q")).commit)
    }

    @Test fun `K04 辞書が決めた送り候補順を使い実際の送りを一度付ける`() {
        val engine = engine()
        engine.type("OoKu")
        assertEquals("多く", engine.dispatch(BasicSkkAction.Text("")).view.candidate?.committedText)
        assertEquals("大く", engine.dispatch(BasicSkkAction.Text(" ")).view.candidate?.committedText)
    }

    @Test fun `K05 候補を往復し先頭の前で読みへ戻る`() {
        val engine = engine()
        engine.type("Nihon ")
        assertEquals("二本", engine.dispatch(BasicSkkAction.Text(" ")).view.candidate?.committedText)
        assertEquals("日本", engine.dispatch(BasicSkkAction.Text("x")).view.candidate?.committedText)
        val reading = engine.dispatch(BasicSkkAction.Text("x"))
        assertEquals(InputPhase.READING, engine.state.phase)
        assertEquals("にほn", reading.view.composing)
        assertEquals("にほ", engine.state.reading)
        assertEquals("n", engine.state.pendingRomaji)
    }

    @Test fun `K06 三件目から七ラベルを表示し注釈なしで選択確定する`() {
        val engine = engine()
        engine.type("Nihon ")
        repeat(2) { engine.dispatch(BasicSkkAction.Text(" ")) }
        val candidate = engine.dispatch(BasicSkkAction.Text("" )).view.candidate!!
        assertEquals("asdfjkl", candidate.menu.joinToString("") { it.label.toString() })
        assertEquals("ニホン", candidate.menu.first().candidate.text)
        assertEquals("地名", candidate.menu[1].candidate.annotation)
        assertEquals("日本橋", engine.dispatch(BasicSkkAction.Text("s")).commit)
    }

    @Test fun `K07 abbrevは大文字小文字を保って検索する`() {
        val engine = engine()
        engine.type("/API")
        assertEquals("API", engine.state.reading)
        engine.dispatch(BasicSkkAction.Text(" "))
        assertEquals(DictionaryQuery("API", abbrev = true), lookups.last())
        assertEquals("エーピーアイ", engine.dispatch(BasicSkkAction.Enter).commit)
    }

    @Test fun `abbrev候補の先頭から戻ると検索前の段階と編集位置を復元する`() {
        val engine = engine()
        engine.type("/API")
        engine.dispatch(BasicSkkAction.Home)
        engine.dispatch(BasicSkkAction.Right)
        val beforeLookup = engine.state

        engine.dispatch(BasicSkkAction.Text(" "))
        assertEquals(InputPhase.SELECTING, engine.state.phase)
        engine.dispatch(BasicSkkAction.Text("x"))

        assertEquals(beforeLookup, engine.state)
        assertEquals(InputPhase.ABBREV, engine.state.phase)
        assertEquals("API", engine.currentView.composing)
        assertEquals(1, engine.currentView.cursor)
    }

    @Test fun `K08 接頭辞と候補中の接尾辞開始を区別する`() {
        val engine = engine()
        engine.type("Dai>")
        assertEquals(DictionaryQuery("だい>"), lookups.last())
        val suffix = engine.dispatch(BasicSkkAction.Text(">"))
        assertEquals("第", suffix.commit)
        assertEquals(">", suffix.view.composing)
        engine.type("kai")
        engine.dispatch(BasicSkkAction.Text(" "))
        assertEquals(DictionaryQuery(">かい"), lookups.last())
        assertEquals("回", engine.dispatch(BasicSkkAction.Enter).commit)
    }

    @Test fun `候補なしでは読みを保持し登録未対応を通知する`() {
        val engine = engine()
        engine.type("Michi")
        val result = engine.dispatch(BasicSkkAction.Text(" "))
        assertEquals(InputPhase.READING, engine.state.phase)
        assertEquals("みち", result.view.composing)
        assertEquals("単語登録はまだ利用できません", result.notice)
    }

    @Test fun `末尾候補の次は読みを保持し登録未対応を通知する`() {
        val engine = engine()
        engine.type("Nihon ")
        repeat(3) { engine.dispatch(BasicSkkAction.Text(" ")) }
        val result = engine.dispatch(BasicSkkAction.Text(" "))
        assertEquals(InputPhase.READING, engine.state.phase)
        assertEquals("にほn", result.view.composing)
        assertEquals("単語登録はまだ利用できません", result.notice)
    }

    @Test fun `内部カーソル編集後の読みだけで検索する`() {
        val engine = engine()
        engine.type("Nihon")
        engine.dispatch(BasicSkkAction.Left)
        engine.dispatch(BasicSkkAction.Left)
        engine.type("a")
        engine.dispatch(BasicSkkAction.Delete)
        engine.dispatch(BasicSkkAction.Text(" "))
        assertEquals(DictionaryQuery("にあん"), lookups.last())
    }

    @Test fun `途中カーソルの残余を表示位置に置き取消で検索前へ完全復元する`() {
        val engine = engine()
        engine.type("Nihon")
        engine.dispatch(BasicSkkAction.Home)
        engine.dispatch(BasicSkkAction.Text("k"))
        val beforeLookup = engine.state
        assertEquals("kにほん", engine.currentView.composing)
        assertEquals(1, engine.currentView.cursor)

        engine.dispatch(BasicSkkAction.Text(" "))
        assertEquals(DictionaryQuery("kにほん"), lookups.last())
        assertEquals(InputPhase.SELECTING, engine.state.phase)
        engine.dispatch(BasicSkkAction.Cancel)

        assertEquals(beforeLookup, engine.state)
        assertEquals("kにほん", engine.currentView.composing)
        assertEquals(1, engine.currentView.cursor)
    }

    @Test fun `送り残余は先頭候補の前へ戻ると検索前の境界ごと復元する`() {
        val engine = engine()
        engine.type("NihonK")
        val beforeLookup = engine.state
        assertEquals("にほんk", engine.currentView.composing)
        assertEquals(4, engine.currentView.cursor)

        engine.dispatch(BasicSkkAction.Text(" "))
        assertEquals(DictionaryQuery("にほんk", "k"), lookups.last())
        assertEquals(InputPhase.SELECTING, engine.state.phase)
        engine.dispatch(BasicSkkAction.Text("x"))

        assertEquals(beforeLookup, engine.state)
        assertEquals("にほんk", engine.currentView.composing)
        assertEquals(4, engine.currentView.cursor)
    }

    @Test fun `読み先頭の大文字は空の語幹で送りを開始しない`() {
        val engine = engine()
        engine.type("Nihon")
        engine.dispatch(BasicSkkAction.Home)
        engine.dispatch(BasicSkkAction.Text("K"))

        assertEquals("にほん", engine.state.reading)
        assertNull(engine.state.okuriConsonant)
        assertEquals("", engine.state.okuri)
        assertEquals("k", engine.state.pendingRomaji)
        assertEquals(0, engine.state.cursor)
        assertEquals("kにほん", engine.currentView.composing)
        assertEquals(1, engine.currentView.cursor)
    }

    @Test fun `読みの先頭でBSを押しても全体を消さない`() {
        val engine = engine()
        engine.type("Nihon ")
        engine.dispatch(BasicSkkAction.Cancel)
        engine.dispatch(BasicSkkAction.Home)
        assertTrue(engine.dispatch(BasicSkkAction.Backspace).handled)
        assertEquals(InputPhase.READING, engine.state.phase)
        assertEquals("にほん", engine.state.reading)
        engine.dispatch(BasicSkkAction.Right)
        engine.dispatch(BasicSkkAction.Delete)
        assertEquals("にん", engine.state.reading)
    }

    @Test fun `abbrevの先頭でBSを押しても全体を消さない`() {
        val engine = engine()
        engine.type("/API")
        engine.dispatch(BasicSkkAction.Home)
        engine.dispatch(BasicSkkAction.Backspace)
        assertEquals(InputPhase.ABBREV, engine.state.phase)
        assertEquals("API", engine.state.reading)
    }

    @Test fun `補助文字とZWJ列を分割せず入力し内部削除する`() {
        val engine = engine()
        assertEquals("😀", engine.dispatch(BasicSkkAction.Text("😀")).commit)
        engine.dispatch(BasicSkkAction.Text("Q"))
        val emoji = "👩🏽‍💻"
        assertEquals(emoji, engine.dispatch(BasicSkkAction.Text(emoji)).view.composing)
        assertTrue(engine.dispatch(BasicSkkAction.Backspace).handled)
        assertEquals("", engine.dispatch(BasicSkkAction.Text("")).view.composing)
    }

    @Test fun `不正なUTF16は状態を部分更新せず拒否する`() {
        val engine = engine()
        engine.dispatch(BasicSkkAction.Text("Q"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            engine.dispatch(BasicSkkAction.Text("a\uD800"))
        }
        assertEquals("", engine.state.reading)
        assertEquals("", engine.state.pendingRomaji)
    }

    @Test fun `取消は候補から読みを復元し空状態のCgはかなだけ消費する`() {
        val engine = engine()
        engine.type("Nihon ")
        assertTrue(engine.dispatch(BasicSkkAction.Cancel).handled)
        assertEquals("にほ", engine.state.reading)
        assertEquals("n", engine.state.pendingRomaji)
        engine.dispatch(BasicSkkAction.Cancel)
        assertTrue(engine.dispatch(BasicSkkAction.Cancel).handled)
        engine.dispatch(BasicSkkAction.Text("l"))
        assertFalse(engine.dispatch(BasicSkkAction.Cancel).handled)
    }
}
