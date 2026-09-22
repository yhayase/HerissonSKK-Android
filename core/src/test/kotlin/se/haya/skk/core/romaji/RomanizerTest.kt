package se.haya.skk.core.romaji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomanizerTest {
    @Test
    fun `K01 kana と kitte を変換する`() {
        val romanizer = Romanizer()

        assertEquals("かな", romanizer.feed("kana"))
        assertEquals("", romanizer.pending)
        assertEquals("きって", romanizer.feed("kitte"))
        assertEquals("", romanizer.pending)
    }

    @Test
    fun `K01 n の終端と未消化文字の削除を扱う`() {
        val romanizer = Romanizer()

        assertEquals("", romanizer.feed("n"))
        assertEquals("n", romanizer.pending)
        assertEquals("ん", romanizer.finish())
        assertEquals("", romanizer.feed("k"))
        assertTrue(romanizer.backspacePending())
        assertEquals("", romanizer.pending)
        assertFalse(romanizer.backspacePending())
    }

    @Test
    fun `終端は n だけをんにし未知の k と ky を保存する`() {
        val romanizer = Romanizer()

        romanizer.feed("k")
        assertEquals("k", romanizer.finish())
        romanizer.feed("ky")
        assertEquals("ky", romanizer.finish())
    }

    @Test
    fun `撥音は子音の前で確定し次のかなを処理する`() {
        val romanizer = Romanizer()

        assertEquals("んば", romanizer.feed("nba"))
        assertEquals("", romanizer.pending)
    }

    @Test
    fun `規則にない接続は古い接頭辞を捨てて現在の文字から再開する`() {
        val romanizer = Romanizer()

        assertEquals("", romanizer.feed("zc"))
        assertEquals("c", romanizer.pending)

        romanizer.reset()
        assertEquals("", romanizer.feed("k"))
        assertEquals("", romanizer.feed("y"))
        assertEquals("ky", romanizer.pending)
        assertEquals("", romanizer.feed("t"))
        assertEquals("t", romanizer.pending)
        assertEquals("た", romanizer.feed("a"))
        assertEquals("", romanizer.pending)

        assertEquals("", romanizer.feed("s"))
        assertEquals("", romanizer.feed("h"))
        assertEquals("sh", romanizer.pending)
        assertEquals("", romanizer.feed("k"))
        assertEquals("k", romanizer.pending)
        assertEquals("か", romanizer.feed("a"))
        assertEquals("", romanizer.pending)
    }

    @Test
    fun `基本行と濁音半濁音と拗音小書きを変換する`() {
        val romanizer = Romanizer()

        assertEquals("あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわを", romanizer.feed("aiueokakikukekosashisusesotachitsutetonaninuneno hahifuhehomamimumemoyayuyorarirurerowawo".replace(" ", "")))
        assertEquals("がぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽ", romanizer.feed("gagigugegozajizuzezodadidudedo babibubebopapipupepo".replace(" ", "")))
        assertEquals("じゃじじゅじぇじょ", romanizer.feed("jajijujejo"))
        assertEquals("きゃしゅちょにゃひゅみょりゃぎゅじゃびょぴゃぢゅ", romanizer.feed("kyashuchonyahyumyoryagyujabyopyadyu"))
        assertEquals("ぁぃぅぇぉゃゅょっ", romanizer.feed("xaxixuxexoxyaxyuxyoxtsu"))
    }

    @Test
    fun `DDSKK 標準規則の xtu と xtsu を小さいつへ変換する`() {
        val romanizer = Romanizer()

        assertEquals("っっ", romanizer.feed("xtuxtsu"))
        assertEquals("ッ", KanaTransforms.hiraganaToKatakana(Romanizer().feed("xtu")))
    }

    @Test
    fun `設定した規則を注入できる`() {
        val romanizer = Romanizer(listOf(RomajiRule("ka", "か゚")))
        val overridden = Romanizer(listOf(RomajiRule("xtu", "小")))

        assertEquals("か゚", romanizer.feed("ka"))
        assertEquals("小", overridden.feed("xtu"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `同じ入力の規則は拒否する`() {
        Romanizer(listOf(RomajiRule("ka", "か"), RomajiRule("ka", "カ")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `残余の循環を拒否する`() {
        Romanizer(listOf(RomajiRule("a", "あ", "b"), RomajiRule("b", "び", "a")))
    }

    @Test
    fun `接頭辞が重なる規則と促音の残余を許可する`() {
        val romanizer = Romanizer(
            listOf(
                RomajiRule("k", "く"),
                RomajiRule("ka", "か"),
                RomajiRule("tt", "っ", "t"),
                RomajiRule("te", "て"),
            ),
        )

        assertEquals("か", romanizer.feed("ka"))
        assertEquals("って", romanizer.feed("tte"))
    }

    @Test
    fun `接頭辞が重なる規則は次の文字で一致しなければ短い規則を出力する`() {
        val romanizer = Romanizer(listOf(RomajiRule("a", "A"), RomajiRule("ab", "B")))

        assertEquals("Ac", romanizer.feed("ac"))
        assertEquals("B", romanizer.feed("ab"))
    }

    @Test
    fun `接頭辞が重なる規則は終端で短い完全一致を出力する`() {
        val romanizer = Romanizer(listOf(RomajiRule("a", "A"), RomajiRule("ab", "B")))

        assertEquals("", romanizer.feed("a"))
        assertEquals("A", romanizer.finish())
    }

    @Test
    fun `終端で選んだ完全一致の残余も処理する`() {
        val romanizer = Romanizer(
            listOf(RomajiRule("a", "A", "c"), RomajiRule("ab", "B"), RomajiRule("c", "C")),
        )

        assertEquals("", romanizer.feed("a"))
        assertEquals("AC", romanizer.finish())
    }

    @Test
    fun `DDSKK に合わせて wi we ye を拡張かなへ変換する`() {
        val romanizer = Romanizer()

        assertEquals("うぃうぇいぇ", romanizer.feed("wiweye"))
    }

    @Test
    fun `終端専用規則は長い規則がなくても finish まで保留する`() {
        val romanizer = Romanizer(listOf(RomajiRule("n", "", terminalOutput = "ん")))

        assertEquals("", romanizer.feed("n"))
        assertEquals("n", romanizer.pending)
        assertEquals("ん", romanizer.finish())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `終端出力と通常出力を併用する規則は拒否する`() {
        Romanizer(listOf(RomajiRule("n", "n", terminalOutput = "ん")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `終端出力と残余を併用する規則は拒否する`() {
        Romanizer(listOf(RomajiRule("n", "", "n", terminalOutput = "ん")))
    }

    @Test
    fun `残余の処理は一文字入力ごとに有限回で完了する`() {
        val romanizer = Romanizer(
            listOf(
                RomajiRule("a", "あ", "b"),
                RomajiRule("b", "び", "c"),
                RomajiRule("c", "し"),
            ),
        )

        assertEquals("あびし", romanizer.feed("a"))
    }
}
