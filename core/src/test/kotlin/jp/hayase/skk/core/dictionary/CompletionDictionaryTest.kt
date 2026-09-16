package jp.hayase.skk.core.dictionary

import jp.hayase.skk.core.BasicSkkDictionary
import jp.hayase.skk.core.CompletionException
import jp.hayase.skk.core.CompletionFailure
import jp.hayase.skk.core.CompletionQuery
import jp.hayase.skk.core.CompletionScope
import jp.hayase.skk.core.DictionaryCandidate
import jp.hayase.skk.core.numeric.NumericSkkDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionDictionaryTest {
    @Test fun `全辞書は個人とシステム設定順で重複を除き個人限定はシステムを検索しない`() {
        val personal = source("personal", 4, listOf("にほんご", "にほん"))
        val first = source("first", 2, listOf("にほん", "にほんかい"))
        val second = source("second", 3, listOf("にほんじん"))
        val disabled = source("disabled", 1, listOf("にほんし"), enabled = false)
        val dictionary = CompositeSkkDictionary(personal, listOf(first, disabled, second))

        assertEquals(
            listOf("にほん", "にほんご", "にほんかい", "にほんじん"),
            dictionary.complete(CompletionQuery("に")),
        )
        assertEquals(
            listOf("にほん", "にほんご"),
            dictionary.complete(CompletionQuery("に", scope = CompletionScope.PERSONAL_ONLY)),
        )
        assertTrue(
            CompositeSkkDictionary(systems = listOf(first))
                .complete(CompletionQuery("に", scope = CompletionScope.PERSONAL_ONLY)).isEmpty(),
        )
    }

    @Test fun `全候補を抑止したシステム見出しだけを除外する`() {
        val entry = SkkDictionaryEntry("かなもの", listOf(
            SkkDictionaryCandidate("金物"),
            SkkDictionaryCandidate("鉄物", okuriCondition = "る"),
        ))
        val source = SkkDictionarySource("system", 1, listOf(entry))
        val firstOnly = SuppressedDictionaryCandidate("system", "かなもの", "金物")
        val all = listOf(
            firstOnly,
            SuppressedDictionaryCandidate("system", "かなもの", "鉄物", "る"),
        )

        assertEquals(
            listOf("かなもの"),
            CompositeSkkDictionary(systems = listOf(source), suppressions = listOf(firstOnly))
                .complete(CompletionQuery("かな")),
        )
        assertTrue(
            CompositeSkkDictionary(systems = listOf(source), suppressions = all)
                .complete(CompletionQuery("かな")).isEmpty(),
        )
    }

    @Test fun `abbrevはASCIIと大小文字を保ち非ASCII見出しを除外する`() {
        val dictionary = CompositeSkkDictionary(source("personal", 1, listOf(
            "API", "ApiClient", "A日本", "apiLower",
        )))

        assertEquals(
            listOf("API", "ApiClient"),
            dictionary.complete(CompletionQuery("A", abbrev = true)),
        )
    }

    @Test fun `通常補完と動的補完向け検索は送りありキーを除外する`() {
        val personal = source("personal", 1, listOf("かき", "かk"))
        val system = source("system", 1, listOf("かな", "かn"))
        val dictionary = CompositeSkkDictionary(personal, listOf(system))

        assertEquals(listOf("かき", "かな"), dictionary.complete(CompletionQuery("か")))
        assertEquals(
            listOf("かき"),
            dictionary.complete(CompletionQuery("か", scope = CompletionScope.PERSONAL_ONLY)),
        )
        assertEquals(
            listOf("ApiClient", "ApiClientt"),
            CompositeSkkDictionary(source("abbrev", 1, listOf("ApiClient", "ApiClientt")))
                .complete(CompletionQuery("Api", abbrev = true)),
        )
    }

    @Test fun `64件で正常終了し65件目の不正な結果を検査しない`() {
        val accepted = List(64) { "a%03d".format(it) }
        val tooLongAfterCap = "a999" + "x".repeat(CompletionQuery.MAX_RESULT_CHARS)
        val dictionary = CompositeSkkDictionary(source("personal", 1, accepted + tooLongAfterCap))

        assertEquals(accepted, dictionary.complete(CompletionQuery("a")))
        assertEquals(7, dictionary.complete(CompletionQuery("a", limit = 7)).size)
    }

    @Test fun `結果文字数と検査数の境界を区別して型付き失敗にする`() {
        fun fixedLengthKey(index: Int, length: Int): String =
            "a%03d".format(index) + "x".repeat(length - 5) + "0"
        val exact = CompositeSkkDictionary(source("personal", 1,
            List(16) { fixedLengthKey(it, CompletionQuery.MAX_RESULT_CHARS) }))
        assertEquals(
            CompletionQuery.MAX_TOTAL_RESULT_CHARS,
            exact.complete(CompletionQuery("a")).sumOf(String::length),
        )
        val totalOver = CompositeSkkDictionary(source("personal", 1,
            List(17) { fixedLengthKey(it, CompletionQuery.MAX_RESULT_CHARS) }))
        assertEquals(CompletionFailure.RESULT_LIMIT, assertThrows(CompletionException::class.java) {
            totalOver.complete(CompletionQuery("a"))
        }.failure)
        val oneOver = CompositeSkkDictionary(source("personal", 1,
            listOf(fixedLengthKey(0, CompletionQuery.MAX_RESULT_CHARS + 1))))
        assertEquals(CompletionFailure.RESULT_LIMIT, assertThrows(CompletionException::class.java) {
            oneOver.complete(CompletionQuery("a"))
        }.failure)

        fun filteredKeys(count: Int) = List(count) { "a日本%05d".format(it) }
        assertTrue(CompositeSkkDictionary(source("personal", 1,
            filteredKeys(CompletionQuery.MAX_WORK_ITEMS)))
            .complete(CompletionQuery("a", abbrev = true)).isEmpty())
        assertEquals(CompletionFailure.WORK_LIMIT, assertThrows(CompletionException::class.java) {
            CompositeSkkDictionary(source("personal", 1,
                filteredKeys(CompletionQuery.MAX_WORK_ITEMS + 1)))
                .complete(CompletionQuery("a", abbrev = true))
        }.failure)
    }

    @Test fun `一見出しの抑止候補検査も共通作業量上限で中止する`() {
        val candidateCount = CompletionQuery.MAX_WORK_ITEMS
        val candidates = List(candidateCount) { index -> SkkDictionaryCandidate("候補$index") }
        val entry = SkkDictionaryEntry("かな", candidates)
        val suppressions = candidates.map { candidate ->
            SuppressedDictionaryCandidate("system", "かな", candidate.text)
        }
        val dictionary = CompositeSkkDictionary(
            systems = listOf(SkkDictionarySource("system", 1, listOf(entry))),
            suppressions = suppressions,
        )

        assertEquals(CompletionFailure.WORK_LIMIT, assertThrows(CompletionException::class.java) {
            dictionary.complete(CompletionQuery("か"))
        }.failure)
    }

    @Test fun `不正な問合せは本文を含まない型付き例外にする`() {
        val dictionary = CompositeSkkDictionary(source("personal", 1, listOf("ひみつ候補")))
        val invalid = listOf(
            CompletionQuery("秘匿入力", limit = 0),
            CompletionQuery("秘匿入力", limit = CompletionQuery.MAX_RESULTS + 1),
            CompletionQuery("x".repeat(CompletionQuery.MAX_PREFIX_CHARS + 1)),
            CompletionQuery("\ud800"),
        )
        invalid.forEach { query ->
            val error = assertThrows(CompletionException::class.java) { dictionary.complete(query) }
            assertEquals(CompletionFailure.INVALID_INPUT, error.failure)
            assertFalse(error.toString().contains("ひみつ"))
            assertFalse(error.toString().contains(query.prefix))
        }
    }

    @Test fun `公開後は入力リスト変更の影響を受けず数値辞書も同じ問合せを委譲する`() {
        val entries = mutableListOf(
            SkkDictionaryEntry("にほん", listOf(SkkDictionaryCandidate("日本"))),
        )
        val composite = CompositeSkkDictionary(SkkDictionarySource("personal", 6, entries))
        entries.clear()
        assertEquals(listOf("にほん"), composite.complete(CompletionQuery("に")))

        var received: CompletionQuery? = null
        val raw = object : BasicSkkDictionary {
            override fun lookup(query: jp.hayase.skk.core.DictionaryQuery): List<DictionaryCandidate> = emptyList()
            override fun complete(query: CompletionQuery): List<String> {
                received = query
                return listOf("だいすう")
            }
        }
        val query = CompletionQuery("だい", scope = CompletionScope.PERSONAL_ONLY)
        assertEquals(listOf("だいすう"), NumericSkkDictionary(raw).complete(query))
        assertTrue(received === query)
    }

    private fun source(
        id: String,
        generation: Long,
        keys: List<String>,
        enabled: Boolean = true,
    ) = SkkDictionarySource(
        id,
        generation,
        keys.map { key -> SkkDictionaryEntry(key, listOf(SkkDictionaryCandidate("候補"))) },
        enabled,
    )
}
