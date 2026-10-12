package se.haya.skk.core.date

import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class DateFormat { DDSKK_ERA_FULLWIDTH, GREGORIAN_JAPANESE, ISO }

object DateRenderer {
    private data class Era(val start: LocalDate, val firstYear: Int, val name: String)

    // 明治初期の旧暦との対応は扱わず、グレゴリオ暦の採用以降を対象にします。
    private val eras = listOf(
        Era(LocalDate.of(1873, 1, 1), 1868, "明治"),
        Era(LocalDate.of(1912, 7, 30), 1912, "大正"),
        Era(LocalDate.of(1926, 12, 25), 1926, "昭和"),
        Era(LocalDate.of(1989, 1, 8), 1989, "平成"),
        Era(LocalDate.of(2019, 5, 1), 2019, "令和"),
    )
    private val weekdays = listOf("月", "火", "水", "木", "金", "土", "日")

    /** 日付の範囲外や暦日移動の失敗は候補を生成せずに返します。 */
    fun render(
        expression: DateExpression,
        instant: Instant,
        zone: ZoneId,
        format: DateFormat = DateFormat.DDSKK_ERA_FULLWIDTH,
    ): String? {
        if (expression.dayOffset !in -DateExpression.MAX_DAY_OFFSET..DateExpression.MAX_DAY_OFFSET) return null
        return try {
            val local = instant.atZone(zone)
            if (local.year !in 1..9999) return null
            val date = local.toLocalDate().plusDays(expression.dayOffset.toLong())
            if (date.year !in 1..9999) return null

            val effectiveFormat = expression.format ?: format
            val era = if (effectiveFormat == DateFormat.DDSKK_ERA_FULLWIDTH) eras.lastOrNull { date >= it.start } else null
            val dateText = when (effectiveFormat) {
                DateFormat.ISO -> "${padded(date.year, 4)}-${padded(date.monthValue)}-${padded(date.dayOfMonth)}"
                DateFormat.GREGORIAN_JAPANESE, DateFormat.DDSKK_ERA_FULLWIDTH -> {
                    val year = if (era == null) date.year.toString() else {
                        val eraYear = date.year - era.firstYear + 1
                        era.name + if (eraYear == 1) "元" else fullwidth(eraYear.toString())
                    }
                    val month = date.monthValue.toString()
                    val day = date.dayOfMonth.toString()
                    val numericDate = if (era == null) "${year}年${month}月${day}日" else {
                        "${year}年${fullwidth(month)}月${fullwidth(day)}日"
                    }
                    "$numericDate(${weekdays[date.dayOfWeek.value - 1]})"
                }
            }
            if (!expression.andTime) return dateText
            val hour = padded(local.hour)
            val minute = padded(local.minute)
            val second = padded(local.second)
            if (effectiveFormat == DateFormat.ISO) "$dateText${'T'}$hour:$minute:$second" else {
                val time = "${hour}時${minute}分${second}秒"
                dateText + if (era == null) time else fullwidth(time)
            }
        } catch (_: DateTimeException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun render(
        expression: DateExpression,
        clock: Clock,
        format: DateFormat = DateFormat.DDSKK_ERA_FULLWIDTH,
    ): String? = render(expression, clock.instant(), clock.zone, format)

    private fun padded(value: Int, length: Int = 2): String = value.toString().padStart(length, '0')

    private fun fullwidth(value: String): String = value.map {
        if (it in '0'..'9') (it.code + ('０'.code - '0'.code)).toChar() else it
    }.joinToString("")
}
