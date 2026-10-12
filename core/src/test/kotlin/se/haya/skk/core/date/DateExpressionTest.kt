package se.haya.skk.core.date

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DateExpressionTest {
    private val gregorian = "(skk-current-date (lambda (date-information format gengo and-time) (skk-default-current-date date-information nil 0 nil 0 0 0 and-time)))"
    private val iso = "(skk-current-date (lambda (date-information format gengo and-time) (format-time-string \"%Y-%m-%d\")))"

    @Test fun `二つの固定形式だけを認識して同じ式へ正規化する`() {
        for ((source, format) in listOf(gregorian to DateFormat.GREGORIAN_JAPANESE, iso to DateFormat.ISO)) {
            val expression = DateExpression.parse(" $source ")
            assertEquals(DateExpression(format = format), expression)
            assertEquals(source, expression!!.canonicalSource())
        }
    }

    @Test fun `固定形式に追加した実行式や引数や書式を拒否する`() {
        val altered = listOf(
            gregorian.replace("nil 0 nil", "nil 1 nil"),
            gregorian.replace("(skk-default-current-date", "(synthetic-date-fixture"),
            gregorian.dropLast(1) + " nil nil t)",
            gregorian.dropLast(2) + " (synthetic-date-fixture)))",
            gregorian.replace("date-information nil", "(synthetic-date-fixture) nil"),
            iso.replace("%Y-%m-%d", "%Y/%m/%d"),
            iso.replace("(format-time-string", "(synthetic-date-fixture"),
            iso.replace("(format-time-string", " (format-time-string"),
            iso + " (synthetic-date-fixture)",
            "'$iso",
        )
        for (source in altered) assertNull(source, DateExpression.parse(source))
    }

    @Test fun `固定形式の式に暦日移動と時刻を組み合わせない`() {
        assertThrows(IllegalArgumentException::class.java) { DateExpression(1, format = DateFormat.ISO) }
        assertThrows(IllegalArgumentException::class.java) { DateExpression(andTime = true, format = DateFormat.GREGORIAN_JAPANESE) }
        assertThrows(IllegalArgumentException::class.java) { DateExpression(format = DateFormat.DDSKK_ERA_FULLWIDTH) }
    }

    @Test fun `現在日付の許可された形式だけを解析する`() {
        assertEquals(DateExpression(), DateExpression.parse("(skk-current-date)"))
        assertEquals(DateExpression(), DateExpression.parse("(skk-current-date nil)"))
        assertEquals(DateExpression(), DateExpression.parse("(skk-current-date nil nil)"))
        assertEquals(DateExpression(), DateExpression.parse(" \n(skk-current-date\t nil nil nil) "))
        assertEquals(DateExpression(andTime = true), DateExpression.parse("(skk-current-date nil nil t)"))
    }

    @Test fun `相対日付は符号付き整数と両端を許可する`() {
        for (offset in listOf(-36600, -1, 0, 1, 36600)) {
            assertEquals(DateExpression(offset), DateExpression.parse("(skk-relative-date nil nil nil :dd $offset)"))
        }
        assertEquals(DateExpression(1), DateExpression.parse("(skk-relative-date nil nil nil :dd +1)"))
    }

    @Test fun `未知の構文と実行可能な式を拒否する`() {
        val rejected = listOf(
            "", "(synthetic-date-fixture)", "skk-current-date", "(skk-current-date nil t)",
            "(skk-current-date nil nil nil nil)",
            "(skk-current-date t nil nil)", "(skk-current-date nil \"%Y\" nil)",
            "(skk-current-date (lambda () nil) nil nil)", "'(skk-current-date)",
            "(skk-current-date nil nil 't)", "(skk-current-date);synthetic",
            "(skk-current-date) (synthetic-date-fixture)", "(skk-current-date", "(skk-current-date))",
            "(skk-relative-date nil nil t :dd 1)", "(skk-relative-date nil nil nil :mm 1)",
            "(skk-relative-date nil nil nil :dd 1 :dd 2)", "(skk-relative-date)",
            "(skk-relative-date nil nil nil :dd 36601)", "(skk-relative-date nil nil nil :dd -36601)",
            "(skk-relative-date nil nil nil :dd 999999999999999999999999)",
            "(skk-relative-date nil nil nil :dd 1.0)", "(skk-relative-date nil nil nil :dd +)",
            "(skk-relative-date nil nil nil :dd １)",
        )
        for (source in rejected) assertNull(source, DateExpression.parse(source))
    }

    @Test fun `入力長を制限して許可済みの意味だけを正規化する`() {
        val source = "(skk-current-date)"
        assertEquals(DateExpression(), DateExpression.parse(source.padEnd(256)))
        assertNull(DateExpression.parse(source.padEnd(257)))
        assertEquals("(skk-current-date)", DateExpression.parse("(skk-current-date nil nil nil)")!!.canonicalSource())
        assertEquals("(skk-current-date nil nil t)", DateExpression.parse("(skk-current-date nil nil t)")!!.canonicalSource())
        assertEquals("(skk-relative-date nil nil nil :dd 1)", DateExpression.parse("(skk-relative-date nil nil nil :dd +01)")!!.canonicalSource())
    }
}
