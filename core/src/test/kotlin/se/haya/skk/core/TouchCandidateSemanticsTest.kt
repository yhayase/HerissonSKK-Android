package se.haya.skk.core

import se.haya.skk.core.dictionary.DeferredDictionaryReadException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchCandidateSemanticsTest {
    private fun engine(
        count: Int = 7,
        registration: Boolean = false,
        capacity: (List<DictionaryCandidate>, Int) -> Int = { _, _ -> 2 },
    ) = BasicSkkEngine(
        dictionary = BasicSkkDictionary { (1..count).map { DictionaryCandidate("候補$it") } },
        registrationPolicy = RegistrationPolicy(enabled = registration),
        candidatePageCapacityProvider = capacity,
    )

    private fun BasicSkkEngine.startConversion() {
        dispatch(BasicSkkAction.StartReading)
        dispatch(BasicSkkAction.Text("ka", interpretCommands = false))
        dispatch(BasicSkkAction.ConvertNext)
    }

    @Test fun `画面候補ページは可変容量で往復し三角操作から登録へ進まない`() {
        val engine = engine(registration = true) { _, start -> if (start == 0) 2 else 3 }
        engine.startConversion()

        assertEquals(0, engine.currentView.candidate!!.pageStart)
        assertEquals(listOf("候補1", "候補2"), engine.currentView.candidate!!.pageItems.map { it.candidate.text })
        assertFalse(engine.currentView.candidate!!.canPreviousPage)
        assertTrue(engine.currentView.candidate!!.canNextPage)

        engine.dispatch(BasicSkkAction.NextCandidatePage)
        assertEquals(2, engine.currentView.candidate!!.pageStart)
        assertEquals(listOf("候補3", "候補4", "候補5"),
            engine.currentView.candidate!!.pageItems.map { it.candidate.text })
        engine.dispatch(BasicSkkAction.NextCandidatePage)
        assertEquals(5, engine.currentView.candidate!!.pageStart)
        assertFalse(engine.currentView.candidate!!.canNextPage)
        engine.dispatch(BasicSkkAction.NextCandidatePage)
        assertEquals(0, engine.state.registrationDepth)
        assertEquals(5, engine.currentView.candidate!!.pageStart)

        engine.dispatch(BasicSkkAction.PreviousCandidatePage)
        assertEquals(2, engine.currentView.candidate!!.pageStart)
        engine.dispatch(BasicSkkAction.PreviousCandidatePage)
        engine.dispatch(BasicSkkAction.PreviousCandidatePage)
        assertEquals(0, engine.currentView.candidate!!.pageStart)
        assertEquals(InputPhase.SELECTING, engine.state.phase)
    }

    @Test fun `ページ先頭確定は物理選択位置でなく画面ページを使う`() {
        val engine = engine()
        engine.startConversion()
        engine.dispatch(BasicSkkAction.NextCandidatePage)
        assertEquals("候補3", engine.dispatch(BasicSkkAction.CommitPageHead).commit)
    }

    @Test fun `物理候補ページは測定容量の境界と同じラベルで選択する`() {
        val engine = BasicSkkEngine(
            dictionary = BasicSkkDictionary { (1..9).map { DictionaryCandidate("候補$it") } },
            registrationPolicy = RegistrationPolicy(),
            candidatePageCapacityProvider = { _, start -> if (start == 2) 2 else 3 },
        )
        engine.startConversion()
        engine.dispatch(BasicSkkAction.ConvertNext)
        engine.dispatch(BasicSkkAction.ConvertNext)
        assertEquals(listOf("候補3", "候補4"),
            engine.currentView.candidate!!.menu.map { it.candidate.text })

        engine.dispatch(BasicSkkAction.ConvertNext)
        assertEquals(listOf("候補5", "候補6", "候補7"),
            engine.currentView.candidate!!.menu.map { it.candidate.text })
        assertEquals("候補6", engine.dispatch(BasicSkkAction.Text("s")).commit)
    }

    @Test fun `未消化ローマ字の予測は到達する読みと入力中の字面をまとめて検索する`() {
        val queries = mutableListOf<PredictionQuery>()
        val dictionary = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun predict(query: PredictionQuery): PredictionSearchResult {
                queries += query
                return PredictionSearchResult.ConfirmedEmpty
            }
        }
        val engine = BasicSkkEngine(dictionary)
        engine.dispatch(BasicSkkAction.SetTouchPrediction(true))
        engine.dispatch(BasicSkkAction.StartReading)
        engine.dispatch(BasicSkkAction.Text("ten", interpretCommands = false))

        val prefixes = queries.last().prefixes.toSet()
        assertEquals(setOf("てな", "てに", "てぬ", "てね", "ての", "てん", "てn"), prefixes)
        assertEquals(2, prefixes.count { it == "てん" || it == "てn" })
        assertEquals("てn", engine.currentView.prediction?.fallbackText)

        engine.dispatch(BasicSkkAction.Backspace)
        engine.dispatch(BasicSkkAction.Text("k", interpretCommands = false))
        val kPrefixes = queries.last().prefixes.toSet()
        assertTrue(listOf("てか", "てき", "てく", "てけ", "てこ", "てk").all(kPrefixes::contains))
    }

    @Test fun `未消化ローマ字の規則展開上限は部分候補や空候補へ読み替えない`() {
        var searches = 0
        val dictionary = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun predict(query: PredictionQuery): PredictionSearchResult {
                searches++
                return PredictionSearchResult.ConfirmedEmpty
            }
        }
        val rules = se.haya.skk.core.romaji.RomanRuleSet.compile((0..64).map { index ->
            se.haya.skk.core.romaji.RomajiRule("a${index.toString(36).padStart(2, '0')}", "候$index")
        })
        val engine = BasicSkkEngine(dictionary, RegistrationPolicy(), romanRuleSet = rules)
        engine.dispatch(BasicSkkAction.SetTouchPrediction(true))
        engine.dispatch(BasicSkkAction.StartReading)
        engine.dispatch(BasicSkkAction.Text("a", interpretCommands = false))

        assertEquals(0, searches)
        assertEquals(PredictionSearchFailure.WORK_LIMIT, engine.currentView.prediction?.failure)
        assertNull(engine.currentView.prediction?.fallbackText)
    }

    @Test fun `登録見出しの取消は内側候補があっても現在階層を破棄する`() {
        val engine = engine(count = 0, registration = true)
        engine.dispatch(BasicSkkAction.StartReading)
        engine.dispatch(BasicSkkAction.Text("michi", interpretCommands = false))
        engine.dispatch(BasicSkkAction.ConvertNext)
        assertEquals(1, engine.state.registrationDepth)
        engine.dispatch(BasicSkkAction.Text("Ka", interpretCommands = false))
        assertEquals(InputPhase.READING, engine.state.phase)

        val canceled = engine.dispatch(BasicSkkAction.CancelRegistrationFrame)
        assertEquals(0, engine.state.registrationDepth)
        assertNull(canceled.view.registration)
    }

    @Test fun `絶対モード選択は読みまたは画面ページ先頭を確定してから切り替える`() {
        val reading = engine()
        reading.dispatch(BasicSkkAction.StartReading)
        reading.dispatch(BasicSkkAction.Text("ka", interpretCommands = false))
        assertNull(reading.dispatch(BasicSkkAction.SetInputMode(InputMode.DIRECT)).commit)
        assertEquals(InputMode.HIRAGANA, reading.state.mode)
        assertEquals("か", reading.dispatch(BasicSkkAction.Enter).commit)
        reading.dispatch(BasicSkkAction.SetInputMode(InputMode.DIRECT))
        assertEquals(InputMode.DIRECT, reading.state.mode)

        val selecting = engine()
        selecting.startConversion()
        selecting.dispatch(BasicSkkAction.NextCandidatePage)
        val changed = selecting.dispatch(BasicSkkAction.CommitPageHead)
        assertEquals("候補3", changed.commit)
        selecting.dispatch(BasicSkkAction.SetInputMode(InputMode.FULLWIDTH))
        assertEquals(InputMode.FULLWIDTH, selecting.state.mode)
    }

    @Test fun `予測は読みを候補へ置換せず描画時の由来を確定効果へ渡す`() {
        val target = PredictionHistoryTarget("か", "蚊", null, "蚊")
        val predicted = PredictionCandidate(DictionaryCandidate("蚊"), "蚊", target)
        val dictionary = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun predict(query: PredictionQuery): PredictionSearchResult {
                assertEquals("か", query.prefix)
                return PredictionSearchResult.Ready(listOf(predicted), false)
            }
        }
        val engine = BasicSkkEngine(dictionary,
            RegistrationPolicy(enabled = true, sessionGeneration = 9), learningEnabled = true)
        engine.dispatch(BasicSkkAction.SetTouchPrediction(true))
        engine.dispatch(BasicSkkAction.StartReading)
        val reading = engine.dispatch(BasicSkkAction.Text("ka", interpretCommands = false))

        assertEquals("か", reading.view.composing)
        assertEquals(listOf("蚊"), reading.view.prediction!!.items.map { it.committedText })
        val committed = engine.dispatch(BasicSkkAction.CommitPrediction())
        assertEquals("蚊", committed.commit)
        val request = (committed.effects.single() as BasicSkkEffect.LearnCandidate).request
        assertEquals("蚊", request.committedText)
        assertEquals(target, request.historyTarget)
    }

    @Test fun `予測の確定済み空だけ読みを代替候補にし未判定では確定しない`() {
        fun engine(result: PredictionSearchResult) = BasicSkkEngine(object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun predict(query: PredictionQuery) = result
        }).also {
            it.dispatch(BasicSkkAction.SetTouchPrediction(true))
            it.dispatch(BasicSkkAction.StartReading)
            it.dispatch(BasicSkkAction.Text("ka", interpretCommands = false))
        }

        val empty = engine(PredictionSearchResult.ConfirmedEmpty)
        assertEquals("か", empty.currentView.prediction?.fallbackText)
        assertEquals("か", empty.dispatch(BasicSkkAction.CommitPrediction()).commit)

        val unknown = engine(PredictionSearchResult.Indeterminate(PredictionSearchFailure.WORK_LIMIT))
        assertNull(unknown.currentView.prediction?.fallbackText)
        assertNull(unknown.dispatch(BasicSkkAction.CommitPrediction()).commit)
        assertEquals(InputPhase.READING, unknown.state.phase)
    }

    @Test fun `カタカナ読みにも辞書のひらがな接頭辞を使い空候補だけ表示形を確定する`() {
        fun engine(result: PredictionSearchResult) = BasicSkkEngine(object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun predict(query: PredictionQuery): PredictionSearchResult {
                return if (query.prefix == "にほん") result else
                    PredictionSearchResult.Indeterminate(PredictionSearchFailure.UNSUPPORTED)
            }
        }).also {
            it.dispatch(BasicSkkAction.SetInputMode(InputMode.KATAKANA))
            it.dispatch(BasicSkkAction.SetTouchPrediction(true))
            it.dispatch(BasicSkkAction.StartReading)
            it.dispatch(BasicSkkAction.Text("nihon'", interpretCommands = false))
        }

        val target = PredictionHistoryTarget("にほん", "日本", null, "日本")
        val hit = engine(PredictionSearchResult.Ready(listOf(
            PredictionCandidate(DictionaryCandidate("日本"), "日本", target),
        ), false))
        assertEquals("ニホン", hit.currentView.composing)
        assertEquals("日本", hit.dispatch(BasicSkkAction.CommitPrediction()).commit)

        val empty = engine(PredictionSearchResult.ConfirmedEmpty)
        assertEquals("ニホン", empty.currentView.prediction?.fallbackText)
        assertEquals("ニホン", empty.dispatch(BasicSkkAction.CommitPrediction()).commit)
    }

    @Test fun `カタカナ読みの送りあり予測は送りだけをカタカナで表示して確定する`() {
        val canonical = PredictionHistoryTarget("かk", "書", "く", "書く")
        val dictionary = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun predict(query: PredictionQuery) = PredictionSearchResult.Ready(listOf(
                PredictionCandidate(DictionaryCandidate("書", okuriCondition = "く"), "書く", canonical),
            ), false)
        }
        val engine = BasicSkkEngine(dictionary, RegistrationPolicy(savingAllowed = true), learningEnabled = true)
        engine.dispatch(BasicSkkAction.SetInputMode(InputMode.KATAKANA))
        engine.dispatch(BasicSkkAction.SetTouchPrediction(true))
        engine.dispatch(BasicSkkAction.StartReading)
        engine.dispatch(BasicSkkAction.Text("ka", interpretCommands = false))

        assertEquals("書ク", engine.currentView.prediction!!.items.single().committedText)
        val committed = engine.dispatch(BasicSkkAction.CommitPrediction())
        assertEquals("書ク", committed.commit)
        val request = (committed.effects.single() as BasicSkkEffect.LearnCandidate).request
        assertEquals(DictionaryQuery("かk", "く"), request.query)
        assertEquals("書ク", request.committedText)
        assertEquals("書ク", request.historyTarget?.committedText)
    }

    @Test fun `描画後に候補集合が変わった操作は同じ位置の別候補を確定しない`() {
        val dictionary = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) =
                listOf(DictionaryCandidate("候補-${query.readingKey}"))
            override fun predict(query: PredictionQuery): PredictionSearchResult {
                val value = "予測-${query.prefix}"
                val target = PredictionHistoryTarget(query.prefix, value, null, value)
                return PredictionSearchResult.Ready(listOf(
                    PredictionCandidate(DictionaryCandidate(value), value, target),
                ), false)
            }
        }

        val prediction = BasicSkkEngine(dictionary).also {
            it.dispatch(BasicSkkAction.SetTouchPrediction(true))
            it.dispatch(BasicSkkAction.StartReading)
            it.dispatch(BasicSkkAction.Text("ka", interpretCommands = false))
        }
        val oldPrediction = prediction.currentView.prediction!!
        val oldItem = oldPrediction.items.single()
        prediction.dispatch(BasicSkkAction.Text("na", interpretCommands = false))
        val stalePrediction = prediction.dispatch(BasicSkkAction.CommitPrediction(
            0, oldItem, oldPrediction,
        ))
        assertNull(stalePrediction.commit)
        assertEquals("予測-かな", prediction.currentView.prediction?.items?.single()?.committedText)

        val conversion = BasicSkkEngine(dictionary)
        conversion.startConversion()
        val oldCandidate = conversion.currentView.candidate!!.pageItems.first().candidate
        conversion.dispatch(BasicSkkAction.Cancel)
        conversion.dispatch(BasicSkkAction.ConvertNext)
        val staleCandidate = conversion.dispatch(BasicSkkAction.SelectCandidate(0, oldCandidate))
        assertNull(staleCandidate.commit)
        assertEquals(InputPhase.SELECTING, conversion.state.phase)
    }

    @Test fun `画面予測中は動的補完を検索せず読みと予測だけを更新する`() {
        var completionCalls = 0
        val dictionary = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = emptyList<DictionaryCandidate>()
            override fun complete(query: CompletionQuery): List<String> {
                completionCalls++
                throw DeferredDictionaryReadException { }
            }
            override fun predict(query: PredictionQuery) =
                PredictionSearchResult.Indeterminate(PredictionSearchFailure.PENDING)
        }
        val engine = BasicSkkEngine(
            dictionary,
            RegistrationPolicy(),
            completionConfig = CompletionConfig(dynamicEnabled = true),
        )

        engine.dispatch(BasicSkkAction.SetTouchPrediction(true))
        engine.dispatch(BasicSkkAction.StartReading)
        val input = engine.dispatch(BasicSkkAction.Text("ka", interpretCommands = false))
        assertEquals("か", input.view.composing)
        assertEquals(PredictionSearchFailure.PENDING, input.view.prediction?.failure)
        assertEquals(0, completionCalls)

        val refreshed = engine.dispatch(BasicSkkAction.RefreshPrediction)
        assertEquals("か", refreshed.view.composing)
        assertEquals(PredictionSearchFailure.PENDING, refreshed.view.prediction?.failure)
        assertEquals(0, completionCalls)
    }
}
