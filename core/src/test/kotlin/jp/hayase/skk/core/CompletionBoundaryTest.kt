package jp.hayase.skk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionBoundaryTest {
    private class Dictionary : BasicSkkDictionary {
        var completions = listOf("こども")
        val completionQueries = mutableListOf<CompletionQuery>()

        override fun lookup(query: DictionaryQuery): List<DictionaryCandidate> = emptyList()

        override fun complete(query: CompletionQuery): List<String> {
            completionQueries += query
            return completions.filter { it.startsWith(query.prefix) }.take(query.limit)
        }
    }

    @Test fun `補完巡回を登録開始で受諾した後は子の空本文復帰で古い巡回を復元しない`() {
        val dictionary = Dictionary()
        val engine = BasicSkkEngine(
            dictionary = dictionary,
            registrationPolicy = RegistrationPolicy(enabled = true),
        )

        engine.dispatch(BasicSkkAction.Text("Michi Ko"))
        assertEquals("こども", engine.dispatch(BasicSkkAction.CompleteForward).view.registration?.innerComposing)
        engine.dispatch(BasicSkkAction.Text(" "))
        assertEquals(2, engine.state.registrationDepth)

        val returned = engine.dispatch(BasicSkkAction.Enter)
        assertEquals(1, engine.state.registrationDepth)
        assertEquals("こども", returned.view.registration?.innerComposing)

        dictionary.completions = listOf("こどもご")
        val fresh = engine.dispatch(BasicSkkAction.CompleteForward)
        assertEquals("こどもご", fresh.view.registration?.innerComposing)
        assertEquals("こども", dictionary.completionQueries.last().prefix)
    }

    @Test fun `動的補完は個人辞書に候補がない場合システム見出しを提案しない`() {
        val dictionary = object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery): List<DictionaryCandidate> = emptyList()
            override fun complete(query: CompletionQuery): List<String> = when (query.scope) {
                CompletionScope.ALL -> listOf("にほん")
                CompletionScope.PERSONAL_ONLY -> emptyList()
            }
        }
        val engine = BasicSkkEngine(
            dictionary = dictionary,
            registrationPolicy = RegistrationPolicy(),
            completionConfig = CompletionConfig(dynamicEnabled = true),
        )

        val result = engine.dispatch(BasicSkkAction.Text("Ni"))

        assertEquals("に", result.view.composing)
        assertNull(result.view.completion)
        assertEquals("にほん", engine.dispatch(BasicSkkAction.CompleteForward).view.composing)
    }
}
