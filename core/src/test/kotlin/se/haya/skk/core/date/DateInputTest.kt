package se.haya.skk.core.date

import java.time.*
import org.junit.Assert.*
import org.junit.Test
import se.haya.skk.core.*
import se.haya.skk.core.dictionary.*

class DateInputTest {
    private class MovingClock(var value: Instant) : Clock() {
        override fun instant() = value
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(value, zone)
    }
    private val rawDate = DictionaryCandidate("(skk-current-date)", "独立日付入力注釈", dateExpression = true)

    private fun withDefaults(entries: List<SkkDictionaryEntry> = emptyList()) = CompositeSkkDictionary(
        personal = SkkDictionarySource("date-fixture-personal", 0, entries),
        systems = listOf(DateSkkDictionary.builtinSource()),
    )

    @Test fun `式は変換時に展開し学習先には元の式を保持する`() {
        val clock = MovingClock(Instant.parse("2026-10-10T14:00:00Z"))
        val raw = BasicSkkDictionary { listOf(rawDate) }
        var zone: ZoneId = ZoneId.of("Asia/Tokyo")
        val dictionary = DateSkkDictionary(raw, clock, { zone })
        val query = DictionaryQuery("zz-date-fixture-runtime")
        val first = dictionary.lookup(query).single()
        assertEquals("令和８年１０月１０日(土)", first.text)
        assertEquals(rawDate.text, first.learningTarget!!.templateText)
        assertTrue(first.learningTarget!!.dateExpression)
        clock.value = Instant.parse("2026-10-10T15:00:00Z")
        assertEquals("令和８年１０月１１日(日)", dictionary.lookup(query).single().text)
        assertEquals("令和８年１０月１０日(土)", first.text)
        zone = ZoneOffset.UTC
        assertEquals("令和８年１０月１０日(土)", dictionary.lookup(query).single().text)
    }

    @Test fun `文字列の日付らしい本文は展開せず式だけ予測から除外する`() {
        val literal = rawDate.copy(dateExpression = false)
        val dictionary = DateSkkDictionary(object : BasicSkkDictionary {
            override fun lookup(query: DictionaryQuery) = listOf(literal)
            override fun predict(query: PredictionQuery) = PredictionSearchResult.Ready(listOf(
                PredictionCandidate(rawDate, rawDate.text, PredictionHistoryTarget("zz-date-predict", rawDate.text, null, rawDate.text)),
                PredictionCandidate(literal, literal.text, PredictionHistoryTarget("zz-date-predict", literal.text, null, literal.text)),
            ), hasMore = false)
        })
        assertEquals(literal, dictionary.lookup(DictionaryQuery("zz-date-literal")).single())
        val items = (dictionary.predict(PredictionQuery("zz-date")) as PredictionSearchResult.Ready).items
        assertEquals(listOf(literal), items.map { it.candidate })
    }

    @Test fun `空のtoday辞書で三形式を選択し選んだ形式の式を学習する`() {
        val clock = Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC)
        val dictionary = DateSkkDictionary(withDefaults(), clock, { ZoneOffset.UTC })
        val engine = BasicSkkEngine(dictionary, RegistrationPolicy(), learningEnabled = true)
        val result = engine.dispatch(BasicSkkAction.Text("@"))
        assertEquals(InputPhase.SELECTING, engine.state.phase)
        assertEquals("令和８年１０月１０日(土)", result.view.candidate!!.selected.text)
        assertEquals(3, result.view.candidate!!.total)
        engine.dispatch(BasicSkkAction.Text(" "))
        val committed = engine.dispatch(BasicSkkAction.Kana)
        assertEquals("2026年10月10日(土)", committed.commit)
        val learned = (committed.effects.single() as BasicSkkEffect.LearnCandidate).request
        assertEquals(DateExpression(format = DateFormat.GREGORIAN_JAPANESE).canonicalSource(), learned.candidate.text)
        assertTrue(learned.candidate.dateExpression)
        assertTrue(learned.historyTarget!!.dateExpression)
    }

    @Test fun `学習した形式を先頭にし別形式も引き続き選択できる`() {
        val source = DateExpression(format = DateFormat.ISO).canonicalSource()
        val dictionary = DateSkkDictionary(withDefaults(listOf(SkkDictionaryEntry("today", listOf(
            SkkDictionaryCandidate(source, dateExpression = true),
        )))), Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC), { ZoneOffset.UTC })
        val values = dictionary.lookup(DictionaryQuery("today"))
        assertEquals("2026-10-11", values.first().text)
        assertEquals(3, values.size)
        assertEquals(3, values.map { it.learningTarget!!.templateText }.distinct().size)
    }

    @Test fun `選択済みの日付は日付変更後も同じ本文を確定し次の変換は更新する`() {
        val clock = MovingClock(Instant.parse("2026-10-10T00:00:00Z"))
        val dictionary = DateSkkDictionary(BasicSkkDictionary { listOf(rawDate) }, clock, { ZoneOffset.UTC })
        val engine = BasicSkkEngine(dictionary, RegistrationPolicy(), learningEnabled = true)
        engine.dispatch(BasicSkkAction.Text("@"))
        clock.value = Instant.parse("2026-10-11T00:00:00Z")
        val committed = engine.dispatch(BasicSkkAction.Kana)
        assertEquals("令和８年１０月１０日(土)", committed.commit)
        val learn = (committed.effects.single() as BasicSkkEffect.LearnCandidate).request
        assertEquals(rawDate.text, learn.candidate.text)
        assertTrue(learn.candidate.dateExpression)
        assertEquals("令和８年１０月１１日(日)", engine.dispatch(BasicSkkAction.Text("@")).view.candidate!!.selected.text)
    }

    @Test fun `英数 メールアドレス 読み途中 引用とz記号のアットは日付にしない`() {
        val dictionary = DateSkkDictionary(BasicSkkDictionary { error("辞書検索は不要です") })
        val direct = BasicSkkEngine(dictionary)
        direct.dispatch(BasicSkkAction.ToDirect)
        assertEquals("zz-date@example.invalid", direct.dispatch(BasicSkkAction.Text("zz-date@example.invalid")).commit)
        val reading = BasicSkkEngine(dictionary)
        reading.dispatch(BasicSkkAction.Text("Kana@"))
        assertEquals("かな@", reading.state.reading)
        val quoted = BasicSkkEngine(dictionary)
        assertEquals("@", quoted.dispatch(BasicSkkAction.Text("@", interpretCommands = false)).commit)
        val symbol = BasicSkkEngine(dictionary)
        assertEquals("◎", symbol.dispatch(BasicSkkAction.Text("z@")).commit)
    }
}
