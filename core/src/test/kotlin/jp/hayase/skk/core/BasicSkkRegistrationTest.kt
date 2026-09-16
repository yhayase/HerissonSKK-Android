package jp.hayase.skk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicSkkRegistrationTest {
    private fun engine(
        entries: Map<DictionaryQuery, List<DictionaryCandidate>> = emptyMap(),
        savingAllowed: Boolean = true,
    ) = BasicSkkEngine(
        dictionary = BasicSkkDictionary { entries[it].orEmpty() },
        registrationPolicy = RegistrationPolicy(enabled = true, sessionGeneration = 42, savingAllowed = savingAllowed),
    )

    private fun BasicSkkEngine.type(text: String): BasicSkkResult {
        var result = dispatch(BasicSkkAction.Text(""))
        text.forEach { result = dispatch(BasicSkkAction.Text(it.toString())) }
        return result
    }

    private fun BasicSkkResult.saveRequest(): RegistrationSaveRequest =
        (effects.single() as BasicSkkEffect.SaveRegistration).request

    @Test fun `K09 未登録語は本文を入力先へ漏らさず保存成功後だけ確定する`() {
        val engine = engine()
        engine.type("Michi")
        val started = engine.dispatch(BasicSkkAction.Text(" "))
        assertEquals("みち", started.view.composing)
        assertEquals("", started.view.registration?.body)
        assertNull(started.commit)

        engine.type("miti")
        assertEquals("みち", engine.currentView.composing)
        assertEquals("みち", engine.currentView.registration?.body)
        val saving = engine.dispatch(BasicSkkAction.Enter)
        val request = saving.saveRequest()
        assertEquals(42L, request.token.sessionGeneration)
        assertEquals("みち", request.readingKey)
        assertEquals("みち", request.candidateText)
        assertNull(request.okuriCondition)
        assertEquals("みち", request.committedText)
        assertTrue(saving.view.registration!!.saving)
        assertTrue(engine.dispatch(BasicSkkAction.Enter).effects.isEmpty())

        val completed = engine.completeRegistration(RegistrationSaveCompletion(request.token, RegistrationSaveOutcome.Applied))
        assertEquals("みち", completed.commit)
        assertNull(completed.view.registration)
        assertNull(completed.view.composing)
        assertEquals(0, engine.state.registrationDepth)
    }

    @Test fun `未消化文字のEnterと登録保存のEnterを分離する`() {
        val engine = engine()
        engine.type("Michi ")
        engine.dispatch(BasicSkkAction.Text("n"))

        val inner = engine.dispatch(BasicSkkAction.Enter)
        assertEquals("ん", inner.view.registration?.body)
        assertTrue(inner.effects.isEmpty())
        assertFalse(inner.view.registration!!.saving)
        assertTrue(engine.dispatch(BasicSkkAction.Enter).effects.single() is BasicSkkEffect.SaveRegistration)
    }

    @Test fun `保存失敗は本文を保持し再試行を別操作として扱う`() {
        val engine = engine()
        engine.type("Michi ")
        engine.type("miti")
        val first = engine.dispatch(BasicSkkAction.Enter).saveRequest()

        val failed = engine.completeRegistration(
            RegistrationSaveCompletion(first.token, RegistrationSaveOutcome.Failed(RegistrationSaveFailure.CAPACITY)),
        )
        assertEquals("みち", failed.view.registration?.body)
        assertFalse(failed.view.registration!!.saving)
        assertTrue(failed.notice!!.contains("容量"))

        val second = engine.dispatch(BasicSkkAction.Enter).saveRequest()
        assertTrue(second.token.operationId > first.token.operationId)
        val stale = engine.completeRegistration(RegistrationSaveCompletion(first.token, RegistrationSaveOutcome.Applied))
        assertFalse(stale.handled)
        assertNull(stale.commit)
        val saved = engine.completeRegistration(
            RegistrationSaveCompletion(second.token, RegistrationSaveOutcome.SavedButNotApplied),
        )
        assertEquals("みち", saved.commit)
        assertTrue(saved.notice!!.contains("再読込"))
    }

    @Test fun `空本文Enterは検索前の読みまたは最後の候補へ戻る`() {
        val empty = engine()
        empty.type("Michi ")
        val reading = empty.dispatch(BasicSkkAction.Enter)
        assertEquals(InputPhase.READING, empty.state.phase)
        assertEquals("みち", reading.view.composing)

        val query = DictionaryQuery("にほん")
        val exhausted = engine(mapOf(query to listOf(DictionaryCandidate("日本"))))
        exhausted.type("Nihon ")
        exhausted.dispatch(BasicSkkAction.Text(" "))
        assertEquals(1, exhausted.state.registrationDepth)
        val candidate = exhausted.dispatch(BasicSkkAction.Enter)
        assertEquals(InputPhase.SELECTING, exhausted.state.phase)
        assertEquals("日本", candidate.view.candidate?.committedText)
    }

    @Test fun `本文が空で内側状態もないCgは登録を一段破棄する`() {
        val top = engine()
        top.type("Michi ")
        val normal = top.dispatch(BasicSkkAction.Cancel)
        assertEquals(0, top.state.registrationDepth)
        assertEquals(InputPhase.IDLE, top.state.phase)
        assertNull(normal.view.composing)

        val exhausted = engine(mapOf(DictionaryQuery("にほん") to listOf(DictionaryCandidate("日本"))))
        exhausted.type("Nihon  ")
        assertEquals(1, exhausted.state.registrationDepth)
        val canceledCandidate = exhausted.dispatch(BasicSkkAction.Cancel)
        assertEquals(InputPhase.IDLE, exhausted.state.phase)
        assertEquals(0, exhausted.state.registrationDepth)
        assertNull(canceledCandidate.view.composing)
        assertNull(canceledCandidate.view.candidate)
        assertNull(canceledCandidate.commit)

        val nested = engine()
        nested.type("Michi Ko ")
        assertEquals(2, nested.state.registrationDepth)
        val parent = nested.dispatch(BasicSkkAction.Cancel)
        assertEquals(1, nested.state.registrationDepth)
        assertEquals("", parent.view.registration?.body)
        assertNull(parent.view.registration?.innerComposing)
    }

    @Test fun `K10 子の保存成功は親だけへ挿入する`() {
        val engine = engine()
        engine.type("Michi ")
        engine.type("oya")
        engine.type("Ko ")
        assertEquals(2, engine.state.registrationDepth)
        assertEquals("みち", engine.currentView.composing)

        engine.type("ko")
        val child = engine.dispatch(BasicSkkAction.Enter).saveRequest()
        val childDone = engine.completeRegistration(RegistrationSaveCompletion(child.token, RegistrationSaveOutcome.Applied))
        assertNull(childDone.commit)
        assertEquals(1, engine.state.registrationDepth)
        assertEquals("おやこ", childDone.view.registration?.body)
        assertEquals("みち", childDone.view.composing)

        val parent = engine.dispatch(BasicSkkAction.Enter).saveRequest()
        assertEquals("おやこ", parent.candidateText)
        assertEquals("おやこ", engine.completeRegistration(
            RegistrationSaveCompletion(parent.token, RegistrationSaveOutcome.Applied),
        ).commit)
    }

    @Test fun `保存済みの子は親を取り消しても再送や入力確定をしない`() {
        val engine = engine()
        engine.type("Michi ")
        engine.type("Oya ")
        engine.type("ko")
        val child = engine.dispatch(BasicSkkAction.Enter).saveRequest()
        engine.completeRegistration(RegistrationSaveCompletion(child.token, RegistrationSaveOutcome.Applied))

        val parentCanceled = engine.dispatch(BasicSkkAction.Cancel)
        assertNull(parentCanceled.commit)
        assertEquals(0, engine.state.registrationDepth)
        assertEquals(InputPhase.IDLE, engine.state.phase)
        assertNull(parentCanceled.view.composing)
    }

    @Test fun `空本文のBSと確定済み本文のCjは登録を終了しない`() {
        val engine = engine()
        engine.type("Michi ")
        assertTrue(engine.dispatch(BasicSkkAction.Backspace).handled)
        assertEquals(1, engine.state.registrationDepth)
        engine.type("mi")
        val kana = engine.dispatch(BasicSkkAction.Kana)
        assertTrue(kana.effects.isEmpty())
        assertEquals("み", kana.view.registration?.body)
        assertEquals(1, engine.state.registrationDepth)
    }

    @Test fun `保存中Cgとセッション破棄は遅い完了を入力へ反映しない`() {
        val engine = engine()
        engine.type("Michi ")
        engine.type("miti")
        val abandoned = engine.dispatch(BasicSkkAction.Enter).saveRequest()
        val canceled = engine.dispatch(BasicSkkAction.Cancel)
        assertTrue(canceled.notice!!.contains("完了する可能性"))
        assertEquals(InputPhase.IDLE, engine.state.phase)
        assertFalse(engine.completeRegistration(
            RegistrationSaveCompletion(abandoned.token, RegistrationSaveOutcome.Applied),
        ).handled)

        engine.dispatch(BasicSkkAction.Cancel)
        engine.type("Michi ")
        engine.type("miti")
        val closed = engine.dispatch(BasicSkkAction.Enter).saveRequest()
        val clean = engine.resetComposition()
        assertNull(clean.composing)
        assertNull(clean.registration)
        assertEquals(0, engine.state.registrationDepth)
        assertFalse(engine.completeRegistration(
            RegistrationSaveCompletion(closed.token, RegistrationSaveOutcome.Applied),
        ).handled)
    }

    @Test fun `K18 本文中央と結合文字と絵文字を書記素単位で編集する`() {
        val engine = engine()
        engine.type("Michi ")
        engine.dispatch(BasicSkkAction.Text("東京"))
        engine.dispatch(BasicSkkAction.Home)
        engine.dispatch(BasicSkkAction.Right)
        engine.dispatch(BasicSkkAction.Backspace)
        assertEquals("京", engine.currentView.registration?.body)

        val cluster = "👩🏽‍💻"
        engine.dispatch(BasicSkkAction.Text(cluster))
        engine.dispatch(BasicSkkAction.Backspace)
        assertEquals("京", engine.currentView.registration?.body)
        engine.dispatch(BasicSkkAction.Text("か\u309a"))
        engine.dispatch(BasicSkkAction.Backspace)
        assertEquals("京", engine.currentView.registration?.body)
        assertEquals("京", engine.dispatch(BasicSkkAction.Enter).saveRequest().candidateText)
    }

    @Test fun `送りあり登録は語幹候補と送り条件を分け成功時だけ一度付ける`() {
        val engine = engine()
        engine.type("KaKu")
        assertEquals(1, engine.state.registrationDepth)
        engine.dispatch(BasicSkkAction.Text("書"))
        val request = engine.dispatch(BasicSkkAction.Enter).saveRequest()
        assertEquals("かk", request.readingKey)
        assertEquals("書", request.candidateText)
        assertEquals("く", request.okuriCondition)
        assertEquals("書く", request.committedText)
        assertEquals("書く", engine.completeRegistration(
            RegistrationSaveCompletion(request.token, RegistrationSaveOutcome.Applied),
        ).commit)
    }

    @Test fun `登録方針禁止は本文を保持して保存効果を発行しない`() {
        val engine = engine(savingAllowed = false)
        engine.type("Michi ")
        engine.type("miti")
        val denied = engine.dispatch(BasicSkkAction.Enter)
        assertTrue(denied.effects.isEmpty())
        assertEquals("みち", denied.view.registration?.body)
        assertTrue(denied.notice!!.contains("登録できません"))
    }

    @Test fun `登録の深さと本文長の上限超過は既存状態を変更しない`() {
        val depth = engine()
        depth.type("A ")
        repeat(15) { depth.type("A ") }
        assertEquals(16, depth.state.registrationDepth)
        val before = depth.currentView.registration
        val rejected = depth.type("A ")
        assertEquals(16, depth.state.registrationDepth)
        assertEquals(before?.body, rejected.view.registration?.body)
        assertTrue(rejected.notice!!.contains("16段"))

        val body = engine()
        body.type("Michi ")
        body.dispatch(BasicSkkAction.Text("あ".repeat(65_536)))
        assertEquals(65_536, body.currentView.registration?.body?.length)
        val overflow = body.dispatch(BasicSkkAction.Text("い"))
        assertEquals(65_536, overflow.view.registration?.body?.length)
        assertTrue(overflow.notice!!.contains("65,536"))
    }

    @Test fun `一括文字列は途中の登録開始後を本文へ送り通常確定の接頭部だけ返す`() {
        val engine = engine()
        val result = engine.dispatch(BasicSkkAction.Text("kanaMichi lprivate"))
        assertEquals("かな", result.commit)
        assertEquals("みち", result.view.composing)
        assertEquals("private", result.view.registration?.body)
        assertEquals(1, engine.state.registrationDepth)

        val noPrefix = engine()
        val registered = noPrefix.dispatch(BasicSkkAction.Text("Michi lprivate"))
        assertNull(registered.commit)
        assertEquals("private", registered.view.registration?.body)
    }

    @Test fun `一括Unicode本文の上限超過は登録開始後の本文全体を棄却する`() {
        val engine = engine()
        val rejected = engine.dispatch(BasicSkkAction.Text("Michi " + "あ".repeat(65_537)))
        assertNull(rejected.commit)
        assertEquals(1, engine.state.registrationDepth)
        assertEquals("", rejected.view.registration?.body)
        assertTrue(rejected.notice!!.contains("65,536"))

        val accepted = engine()
        accepted.dispatch(BasicSkkAction.Text("Michi " + "😀".repeat(32_768)))
        assertEquals(65_536, accepted.currentView.registration?.body?.length)
    }

    @Test(timeout = 10_000) fun `長い非ASCII列とASCII末尾を線形に一括登録する`() {
        val engine = engine()
        val body = "😀".repeat(32_767) + "."
        val result = engine.dispatch(BasicSkkAction.Text("Michi $body"))
        assertNull(result.commit)
        assertEquals(body, result.view.registration?.body)
        assertEquals(65_535, result.view.registration?.body?.length)
    }

    @Test fun `直接入力で作った子の成功後は親の開始モードへ戻る`() {
        val engine = engine()
        engine.type("Michi Ko lchild")
        assertEquals(InputMode.DIRECT, engine.state.mode)
        val child = engine.dispatch(BasicSkkAction.Enter).saveRequest()
        engine.completeRegistration(RegistrationSaveCompletion(child.token, RegistrationSaveOutcome.Applied))
        assertEquals(InputMode.HIRAGANA, engine.state.mode)
        engine.type("ko")
        assertEquals("childこ", engine.currentView.registration?.body)
    }
}
