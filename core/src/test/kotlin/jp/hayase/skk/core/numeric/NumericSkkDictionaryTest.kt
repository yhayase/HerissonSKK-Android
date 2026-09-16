package jp.hayase.skk.core.numeric

import jp.hayase.skk.core.BasicSkkResult
import jp.hayase.skk.core.BasicSkkDictionary
import jp.hayase.skk.core.DictionaryCandidate
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.BasicSkkAction
import jp.hayase.skk.core.BasicSkkEngine
import jp.hayase.skk.core.BasicSkkEffect
import jp.hayase.skk.core.RegistrationPolicy
import jp.hayase.skk.core.RegistrationSaveCompletion
import jp.hayase.skk.core.RegistrationSaveOutcome
import jp.hayase.skk.core.RegistrationSaveRequest
import jp.hayase.skk.core.dictionary.CandidateSelection
import jp.hayase.skk.core.dictionary.SelectedCandidateOrigin
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NumericSkkDictionaryTest {
    @Test fun `八形式を正規化キーから展開し元テンプレートを学習対象にする`() {
        val dictionary = facade(mapOf("だい#" to listOf(DictionaryCandidate("第#0"), DictionaryCandidate("第#1"), DictionaryCandidate("第#2"), DictionaryCandidate("第#3"), DictionaryCandidate("第#5"), DictionaryCandidate("第#8"), DictionaryCandidate("第#9"))))
        val result = dictionary.lookup(DictionaryQuery("だい12"))
        assertEquals(listOf("第12", "第１２", "第一二", "第十二", "第壱拾弐", "第１二"), result.map { it.text })
        assertEquals("だい#", result.first().learningTarget?.query?.readingKey)
        assertEquals("第#0", result.first().learningTarget?.templateText)
    }

    @Test fun `四番は同じraw辞書を一段だけ検索し重複は先勝ちにする`() {
        val dictionary = facade(mapOf("こーど#" to listOf(DictionaryCandidate("地域:#4", "外")), "314" to listOf(DictionaryCandidate("北区"), DictionaryCandidate("北区"), DictionaryCandidate("中央区"))))
        assertEquals(listOf("地域:北区", "地域:中央区"), dictionary.lookup(DictionaryQuery("こーど314")).map { it.text })
    }

    @Test fun `通常本文と未使用記号を候補として保ち全展開失敗だけ型付き例外にする`() {
        assertEquals(
            listOf("通常", "#x", "末尾#"),
            facade(mapOf("第#" to listOf(
                DictionaryCandidate("通常"), DictionaryCandidate("#x"), DictionaryCandidate("末尾#"),
            ))).lookup(DictionaryQuery("第1")).map { it.text },
        )
        assertThrows(NumericLookupException::class.java) {
            facade(mapOf("第#" to listOf(DictionaryCandidate("#9")))).lookup(DictionaryQuery("第1"))
        }
    }

    @Test fun `展開全失敗はエンジンの読みと保留文字を保持し登録を開始しない`() {
        val engine = BasicSkkEngine(facade(mapOf("だい#" to listOf(DictionaryCandidate("#9")))))
        engine.dispatch(BasicSkkAction.Text("Dai1"))
        val result = engine.dispatch(BasicSkkAction.Text(" "))
        assertEquals("だい1", result.view.composing)
        assertEquals(0, engine.state.registrationDepth)
        assertEquals("数値を展開できません。読みを保持しました。入力を確認してください", result.notice)
    }

    @Test fun `候補数と総出力は全テンプレート合計で境界を判定する`() {
        fun candidates(count: Int) = List(count) { DictionaryCandidate("$it#0") }
        assertEquals(256, facade(mapOf("#" to candidates(256))).lookup(DictionaryQuery("1")).size)
        assertThrows(NumericLookupException::class.java) {
            facade(mapOf("#" to candidates(257))).lookup(DictionaryQuery("1"))
        }
        val exact = listOf(
            DictionaryCandidate("a".repeat(32_767) + "#0"),
            DictionaryCandidate("b".repeat(32_767) + "#0"),
        )
        assertEquals(65_536, facade(mapOf("#" to exact)).lookup(DictionaryQuery("1")).sumOf { it.text.length })
        val over = listOf(
            DictionaryCandidate("a".repeat(32_768) + "#0"),
            DictionaryCandidate("b".repeat(32_768) + "#0"),
        )
        assertThrows(NumericLookupException::class.java) { facade(mapOf("#" to over)).lookup(DictionaryQuery("1")) }
    }

    @Test fun `外側の送りとabbrevを保ち四番再検索だけを通常検索に戻す`() {
        val queries = mutableListOf<DictionaryQuery>()
        val dictionary = NumericSkkDictionary(BasicSkkDictionary { query ->
            queries += query
            when (query.readingKey) {
                "v#" -> listOf(DictionaryCandidate("値#4"))
                "12" -> listOf(DictionaryCandidate("十二"))
                else -> emptyList()
            }
        })
        dictionary.lookup(DictionaryQuery("v12", okuri = "る", abbrev = true))
        assertEquals(DictionaryQuery("v#", "る", true), queries[0])
        assertEquals(DictionaryQuery("12"), queries[1])
    }

    @Test fun `数値キーにテンプレートがない時だけ通常の登録へ進む`() {
        val engine = BasicSkkEngine(
            facade(emptyMap()),
            RegistrationPolicy(enabled = true),
        )
        engine.dispatch(BasicSkkAction.Text("Dai1"))
        val result = engine.dispatch(BasicSkkAction.Text(" "))
        assertEquals(1, engine.state.registrationDepth)
        assertEquals("だい#", result.view.registration?.readingKey)
    }

    @Test fun `数値候補を使い切った登録も正規化見出しを使う`() {
        val engine = BasicSkkEngine(
            facade(mapOf("だい#" to listOf(DictionaryCandidate("第#3")))),
            RegistrationPolicy(enabled = true),
        )
        engine.dispatch(BasicSkkAction.Text("Dai12 "))
        val exhausted = engine.dispatch(BasicSkkAction.Text(" "))
        assertEquals(1, engine.state.registrationDepth)
        assertEquals("だい#", exhausted.view.registration?.readingKey)
    }

    @Test fun `登録は生テンプレートを保存し最初の展開結果を保存完了まで固定する`() {
        val values = mutableMapOf<String, List<DictionaryCandidate>>()
        val dictionary = mutableFacade(values)
        val engine = BasicSkkEngine(dictionary, RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.Text("Dai12 "))
        engine.dispatch(BasicSkkAction.Text("第#3"))
        val request = engine.dispatch(BasicSkkAction.Enter).saveRequest()
        assertEquals("だい#", request.readingKey)
        assertEquals("第#3", request.candidateText)
        assertEquals("第十二", request.committedText)

        values["だい#"] = listOf(DictionaryCandidate(request.candidateText))
        assertEquals("第十二", engine.completeRegistration(
            RegistrationSaveCompletion(request.token, RegistrationSaveOutcome.Applied),
        ).commit)
        assertEquals("第十二", dictionary.lookup(DictionaryQuery("だい12")).single().text)
    }

    @Test fun `不正な登録テンプレートは本文を保ち保存効果を出さない`() {
        val engine = BasicSkkEngine(facade(emptyMap()), RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.Text("Dai1 "))
        engine.dispatch(BasicSkkAction.Text("#9"))
        val rejected = engine.dispatch(BasicSkkAction.Enter)
        assertTrue(rejected.effects.isEmpty())
        assertEquals("#9", rejected.view.registration?.body)
        assertTrue(rejected.notice!!.contains("数値"))
    }

    @Test fun `四番登録は一段だけ展開し辞書更新後の完了でも再計算しない`() {
        val values = mutableMapOf("314" to listOf(DictionaryCandidate("北#3区")))
        val engine = BasicSkkEngine(mutableFacade(values), RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.Text("Dai314 "))
        engine.dispatch(BasicSkkAction.Text("地域:#4"))
        val request = engine.dispatch(BasicSkkAction.Enter).saveRequest()
        assertEquals("地域:#4", request.candidateText)
        assertEquals("地域:北#3区", request.committedText)
        values["314"] = listOf(DictionaryCandidate("中央区"))
        assertEquals("地域:北#3区", engine.completeRegistration(
            RegistrationSaveCompletion(request.token, RegistrationSaveOutcome.Applied),
        ).commit)
    }

    @Test fun `外側テンプレート処理数は1024件を許可し超過は展開前に拒否する`() {
        var innerLookups = 0
        fun dictionary(count: Int) = NumericSkkDictionary(BasicSkkDictionary { query ->
            when (query.readingKey) {
                "値#" -> List(count) { DictionaryCandidate("結果#4") }
                "1" -> { innerLookups++; listOf(DictionaryCandidate("同一")) }
                else -> emptyList()
            }
        })
        assertEquals(listOf("結果同一"), dictionary(1_024).lookup(DictionaryQuery("値1")).map { it.text })
        assertEquals(1_024, innerLookups)
        innerLookups = 0
        assertThrows(NumericLookupException::class.java) {
            dictionary(1_025).lookup(DictionaryQuery("値1"))
        }
        assertEquals(0, innerLookups)
    }

    @Test fun `子登録の展開後本文が親上限を超える時は保存せず子を保持する`() {
        val engine = BasicSkkEngine(facade(emptyMap()), RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.Text("Michi "))
        engine.dispatch(BasicSkkAction.Text("あ".repeat(65_534)))
        engine.dispatch(BasicSkkAction.Text("Dai12 "))
        engine.dispatch(BasicSkkAction.Text("第#3"))
        val rejected = engine.dispatch(BasicSkkAction.Enter)
        assertTrue(rejected.effects.isEmpty())
        assertEquals(2, engine.state.registrationDepth)
        assertEquals("第#3", rejected.view.registration?.body)
        assertTrue(rejected.notice!!.contains("65,536"))
    }

    @Test fun `同じ表示へ収束した全外側テンプレート由来をまとめ先頭の表示と学習対象を保つ`() {
        val dictionary = facade(mapOf("値#" to listOf(
            DictionaryCandidate("#0", "先頭注釈", selection = selection("a", 9, "値#", "#0")),
            DictionaryCandidate("#00", "後続注釈", selection = selection("b", 9, "値#", "#00")),
        )))

        val candidate = dictionary.lookup(DictionaryQuery("値12")).single()

        assertEquals("12", candidate.text)
        assertEquals("先頭注釈", candidate.annotation)
        assertEquals("#0", candidate.learningTarget?.templateText)
        assertEquals(true, candidate.selection?.numericTemplate)
        assertEquals(listOf("#0", "#00"), candidate.selection?.origins?.map { it.text })
        assertEquals(listOf("値#", "値#"), candidate.selection?.origins?.map { it.entryKey })
    }

    @Test fun `四番は内側候補由来を削除対象へ混ぜず外側テンプレートだけを保持する`() {
        val outer = selection("outer", 5, "こーど#", "地域:#4")
        val inner = selection("inner", 5, "314", "北区")
        val dictionary = NumericSkkDictionary(BasicSkkDictionary { query ->
            when (query.readingKey) {
                "こーど#" -> listOf(DictionaryCandidate("地域:#4", selection = outer))
                "314" -> listOf(DictionaryCandidate("北区", selection = inner))
                else -> emptyList()
            }
        })

        val candidate = dictionary.lookup(DictionaryQuery("こーど314")).single()

        assertEquals("地域:北区", candidate.text)
        assertEquals(listOf("outer"), candidate.selection?.origins?.map { it.dictionaryId })
        assertEquals("地域:#4", candidate.selection?.origins?.single()?.text)
        assertEquals(true, candidate.selection?.numericTemplate)
    }

    @Test fun `収束候補の個人世代不一致と由来総数上限超過を安全に拒否する`() {
        val mismatched = facade(mapOf("値#" to listOf(
            DictionaryCandidate("#0", selection = selection("a", 1, "値#", "#0")),
            DictionaryCandidate("#00", selection = selection("b", 2, "値#", "#00")),
        )))
        assertThrows(NumericLookupException::class.java) {
            mismatched.lookup(DictionaryQuery("値1"))
        }

        fun withOrigins(count: Int) = NumericSkkDictionary(BasicSkkDictionary { query ->
            if (query.readingKey == "値#") {
                val origins = List(count) { index ->
                    SelectedCandidateOrigin("d$index", 1, false, "値#", "#0", null)
                }
                listOf(DictionaryCandidate("#0", selection = CandidateSelection(1, origins)))
            } else emptyList()
        })
        assertEquals("1", withOrigins(4_096).lookup(DictionaryQuery("値1")).single().text)
        assertThrows(NumericLookupException::class.java) {
            withOrigins(4_097).lookup(DictionaryQuery("値1"))
        }
    }

    @Test fun `数値表示を確定しても学習効果は元キーとテンプレートを使う`() {
        val engine = BasicSkkEngine(
            facade(mapOf("だい#" to listOf(DictionaryCandidate("第#3", "注釈")))),
            RegistrationPolicy(enabled = true, sessionGeneration = 7),
            learningEnabled = true,
        )
        engine.dispatch(BasicSkkAction.Text("Dai12 "))
        val result = engine.dispatch(BasicSkkAction.Enter)
        val effect = result.effects.single() as BasicSkkEffect.LearnCandidate
        assertEquals("第十二", result.commit)
        assertEquals("だい#", effect.request.query.readingKey)
        assertEquals("第#3", effect.request.candidate.text)
        assertEquals("注釈", effect.request.candidate.annotation)
    }

    private fun BasicSkkResult.saveRequest(): RegistrationSaveRequest =
        (effects.single() as BasicSkkEffect.SaveRegistration).request

    private fun facade(values: Map<String, List<DictionaryCandidate>>) =
        mutableFacade(values.toMutableMap())

    private fun mutableFacade(values: MutableMap<String, List<DictionaryCandidate>>) =
        NumericSkkDictionary(BasicSkkDictionary { values[it.readingKey].orEmpty() })

    private fun selection(
        dictionaryId: String,
        personalGeneration: Long,
        entryKey: String,
        text: String,
    ) = CandidateSelection(
        personalGeneration,
        listOf(SelectedCandidateOrigin(dictionaryId, 1, false, entryKey, text, null)),
    )
}
