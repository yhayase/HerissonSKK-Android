package se.haya.skk.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/** 未受諾の動的提案が、同じ操作列の確定・登録・読みへ影響しないことを比較します。 */
class CompletionInvariantTest {
    @Test fun `明示受諾しない動的補完は無効時と同じ入力結果を保つ`() {
        val dictionary = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery): List<DictionaryCandidate> =
                if (query.readingKey.length % 3 == 0) emptyList()
                else listOf(DictionaryCandidate("候補"), DictionaryCandidate("別候補"))
            override fun complete(query: CompletionQuery): List<String> =
                if (query.prefix.isEmpty()) emptyList() else listOf(query.prefix + "ほん")
        }
        val actions = listOf(
            BasicSkkAction.Text("N"), BasicSkkAction.Text("K"), BasicSkkAction.Text("i"),
            BasicSkkAction.Text("a"), BasicSkkAction.Text("n"), BasicSkkAction.Text(" "),
            BasicSkkAction.Text("q"), BasicSkkAction.Text("/"), BasicSkkAction.Text("語"),
            BasicSkkAction.Enter, BasicSkkAction.Cancel, BasicSkkAction.Kana,
            BasicSkkAction.Backspace, BasicSkkAction.Delete, BasicSkkAction.Left,
            BasicSkkAction.Home, BasicSkkAction.End, BasicSkkAction.Halfwidth,
            BasicSkkAction.CompleteForward, BasicSkkAction.CompleteBackward,
        )
        repeat(24) { seed ->
            val random = Random(seed)
            fun engine(dynamic: Boolean) = BasicSkkEngine(dictionary,
                RegistrationPolicy(enabled = true, sessionGeneration = seed.toLong()),
                learningEnabled = true, completionConfig = CompletionConfig(dynamicEnabled = dynamic))
            val plain = engine(false)
            val proposed = engine(true)
            repeat(700) { step ->
                val context = "seed=$seed step=$step"
                if (random.nextInt(35) == 0) {
                    assertEquals(context, plain.resetComposition(), proposed.resetComposition())
                } else {
                    val action = actions[random.nextInt(actions.size)]
                    val expected = plain.dispatch(action)
                    val actual = proposed.dispatch(action)
                    assertEquals(context, expected, actual.copy(view = actual.view.copy(completion = null)))
                    assertEquals(context, plain.state, proposed.state)
                }
            }
        }
    }
}
