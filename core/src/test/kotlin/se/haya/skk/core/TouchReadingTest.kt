package se.haya.skk.core

import org.junit.Assert.*
import org.junit.Test
import se.haya.skk.core.dictionary.*

class TouchReadingTest {
    @Test fun `タッチShiftは空の読みでも次の文字まで有効で自動開始では残らない`() {
        val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() })
        engine.dispatch(BasicSkkAction.StartTouchReading())
        assertEquals(InputPhase.READING, engine.state.phase)
        assertTrue(engine.state.touchShiftPending)
        engine.dispatch(BasicSkkAction.TouchText("か"))
        assertFalse(engine.state.touchShiftPending)
        assertNull(engine.state.okuriConsonant)
        engine.dispatch(BasicSkkAction.Cancel)
        engine.dispatch(BasicSkkAction.TouchText("か", autoStartReading = true))
        assertFalse(engine.state.touchShiftPending)
        engine.dispatch(BasicSkkAction.TouchText("く", autoStartReading = true))
        assertEquals("かく", engine.state.reading)
        assertNull(engine.state.okuriConsonant)
    }

    @Test fun `自動開始切替のShift解除は読みを保持し送り指定を残さない`() {
        for (initial in listOf("", "か")) {
            val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() })
            if (initial.isNotEmpty()) engine.dispatch(BasicSkkAction.TouchText(initial, autoStartReading = true))
            engine.dispatch(BasicSkkAction.StartTouchReading())
            assertTrue(engine.state.touchShiftPending)
            engine.dispatch(BasicSkkAction.CancelTouchShift)
            assertFalse(engine.state.touchShiftPending)
            assertEquals(InputPhase.READING, engine.state.phase)
            assertEquals(initial, engine.state.reading)
            engine.dispatch(BasicSkkAction.TouchText("く", autoStartReading = true))
            assertEquals(initial + "く", engine.state.reading)
            assertNull(engine.state.okuriConsonant)
        }
    }

    @Test fun `自動開始中のShiftと長押し境界は送りを指定し状態を消費する`() {
        for (hold in listOf(false, true)) {
            val engine = BasicSkkEngine(BasicSkkDictionary { listOf(DictionaryCandidate("書")) })
            engine.dispatch(BasicSkkAction.TouchText("か", autoStartReading = true))
            if (!hold) engine.dispatch(BasicSkkAction.StartTouchReading())
            engine.dispatch(BasicSkkAction.TouchText("く", boundary = hold, autoStartReading = true))
            assertEquals("書く", engine.currentView.candidate!!.committedText)
            assertFalse(engine.state.touchShiftPending)
        }
    }

    @Test fun `候補中のShiftと解除は候補を保ち次の文字で新しい読みを始める`() {
        for (cancelShift in listOf(false, true)) {
            val engine = BasicSkkEngine(BasicSkkDictionary { listOf(DictionaryCandidate("日本")) })
            engine.dispatch(BasicSkkAction.TouchText("にほん", autoStartReading = true))
            engine.dispatch(BasicSkkAction.ConvertNext)
            val candidate = engine.currentView.candidate
            engine.dispatch(BasicSkkAction.StartTouchReading())
            assertTrue(engine.state.touchShiftPending)
            assertEquals(InputPhase.SELECTING, engine.state.phase)
            assertEquals(candidate, engine.currentView.candidate)
            if (cancelShift) {
                engine.dispatch(BasicSkkAction.CancelTouchShift)
                assertFalse(engine.state.touchShiftPending)
                assertEquals(candidate, engine.currentView.candidate)
            }
            val result = engine.dispatch(BasicSkkAction.TouchText("か"))
            assertEquals(if (cancelShift) "日本か" else "日本", result.commit)
            assertEquals(if (cancelShift) InputPhase.IDLE else InputPhase.READING, engine.state.phase)
            assertEquals(if (cancelShift) "" else "か", engine.state.reading)
            assertNull(engine.state.okuriConsonant)
            assertFalse(engine.state.touchShiftPending)
        }
    }

    @Test fun `abbrev中のShiftと解除は読みを保ち次の文字を送りにしない`() {
        for (cancelShift in listOf(false, true)) {
            val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() })
            engine.dispatch(BasicSkkAction.StartAbbrev)
            engine.dispatch(BasicSkkAction.TouchText("API"))
            engine.dispatch(BasicSkkAction.StartTouchReading())
            assertTrue(engine.state.touchShiftPending)
            assertEquals(InputPhase.ABBREV, engine.state.phase)
            assertEquals("API", engine.state.reading)
            if (cancelShift) {
                engine.dispatch(BasicSkkAction.CancelTouchShift)
                assertFalse(engine.state.touchShiftPending)
                assertEquals(InputPhase.ABBREV, engine.state.phase)
                assertEquals("API", engine.state.reading)
            }
            val result = engine.dispatch(BasicSkkAction.TouchText("か"))
            assertNull(result.commit)
            assertEquals(InputPhase.ABBREV, engine.state.phase)
            assertEquals("APIか", engine.state.reading)
            assertNull(engine.state.okuriConsonant)
            assertFalse(engine.state.touchShiftPending)
        }
    }

    @Test fun `登録中のタッチShiftは内側の読みを反映し取消で外側へ戻る`() {
        val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() }, RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.TouchText("かな", autoStartReading = true))
        engine.dispatch(BasicSkkAction.ConvertNext)
        assertEquals(1, engine.state.registrationDepth)
        assertFalse(engine.state.touchShiftPending)
        engine.dispatch(BasicSkkAction.StartTouchReading())
        assertTrue(engine.state.touchShiftPending)
        engine.dispatch(BasicSkkAction.CancelTouchShift)
        assertFalse(engine.state.touchShiftPending)
        engine.dispatch(BasicSkkAction.TouchText("か", autoStartReading = true))
        engine.dispatch(BasicSkkAction.StartTouchReading())
        assertTrue(engine.state.touchShiftPending)
        engine.dispatch(BasicSkkAction.CancelRegistrationFrame)
        assertEquals(0, engine.state.registrationDepth)
        assertFalse(engine.state.touchShiftPending)
        assertEquals("かな", engine.state.reading)
    }

    @Test fun `タッチShiftは物理入力と入力終了で解除される`() {
        for (finish in listOf(BasicSkkAction.Cancel, BasicSkkAction.CommitTouchReading,
            BasicSkkAction.Backspace, BasicSkkAction.Text("ka"))) {
            val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() })
            engine.dispatch(BasicSkkAction.StartTouchReading())
            engine.dispatch(finish)
            assertFalse(engine.state.touchShiftPending)
        }
        val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() })
        engine.dispatch(BasicSkkAction.StartTouchReading())
        engine.resetComposition()
        assertFalse(engine.state.touchShiftPending)
    }

    @Test fun `自動開始は通常入力の未消化ローマ字を確定してから読みを始める`() {
        val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() })
        engine.dispatch(BasicSkkAction.Text("n"))
        val result = engine.dispatch(BasicSkkAction.TouchText("か", autoStartReading = true))
        assertEquals("ん", result.commit)
        assertEquals("か", engine.state.reading)
        assertEquals("", engine.state.pendingRomaji)
    }

    @Test fun `自動開始したカタカナ読みは確定と取消で開始時の文字種へ戻る`() {
        for (finish in listOf(BasicSkkAction.CommitTouchReading, BasicSkkAction.Cancel)) {
            val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() })
            engine.dispatch(BasicSkkAction.SetInputMode(InputMode.KATAKANA))
            engine.dispatch(BasicSkkAction.TouchText("カ", kanaMode = InputMode.KATAKANA, autoStartReading = true))
            assertEquals("か", engine.state.reading)
            val result = engine.dispatch(finish)
            assertEquals(if (finish == BasicSkkAction.CommitTouchReading) "カ" else null, result.commit)
            assertEquals(InputMode.KATAKANA, engine.state.mode)
            assertEquals(InputPhase.IDLE, engine.state.phase)
        }
    }

    @Test fun `自動開始は全ての文字種で読みを始め後続かなを送りにしない`() {
        for (text in listOf("か", "API", "１２", "。", "😀")) {
            for (mode in InputMode.entries) {
                val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() })
                engine.dispatch(BasicSkkAction.SetInputMode(mode))
                val result = engine.dispatch(BasicSkkAction.TouchText(text, autoStartReading = true))
                assertNull(result.commit)
                assertEquals(InputPhase.READING, engine.state.phase)
                engine.dispatch(BasicSkkAction.TouchText("く", autoStartReading = true))
                assertEquals(text + "く", engine.state.reading)
                assertNull(engine.state.okuriConsonant)
                assertEquals("", engine.state.okuri)
            }
        }
    }

    @Test fun `自動開始後も明示境界は送りを開始する`() {
        val engine = BasicSkkEngine(BasicSkkDictionary { listOf(DictionaryCandidate("書")) })
        engine.dispatch(BasicSkkAction.TouchText("か", autoStartReading = true))
        engine.dispatch(BasicSkkAction.TouchText("く", boundary = true, autoStartReading = true))
        assertEquals('k', engine.state.okuriConsonant)
        assertEquals("書く", engine.currentView.candidate!!.committedText)
    }

    @Test fun `自動開始付き文字は候補ページ先頭を一度確定して新しい読みに入る`() {
        val engine = BasicSkkEngine(BasicSkkDictionary { (1..7).map { DictionaryCandidate("候補$it") } }, RegistrationPolicy(),
            candidatePageCapacityProvider = { _, _ -> 2 })
        engine.dispatch(BasicSkkAction.TouchText("か", autoStartReading = true))
        engine.dispatch(BasicSkkAction.ConvertNext)
        engine.dispatch(BasicSkkAction.NextCandidatePage)
        val result = engine.dispatch(BasicSkkAction.TouchText("Q/", autoStartReading = true))
        assertEquals("候補3", result.commit)
        assertEquals(InputPhase.READING, engine.state.phase)
        assertEquals("Q/", engine.state.reading)
    }

    @Test fun `自動開始は登録本文内でも子の読みを開始する`() {
        val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() }, RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.TouchText("おや", autoStartReading = true))
        engine.dispatch(BasicSkkAction.ConvertNext)
        engine.dispatch(BasicSkkAction.TouchText("親本文"))
        val result = engine.dispatch(BasicSkkAction.TouchText("こ", autoStartReading = true))
        assertEquals("親本文", result.view.registration!!.body)
        assertEquals("こ", result.view.registration!!.innerComposing)
        assertNull(result.commit)
        engine.dispatch(BasicSkkAction.TouchText("ども", autoStartReading = true))
        assertNull(engine.state.okuriConsonant)
        engine.dispatch(BasicSkkAction.ConvertNext)
        assertEquals(2, engine.state.registrationDepth)
    }

    @Test fun `各モードから同じ読みを開始し大文字とコマンド文字を保持する`() {
        for (mode in InputMode.entries) {
            var query: DictionaryQuery? = null
            val engine = BasicSkkEngine(BasicSkkDictionary { query = it; listOf(DictionaryCandidate("設定")) })
            engine.dispatch(BasicSkkAction.SetInputMode(mode))
            engine.dispatch(BasicSkkAction.StartTouchReading(InputMode.KATAKANA))
            engine.dispatch(BasicSkkAction.TouchText("API/qL12ガク"))
            assertEquals("API/qL12がく", engine.state.reading)
            engine.dispatch(BasicSkkAction.ConvertNext)
            assertEquals(DictionaryQuery("API/qL12がく", commonReading = true), query)
        }
    }

    @Test fun `通常の英数字は文字どおり確定し境界だけが読みを開始する`() {
        val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() })
        assertEquals("Aq/1", engine.dispatch(BasicSkkAction.TouchText("Aq/1")).commit)
        engine.dispatch(BasicSkkAction.TouchText("API", boundary = true))
        engine.dispatch(BasicSkkAction.TouchText("2", boundary = true))
        assertEquals("API2", engine.state.reading)
        assertNull(engine.state.okuriConsonant)
    }

    @Test fun `送りは固定の子音で即変換し候補表示後も循環して再検索する`() {
        val queries = mutableListOf<DictionaryQuery>()
        val engine = BasicSkkEngine(BasicSkkDictionary { queries += it; listOf(DictionaryCandidate("書")) })
        engine.dispatch(BasicSkkAction.TouchText("か", boundary = true))
        engine.dispatch(BasicSkkAction.TouchText("く", boundary = true))
        assertEquals("かk", queries.last().readingKey)
        assertEquals("書く", engine.currentView.candidate!!.committedText)
        engine.dispatch(BasicSkkAction.CycleTouchKana)
        assertEquals("かg", queries.last().readingKey)
        assertEquals("書ぐ", engine.currentView.candidate!!.committedText)
        for ((kana, consonant) in listOf('し' to 's', 'ち' to 't', 'ふ' to 'h', 'じ' to 'z', 'ぢ' to 'd', 'づ' to 'd', 'ゔ' to 'v', 'ん' to 'n', 'っ' to 't')) {
            assertEquals(consonant, TouchKana.consonant(kana))
        }
    }

    @Test fun `送りの空登録から修正して候補へ戻る`() {
        val engine = BasicSkkEngine(BasicSkkDictionary {
            if (it.readingKey == "かg") listOf(DictionaryCandidate("書")) else emptyList()
        }, RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.TouchText("か", boundary = true))
        engine.dispatch(BasicSkkAction.TouchText("く", boundary = true))
        assertEquals(1, engine.state.registrationDepth)
        engine.dispatch(BasicSkkAction.CycleTouchKana)
        assertEquals(0, engine.state.registrationDepth)
        assertEquals("書ぐ", engine.currentView.candidate!!.committedText)
    }

    @Test fun `送り登録の取消は全文を送りなし読みに戻し次のかなで再登録しない`() {
        for (cancel in listOf(BasicSkkAction.CancelRegistrationFrame, BasicSkkAction.Cancel)) {
            val queries = mutableListOf<DictionaryQuery>()
            val engine = BasicSkkEngine(BasicSkkDictionary { queries += it; emptyList() },
                RegistrationPolicy(enabled = true))
            engine.dispatch(BasicSkkAction.TouchText("り", boundary = true))
            engine.dispatch(BasicSkkAction.TouchText("よ", boundary = true))
            assertEquals(DictionaryQuery("りy", "よ", commonReading = true), queries.single())
            assertEquals(1, engine.state.registrationDepth)

            val canceled = engine.dispatch(cancel)
            assertEquals(0, engine.state.registrationDepth)
            assertEquals(InputPhase.READING, engine.state.phase)
            assertEquals("りよ", engine.state.reading)
            assertEquals("りよ", canceled.view.composing)
            assertEquals(2, canceled.view.cursor)
            assertNull(engine.state.okuriConsonant)
            assertEquals("", engine.state.okuri)
            assertEquals("", engine.state.pendingRomaji)
            assertNull(canceled.commit)

            engine.dispatch(BasicSkkAction.TouchText("う"))
            assertEquals("りよう", engine.state.reading)
            assertEquals(0, engine.state.registrationDepth)
            assertEquals(1, queries.size)
            engine.dispatch(BasicSkkAction.ConvertNext)
            assertEquals(DictionaryQuery("りよう", commonReading = true), queries.last())
        }
    }

    @Test fun `子の送り登録の取消は親本文を保ち全文を送りなしで編集する`() {
        for (cancel in listOf(BasicSkkAction.CancelRegistrationFrame, BasicSkkAction.Cancel)) {
            val queries = mutableListOf<DictionaryQuery>()
            val engine = BasicSkkEngine(BasicSkkDictionary { queries += it; emptyList() },
                RegistrationPolicy(enabled = true))
            engine.dispatch(BasicSkkAction.TouchText("おや", boundary = true))
            engine.dispatch(BasicSkkAction.ConvertNext)
            engine.dispatch(BasicSkkAction.TouchText("親本文"))
            engine.dispatch(BasicSkkAction.TouchText("り", boundary = true))
            engine.dispatch(BasicSkkAction.TouchText("よ", boundary = true))
            assertEquals(2, engine.state.registrationDepth)

            val canceled = engine.dispatch(cancel)
            assertEquals(1, engine.state.registrationDepth)
            assertEquals("親本文", canceled.view.registration!!.body)
            assertEquals("りよ", canceled.view.registration!!.innerComposing)
            assertNull(engine.state.okuriConsonant)
            assertEquals("", engine.state.okuri)
            engine.dispatch(BasicSkkAction.TouchText("う"))
            assertEquals("りよう", engine.currentView.registration!!.innerComposing)
            assertEquals(1, engine.state.registrationDepth)
            assertEquals(2, queries.size)
            engine.dispatch(BasicSkkAction.ConvertNext)
            assertEquals(DictionaryQuery("りよう", commonReading = true), queries.last())
        }
    }

    @Test fun `送り候補末尾と明示登録の取消も候補を閉じて送りなし読みに戻す`() {
        for (cancel in listOf(BasicSkkAction.CancelRegistrationFrame, BasicSkkAction.Cancel)) {
            for (explicit in listOf(false, true)) {
                val queries = mutableListOf<DictionaryQuery>()
                val engine = BasicSkkEngine(BasicSkkDictionary {
                    queries += it
                    listOf(DictionaryCandidate("利用"))
                }, RegistrationPolicy(enabled = true))
                engine.dispatch(BasicSkkAction.TouchText("り", boundary = true))
                engine.dispatch(BasicSkkAction.TouchText("よ", boundary = true))
                engine.dispatch(if (explicit) BasicSkkAction.RegisterCandidate else BasicSkkAction.ConvertNext)
                assertEquals(1, engine.state.registrationDepth)
                val canceled = engine.dispatch(cancel)
                assertEquals(0, engine.state.registrationDepth)
                assertEquals(InputPhase.READING, engine.state.phase)
                assertEquals("りよ", engine.state.reading)
                assertEquals("", engine.state.okuri)
                assertNull(engine.state.okuriConsonant)
                assertNull(canceled.view.candidate)
                assertNull(canceled.commit)
                engine.dispatch(BasicSkkAction.TouchText("う"))
                assertEquals("りよう", engine.state.reading)
                assertEquals(1, queries.size)
                engine.dispatch(BasicSkkAction.ConvertNext)
                assertEquals(DictionaryQuery("りよう", commonReading = true), queries.last())
            }
        }
    }

    @Test fun `送りあり明示登録は読み段階からも取消時に全文を残す`() {
        val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() }, RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.TouchText("り", boundary = true))
        engine.dispatch(BasicSkkAction.TouchText("よ", boundary = true))
        // 空本文の確定では従来どおり送りあり読みへ戻して、そこから明示登録します。
        engine.dispatch(BasicSkkAction.Enter)
        assertEquals('y', engine.state.okuriConsonant)
        engine.dispatch(BasicSkkAction.RegisterCandidate)
        engine.dispatch(BasicSkkAction.CancelRegistrationFrame)
        assertEquals(InputPhase.READING, engine.state.phase)
        assertEquals("りよ", engine.state.reading)
        assertEquals("", engine.state.okuri)
        assertNull(engine.state.okuriConsonant)
        engine.dispatch(BasicSkkAction.TouchText("う"))
        assertEquals("りよう", engine.state.reading)
        assertEquals(0, engine.state.registrationDepth)
    }

    @Test fun `混在読みの文字種確定は非かなを保持し送りを一度だけ出力する`() {
        val engine = BasicSkkEngine(BasicSkkDictionary { listOf(DictionaryCandidate("学")) })
        engine.dispatch(BasicSkkAction.TouchText("APIが", boundary = true))
        engine.dispatch(BasicSkkAction.TouchText("く", boundary = true))
        assertEquals("APIガク", engine.dispatch(BasicSkkAction.ConvertTouchReading()).commit)
        engine.dispatch(BasicSkkAction.StartTouchReading(InputMode.KATAKANA))
        engine.dispatch(BasicSkkAction.TouchText("ＡPIガ１２"))
        assertEquals("ＡPIが１２", engine.dispatch(BasicSkkAction.ConvertTouchReading()).commit)
        engine.dispatch(BasicSkkAction.StartTouchReading())
        engine.dispatch(BasicSkkAction.TouchText("APIがく。、"))
        assertEquals("APIｶﾞｸ。、", engine.dispatch(BasicSkkAction.ConvertTouchReading(true)).commit)
    }

    @Test fun `登録内の文字種確定は本文へ入れ保存しない`() {
        val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() }, RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.TouchText("みち", boundary = true))
        engine.dispatch(BasicSkkAction.ConvertNext)
        engine.dispatch(BasicSkkAction.StartTouchReading())
        engine.dispatch(BasicSkkAction.TouchText("APIがく"))
        val result = engine.dispatch(BasicSkkAction.ConvertTouchReading())
        assertNull(result.commit)
        assertTrue(result.effects.isEmpty())
        assertEquals("APIガク", result.view.registration!!.body)
    }

    @Test fun `修正は編集位置直前の書記素だけを変更する`() {
        val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() })
        engine.dispatch(BasicSkkAction.StartTouchReading())
        engine.dispatch(BasicSkkAction.TouchText("😀はな"))
        engine.dispatch(BasicSkkAction.Left)
        engine.dispatch(BasicSkkAction.CycleTouchKana)
        assertEquals("😀ばな", engine.state.reading)
        engine.dispatch(BasicSkkAction.CycleTouchKana)
        assertEquals("😀ぱな", engine.state.reading)
    }

    @Test fun `辞書の延期は送り修正と登録階層を完全に巻き戻す`() {
        var ready = false
        val engine = BasicSkkEngine(BasicSkkDictionary {
            if (it.readingKey == "かg" && !ready) throw DeferredDictionaryReadException { ready = true }
            emptyList()
        }, RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.TouchText("か", boundary = true))
        engine.dispatch(BasicSkkAction.TouchText("く", boundary = true))
        val before = engine.state
        val pending = assertThrows(DeferredDictionaryReadException::class.java) { engine.dispatch(BasicSkkAction.CycleTouchKana) }
        assertEquals(before, engine.state)
        pending.load()
        engine.dispatch(BasicSkkAction.CycleTouchKana)
        engine.dispatch(BasicSkkAction.Enter)
        assertEquals('g', engine.state.okuriConsonant)
    }

    @Test fun `共通の混在見出し語は補完予測を行い物理abbrevの制限は保持する`() {
        val source = SkkDictionarySource("system", 1, listOf(
            SkkDictionaryEntry("かなapi", listOf(SkkDictionaryCandidate("候補")), SkkDictionarySection.OKURI_NASI),
            SkkDictionaryEntry("APIのせってい", listOf(SkkDictionaryCandidate("設定"))),
        ))
        val dictionary = CompositeSkkDictionary(systems = listOf(source))
        assertEquals(listOf("かなapi"), dictionary.complete(CompletionQuery("かな", commonReading = true)))
        assertEquals(listOf("かなapi"), dictionary.complete(CompletionQuery("かな")))
        assertTrue(dictionary.complete(CompletionQuery("API", abbrev = true)).isEmpty())
        val engine = BasicSkkEngine(dictionary)
        engine.dispatch(BasicSkkAction.SetTouchPrediction(true))
        engine.dispatch(BasicSkkAction.StartTouchReading())
        engine.dispatch(BasicSkkAction.TouchText("かな"))
        assertEquals("候補", engine.currentView.prediction!!.items.single().committedText)
        engine.dispatch(BasicSkkAction.CompleteForward)
        assertEquals("かなapi", engine.state.reading)
    }
    @Test fun `タッチ文字は画面ページ先頭を一度だけ確定して処理する`() {
        val engine = BasicSkkEngine(BasicSkkDictionary { (1..7).map { DictionaryCandidate("候補$it") } }, RegistrationPolicy(),
            candidatePageCapacityProvider = { _, _ -> 2 })
        engine.dispatch(BasicSkkAction.TouchText("か", boundary = true))
        engine.dispatch(BasicSkkAction.ConvertNext)
        engine.dispatch(BasicSkkAction.NextCandidatePage)
        assertEquals("候補3Q/", engine.dispatch(BasicSkkAction.TouchText("Q/")).commit)
        assertEquals(InputPhase.IDLE, engine.state.phase)
    }

    @Test fun `混在読みの登録要求は送り推定を禁止するメタデータを保持する`() {
        val engine = BasicSkkEngine(BasicSkkDictionary { emptyList() }, RegistrationPolicy(enabled = true))
        engine.dispatch(BasicSkkAction.TouchText("かなapi", boundary = true))
        engine.dispatch(BasicSkkAction.ConvertNext)
        engine.dispatch(BasicSkkAction.TouchText("登録語"))
        val result = engine.dispatch(BasicSkkAction.Enter)
        val request = (result.effects.single() as BasicSkkEffect.SaveRegistration).request
        assertTrue(request.commonReading)
        assertNull(request.okuriCondition)
        assertEquals("かなapi", request.readingKey)
    }

    @Test fun `読み開始キーは読みの途中で次のかなの送り境界を指定し再タップで解除する`() {
        val engine = BasicSkkEngine(BasicSkkDictionary { listOf(DictionaryCandidate("書")) })
        engine.dispatch(BasicSkkAction.StartTouchReading())
        engine.dispatch(BasicSkkAction.TouchText("か"))
        engine.dispatch(BasicSkkAction.StartTouchReading())
        engine.dispatch(BasicSkkAction.TouchText("く"))
        assertEquals("書く", engine.currentView.candidate!!.committedText)
        val plain = BasicSkkEngine(BasicSkkDictionary { emptyList() })
        plain.dispatch(BasicSkkAction.StartTouchReading())
        plain.dispatch(BasicSkkAction.TouchText("か"))
        plain.dispatch(BasicSkkAction.StartTouchReading())
        plain.dispatch(BasicSkkAction.StartTouchReading())
        plain.dispatch(BasicSkkAction.TouchText("く"))
        assertEquals("かく", plain.state.reading)
        assertNull(plain.state.okuriConsonant)
        plain.dispatch(BasicSkkAction.StartTouchReading())
        plain.dispatch(BasicSkkAction.TouchText("A"))
        assertNull(plain.state.okuriConsonant)
        assertEquals("かくA", plain.state.reading)
    }

    @Test fun `共通読みの予測は送りあり辞書の区分と送りと学習キーを保持する`() {
        val source = SkkDictionarySource("system", 1, listOf(
            SkkDictionaryEntry("かk", listOf(SkkDictionaryCandidate("書", okuriCondition = "く")), SkkDictionarySection.OKURI_ARI),
            SkkDictionaryEntry("かg", listOf(SkkDictionaryCandidate("書")), SkkDictionarySection.OKURI_ARI),
            SkkDictionaryEntry("かなapi", listOf(SkkDictionaryCandidate("候補")), SkkDictionarySection.OKURI_NASI),
        ))
        val dictionary = CompositeSkkDictionary(systems = listOf(source))
        assertEquals(listOf("かなapi"), dictionary.complete(CompletionQuery("か", commonReading = true)))
        val predictions = (dictionary.predict(PredictionQuery("か", commonReading = true)) as PredictionSearchResult.Ready).items
        assertEquals(setOf("書く", "候補"), predictions.map { it.committedText }.toSet())
        val okuriPrediction = predictions.single { it.committedText == "書く" }
        assertEquals(SkkDictionarySection.OKURI_ARI, okuriPrediction.candidate.entrySection)
        assertEquals("く", okuriPrediction.historyTarget.okuriCondition)
        assertEquals(SkkDictionarySection.OKURI_ARI,
            dictionary.lookup(DictionaryQuery("かk", commonReading = true)).single().entrySection)
        val engine = BasicSkkEngine(dictionary, RegistrationPolicy(), learningEnabled = true)
        engine.dispatch(BasicSkkAction.SetTouchPrediction(true))
        engine.dispatch(BasicSkkAction.StartTouchReading())
        engine.dispatch(BasicSkkAction.TouchText("か"))
        val index = engine.currentView.prediction!!.items.indexOfFirst { it.committedText == "書く" }
        val committed = engine.dispatch(BasicSkkAction.CommitPrediction(index))
        assertEquals("書く", committed.commit)
        val learning = (committed.effects.single() as BasicSkkEffect.LearnCandidate).request
        assertEquals("かk", learning.query.readingKey)
        assertEquals("く", learning.query.okuri)
        assertEquals(SkkDictionarySection.OKURI_ARI, learning.candidate.entrySection)
        assertEquals("書く", learning.historyTarget!!.committedText)
    }

}
