package se.haya.skk.core.romaji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RomanRuleSetTest {
    @Test fun `コンパイル済み規則を複数Romanizerで再利用できる`() {
        val rules = RomanRuleSet.compile(listOf(
            RomajiRule("ka", "か゚"),
            RomajiRule("n", "", terminalOutput = "ん"),
        ))

        assertEquals(setOf('k', 'a', 'n'), rules.inputCharacters)
        assertTrue(rules.accepts('k'))
        assertFalse(rules.accepts(';'))
        assertEquals("か゚", Romanizer(rules).feed("ka"))
        assertEquals("か゚", Romanizer(rules).feed("ka"))
        assertSame(RomanRuleSet.standard, RomanRuleSet.standard)
    }

    @Test fun `ASCII図形文字を入力規則として許可する`() {
        val rules = RomanRuleSet.compile(listOf(
            RomajiRule(";", "っ"),
            RomajiRule("x;", ";"),
        ))
        val romanizer = Romanizer(rules)

        assertTrue(rules.accepts(';'))
        assertEquals("っ", romanizer.feed(";"))
        assertEquals(";", romanizer.feed("x;"))
    }

    @Test fun `空の残余と正当な接頭辞共有と終端規則を扱う`() {
        val rules = RomanRuleSet.compile(listOf(
            RomajiRule("a", "A"),
            RomajiRule("ab", "B"),
            RomajiRule("n", "", terminalOutput = "ん"),
        ))
        val short = Romanizer(rules)
        short.feed("a")
        assertTrue(short.canFinishPending())
        assertEquals("A", short.finish())

        val terminal = Romanizer(rules)
        terminal.feed("n")
        assertTrue(terminal.canFinishPending())
        assertEquals("ん", terminal.finish())

        val incomplete = Romanizer(RomanRuleSet.compile(listOf(RomajiRule("ka", "か"))))
        incomplete.feed("k")
        assertFalse(incomplete.canFinishPending())
        assertEquals("k", incomplete.finish())
    }

    @Test fun `規則数上限の長い残余鎖を再帰なしで検証し実行する`() {
        val tokens = List(RomanRuleSet.MAX_RULES) { token(it) }
        val rules = tokens.mapIndexed { index, input ->
            RomajiRule(input, "x", remaining = tokens.getOrElse(index + 1) { "" })
        }
        val compiled = RomanRuleSet.compile(rules)

        assertEquals(RomanRuleSet.MAX_RULES, compiled.ruleCount)
        assertEquals("x".repeat(RomanRuleSet.MAX_RULES), Romanizer(compiled).feed(tokens.first()))
    }

    @Test fun `予測は出力後の残余を再展開せず標準のkとnを有限に列挙する`() {
        val doubledOnly = RomanRuleSet.compile(listOf(RomajiRule("kk", "っ", "k")))
        assertEquals(
            RomanPredictionExpansion.Complete(listOf("っ")),
            doubledOnly.possibleOutputs("k"),
        )

        val k = RomanRuleSet.standard.possibleOutputs("k") as RomanPredictionExpansion.Complete
        assertTrue(listOf("か", "き", "く", "け", "こ", "っ").all(k.outputs::contains))
        val n = RomanRuleSet.standard.possibleOutputs("n") as RomanPredictionExpansion.Complete
        assertTrue(listOf("な", "に", "ぬ", "ね", "の", "ん").all(n.outputs::contains))
    }

    @Test fun `予測は空出力の長い残余鎖を反復処理し未検証循環と件数超過を未判定にする`() {
        val tokens = List(RomanRuleSet.MAX_RULES) { token(it) }
        val chain = RomanRuleSet.compile(tokens.mapIndexed { index, input ->
            if (index == tokens.lastIndex) RomajiRule(input, "終")
            else RomajiRule(input, "", remaining = tokens[index + 1])
        })
        assertEquals(
            RomanPredictionExpansion.Complete(listOf("終")),
            chain.possibleOutputs(tokens.first()),
        )

        val hiddenCycle = RomanRuleSet.compile(listOf(RomajiRule("aa", "", remaining = "a")))
        assertEquals(RomanPredictionExpansion.Indeterminate, hiddenCycle.possibleOutputs("a"))

        val converging = RomanRuleSet.compile(listOf(
            RomajiRule("a", "", remaining = "x"),
            RomajiRule("ab", "", remaining = "x"),
            RomajiRule("xa", "か"),
        ))
        assertEquals(
            RomanPredictionExpansion.Complete(listOf("か")),
            converging.possibleOutputs("a"),
        )

        val branches = RomanRuleSet.compile((0..64).map { index ->
            RomajiRule("a${index.toString(36).padStart(2, '0')}", "候$index")
        })
        assertEquals(RomanPredictionExpansion.Indeterminate, branches.possibleOutputs("a", 64))
    }

    @Test fun `4096段の残余循環をstack overflowせず拒否する`() {
        val tokens = List(RomanRuleSet.MAX_RULES) { token(it) }
        expectInvalid {
            RomanRuleSet.compile(tokens.mapIndexed { index, input ->
                RomajiRule(input, "x", remaining = tokens[(index + 1) % tokens.size])
            })
        }
    }

    @Test fun `件数長さ文字種Unicode終端不変条件を適用前に拒否する`() {
        expectInvalid { RomanRuleSet.compile(emptyList()) }
        expectInvalid {
            RomanRuleSet.compile(List(RomanRuleSet.MAX_RULES + 1) { RomajiRule(token(it), "x") })
        }
        expectInvalid { RomanRuleSet.compile(listOf(RomajiRule("a".repeat(17), "x"))) }
        expectInvalid { RomanRuleSet.compile(listOf(RomajiRule("a", "x", remaining = "b".repeat(17)))) }
        expectInvalid { RomanRuleSet.compile(listOf(RomajiRule("a", "x".repeat(65)))) }
        expectInvalid { RomanRuleSet.compile(listOf(RomajiRule("a", "\uD800"))) }
        expectInvalid { RomanRuleSet.compile(listOf(RomajiRule("a", "", terminalOutput = "\uDC00"))) }
        expectInvalid { RomanRuleSet.compile(listOf(RomajiRule("a b", "x"))) }
        expectInvalid { RomanRuleSet.compile(listOf(RomajiRule("a", ""))) }
        expectInvalid { RomanRuleSet.compile(listOf(RomajiRule("a", "x", terminalOutput = "A"))) }
        expectInvalid { RomanRuleSet.compile(listOf(RomajiRule("a", "", "b", terminalOutput = "A"))) }
        expectInvalid { RomanRuleSet.compile(listOf(RomajiRule("a", "A"), RomajiRule("a", "B"))) }
    }

    private fun token(value: Int): String {
        val radix = 94
        return buildString(2) {
            append((0x21 + value / radix).toChar())
            append((0x21 + value % radix).toChar())
        }
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("不正な規則を受理しました")
        } catch (_: IllegalArgumentException) {
            // 期待どおりです。
        }
    }
}
