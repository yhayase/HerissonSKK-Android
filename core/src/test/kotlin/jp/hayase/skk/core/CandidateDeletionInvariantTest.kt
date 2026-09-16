package jp.hayase.skk.core

import java.util.Random
import jp.hayase.skk.core.dictionary.CandidateSelection
import jp.hayase.skk.core.dictionary.SelectedCandidateOrigin
import org.junit.Assert.*
import org.junit.Test

class CandidateDeletionInvariantTest {
    @Test fun `生成した削除登録操作と遅延完了でも確認文字を確定せず古い要求を復活させない`() {
        repeat(24) { seed ->
            val random = Random(seed.toLong())
            val dictionary = BasicSkkDictionary { query ->
                if (query.readingKey.startsWith("み")) emptyList() else listOf(
                    DictionaryCandidate("候補", selection = CandidateSelection(0, listOf(
                        SelectedCandidateOrigin("system", 1, false, query.readingKey, "候補", null),
                    ))),
                )
            }
            val engine = BasicSkkEngine(dictionary, RegistrationPolicy(true, seed.toLong()),
                learningEnabled = true, deletionEnabled = true)
            val outstanding = mutableListOf<CandidateDeletionRequest>()
            val actions = listOf(
                BasicSkkAction.Text("Nihon "), BasicSkkAction.Text("Michi "),
                BasicSkkAction.Text("X"), BasicSkkAction.Text("y"), BasicSkkAction.Text("n"),
                BasicSkkAction.Text("Xyignored"), BasicSkkAction.Text("ma"),
                BasicSkkAction.Enter, BasicSkkAction.Cancel, BasicSkkAction.Kana,
                BasicSkkAction.Backspace, BasicSkkAction.Left, BasicSkkAction.Right,
                BasicSkkAction.Text(" "),
            )
            repeat(700) {
                if (random.nextInt(15) == 0) {
                    engine.resetComposition()
                    outstanding.forEach { request ->
                        assertFalse(engine.completeCandidateDeletion(CandidateDeletionCompletion(
                            request.token, CandidateDeletionOutcome.Applied,
                        )).handled)
                    }
                    outstanding.clear()
                } else if (outstanding.isNotEmpty() && random.nextInt(5) == 0) {
                    val request = outstanding[random.nextInt(outstanding.size)]
                    val outcome = when (random.nextInt(4)) {
                        0 -> CandidateDeletionOutcome.Applied
                        1 -> CandidateDeletionOutcome.SavedButNotApplied
                        2 -> CandidateDeletionOutcome.Failed(CandidateDeletionFailure.CONFLICT)
                        else -> CandidateDeletionOutcome.Failed(CandidateDeletionFailure.CAPACITY)
                    }
                    val completed = engine.completeCandidateDeletion(CandidateDeletionCompletion(request.token, outcome))
                    assertNull(completed.commit)
                    assertFalse(engine.completeCandidateDeletion(CandidateDeletionCompletion(request.token, outcome)).handled)
                } else {
                    val before = engine.currentView.deletion
                    val result = engine.dispatch(actions[random.nextInt(actions.size)])
                    if (before != null) {
                        assertNull("確認中に本文を確定しました: seed=$seed", result.commit)
                        assertTrue(result.effects.none { it is BasicSkkEffect.LearnCandidate || it is BasicSkkEffect.SaveRegistration })
                    }
                    result.effects.filterIsInstance<BasicSkkEffect.DeleteCandidate>().forEach { effect ->
                        assertEquals(seed.toLong(), effect.request.token.sessionGeneration)
                        assertTrue(outstanding.none { it.token == effect.request.token })
                        outstanding += effect.request
                    }
                    result.effects.filterIsInstance<BasicSkkEffect.SaveRegistration>().forEach { effect ->
                        engine.completeRegistration(RegistrationSaveCompletion(effect.request.token,
                            RegistrationSaveOutcome.Failed(RegistrationSaveFailure.GENERAL)))
                    }
                }
                val view = engine.currentView
                view.deletion?.let {
                    assertTrue(it.originCount > 0)
                    assertEquals(it.originCount, it.personalOriginCount + it.systemOriginCount)
                    assertNotNull(view.candidate ?: view.registration?.innerCandidate)
                }
                assertTrue(engine.state.registrationDepth in 0..16)
            }
        }
    }
}
