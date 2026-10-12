package se.haya.skk.core.date

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateRendererTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test fun `式の固定形式を呼び出し側の既定形式より優先する`() {
        val instant = Instant.parse("2026-10-10T00:05:09Z")
        assertEquals("2026年10月10日(土)", DateRenderer.render(DateExpression(format = DateFormat.GREGORIAN_JAPANESE), instant, tokyo, DateFormat.ISO))
        assertEquals("2026-10-10", DateRenderer.render(DateExpression(format = DateFormat.ISO), instant, tokyo, DateFormat.GREGORIAN_JAPANESE))
    }

    @Test fun `三つの固定形式で表示する`() {
        val instant = Instant.parse("2026-10-10T00:05:09Z")
        assertEquals("令和８年１０月１０日(土)", DateRenderer.render(DateExpression(), instant, tokyo))
        assertEquals("2026年10月10日(土)", DateRenderer.render(DateExpression(), instant, tokyo, DateFormat.GREGORIAN_JAPANESE))
        assertEquals("2026-10-10", DateRenderer.render(DateExpression(), instant, tokyo, DateFormat.ISO))
        assertEquals("令和８年１０月１０日(土)０９時０５分０９秒", DateRenderer.render(DateExpression(andTime = true), instant, tokyo))
        assertEquals("2026年10月10日(土)09時05分09秒", DateRenderer.render(DateExpression(andTime = true), instant, tokyo, DateFormat.GREGORIAN_JAPANESE))
        assertEquals("2026-10-10T09:05:09", DateRenderer.render(DateExpression(andTime = true), instant, tokyo, DateFormat.ISO))
    }

    @Test fun `時差と日付境界を呼び出しごとに適用する`() {
        val instant = Instant.parse("2026-10-09T15:00:00Z")
        assertEquals("2026-10-10", DateRenderer.render(DateExpression(), instant, tokyo, DateFormat.ISO))
        assertEquals("2026-10-09", DateRenderer.render(DateExpression(), instant, ZoneOffset.UTC, DateFormat.ISO))
        assertEquals("2026-10-10", DateRenderer.render(DateExpression(), Clock.fixed(instant, tokyo), DateFormat.ISO))
    }

    @Test fun `相対日付は閏日と夏時間を暦日で移動する`() {
        assertEquals("2024-02-29", iso("2024-02-28T12:00:00Z", 1))
        assertEquals("2024-02-28", iso("2024-03-01T12:00:00Z", -2))
        val newYork = ZoneId.of("America/New_York")
        assertEquals("2024年3月10日(日)23時30分00秒", DateRenderer.render(DateExpression(1, true), Instant.parse("2024-03-10T04:30:00Z"), newYork, DateFormat.GREGORIAN_JAPANESE))
    }

    @Test fun `近代元号の開始日と前日と元年を表示する`() {
        val boundaries = listOf(
            Triple("1873-01-01", "明治６年１月１日(水)", "1872年12月31日(火)"),
            Triple("1912-07-30", "大正元年７月３０日(火)", "明治４５年７月２９日(月)"),
            Triple("1926-12-25", "昭和元年１２月２５日(土)", "大正１５年１２月２４日(金)"),
            Triple("1989-01-08", "平成元年１月８日(日)", "昭和６４年１月７日(土)"),
            Triple("2019-05-01", "令和元年５月１日(水)", "平成３１年４月３０日(火)"),
        )
        for ((date, first, previous) in boundaries) {
            val instant = Instant.parse("${date}T12:00:00Z")
            assertEquals(first, DateRenderer.render(DateExpression(), instant, ZoneOffset.UTC))
            assertEquals(previous, DateRenderer.render(DateExpression(-1), instant, ZoneOffset.UTC))
        }
    }

    @Test fun `年の範囲外と相対移動の範囲外は結果を返さない`() {
        assertNull(DateRenderer.render(DateExpression(-1), Instant.parse("0001-01-01T12:00:00Z"), ZoneOffset.UTC))
        assertNull(DateRenderer.render(DateExpression(1), Instant.parse("9999-12-31T12:00:00Z"), ZoneOffset.UTC))
        assertNull(DateRenderer.render(DateExpression(), Instant.MIN, ZoneOffset.UTC))
        assertNull(DateRenderer.render(DateExpression(36601), Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC))
        assertEquals("0001-01-01", iso("0001-01-01T12:00:00Z", 0))
        assertEquals("9999-12-31", iso("9999-12-31T12:00:00Z", 0))
    }

    private fun iso(instant: String, offset: Int): String? =
        DateRenderer.render(DateExpression(offset), Instant.parse(instant), ZoneOffset.UTC, DateFormat.ISO)
}
