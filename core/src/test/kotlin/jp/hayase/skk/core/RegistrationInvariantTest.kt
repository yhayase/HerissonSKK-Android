package jp.hayase.skk.core

import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class RegistrationInvariantTest {
    @Test fun `生成した操作列と遅延完了でも登録の隔離と一意性を維持する`() {
        repeat(24) { seed ->
            val random = Random(seed.toLong())
            val engine = BasicSkkEngine(BasicSkkDictionary { query ->
                if (query.readingKey == "にほん") listOf(DictionaryCandidate("日本"), DictionaryCandidate("二本"))
                else emptyList()
            }, RegistrationPolicy(enabled = true, sessionGeneration = seed.toLong()))
            val requests = mutableListOf<RegistrationSaveRequest>()
            val identities = mutableSetOf<RegistrationSaveToken>()
            repeat(700) {
                val beforeDepth = engine.state.registrationDepth
                val result = if (requests.isNotEmpty() && random.nextInt(6) == 0) {
                    val request = requests[random.nextInt(requests.size)]
                    val outcome = when (random.nextInt(3)) {
                        0 -> RegistrationSaveOutcome.Failed(RegistrationSaveFailure.GENERAL)
                        1 -> RegistrationSaveOutcome.SavedButNotApplied
                        else -> RegistrationSaveOutcome.Applied
                    }
                    engine.completeRegistration(RegistrationSaveCompletion(request.token, outcome))
                } else {
                    val action = when (random.nextInt(14)) {
                        0 -> BasicSkkAction.Enter
                        1 -> BasicSkkAction.Cancel
                        2 -> BasicSkkAction.Backspace
                        3 -> BasicSkkAction.Delete
                        4 -> BasicSkkAction.Left
                        5 -> BasicSkkAction.Right
                        6 -> BasicSkkAction.Home
                        7 -> BasicSkkAction.End
                        8 -> BasicSkkAction.Kana
                        else -> BasicSkkAction.Text(listOf("Michi ", "Ko ", "Nihon  ", "lword", "a", " ", "👩‍💻", "か\u3099", "q")[random.nextInt(9)])
                    }
                    engine.dispatch(action)
                }
                result.effects.forEach { effect ->
                    val request = (effect as BasicSkkEffect.SaveRegistration).request
                    assertTrue("保存要求は一度だけ発行します", identities.add(request.token))
                    assertTrue(request.candidateText.isNotEmpty())
                    assertTrue(request.candidateText.length <= 65_536)
                    requests += request
                }
                assertTrue(engine.state.registrationDepth in 0..16)
                result.view.registration?.let { view ->
                    assertTrue(view.cursor in 0..view.body.length)
                    EditableBuffer(view.body, view.cursor)
                    assertTrue(view.body.length <= 65_536)
                    if (beforeDepth > 0) assertNull("内側の確定を入力先へ出しません", result.commit)
                    assertNull(result.view.candidate)
                }
                result.commit?.let { EditableBuffer(it) }
                if (random.nextInt(80) == 0) {
                    engine.resetComposition()
                    requests.lastOrNull()?.let { request ->
                        val stale = engine.completeRegistration(RegistrationSaveCompletion(request.token, RegistrationSaveOutcome.Applied))
                        assertFalse(stale.handled)
                        assertNull(stale.commit)
                        assertNull(stale.view.registration)
                    }
                }
            }
        }
    }
}
