package se.haya.skk.core.numeric

import java.util.Collections
import se.haya.skk.core.dictionary.SkkDictionaryCandidate

enum class NumericDiagnostic {
    TOO_MANY_SPANS,
    INVALID_INPUT,
    INVALID_SPAN,
    UNSUPPORTED_TYPE,
    MISSING_SPAN,
    NUMBER_TOO_LARGE,
    INVALID_NUMBER_FOR_TYPE,
    LOOKUP_FAILED,
    LOOKUP_RESULT_LIMIT,
    VARIANT_LIMIT,
    OUTPUT_LIMIT,
}

class NumericExtraction(
    val lookupKey: String?,
    spans: List<String>,
    diagnostics: List<NumericDiagnostic> = emptyList(),
) {
    val spans: List<String> = Collections.unmodifiableList(ArrayList(spans))
    val diagnostics: List<NumericDiagnostic> = Collections.unmodifiableList(ArrayList(diagnostics))
    val isUsable: Boolean get() = lookupKey != null && diagnostics.isEmpty()
    val hasNumericSpans: Boolean get() = spans.isNotEmpty()
}

class NumericExpansion(
    candidates: List<SkkDictionaryCandidate>,
    diagnostics: List<NumericDiagnostic> = emptyList(),
) {
    val candidates: List<SkkDictionaryCandidate> = Collections.unmodifiableList(ArrayList(candidates))
    val diagnostics: List<NumericDiagnostic> = Collections.unmodifiableList(ArrayList(diagnostics))
}

/** DDSKK 互換の数値検索キー抽出と、安全な候補展開です。任意の式は実行しません。 */
object NumericConversion {
    const val MAX_SPANS = 16
    const val MAX_LOOKUP_RESULTS = 256
    const val MAX_VARIANTS = 256
    const val MAX_TOTAL_OUTPUT_CHARS = 65_536
    const val MAX_POSITIONAL_DIGITS = 20

    fun extract(queryReading: String): NumericExtraction {
        if (!hasValidUtf16(queryReading)) return extractionFailure(NumericDiagnostic.INVALID_INPUT)
        val normalized = buildString(queryReading.length) {
            queryReading.forEach { character ->
                append(if (character in '０'..'９') '0' + (character - '０') else character)
            }
        }
        val degrouped = buildString(normalized.length) {
            normalized.forEachIndexed { index, character ->
                val groupingComma = character == ',' &&
                    index > 0 && normalized[index - 1].isAsciiDigit() &&
                    index + 1 < normalized.length && normalized[index + 1].isAsciiDigit()
                if (!groupingComma) append(character)
            }
        }
        val spans = mutableListOf<String>()
        val lookup = StringBuilder(degrouped.length)
        var index = 0
        while (index < degrouped.length) {
            if (!degrouped[index].isAsciiDigit()) {
                lookup.append(degrouped[index++])
                continue
            }
            val start = index
            while (index < degrouped.length && degrouped[index].isAsciiDigit()) index++
            spans += degrouped.substring(start, index)
            if (spans.size > MAX_SPANS) return extractionFailure(NumericDiagnostic.TOO_MANY_SPANS)
            lookup.append('#')
        }
        return NumericExtraction(lookup.toString(), spans)
    }

    fun expand(
        candidate: SkkDictionaryCandidate,
        spans: List<String>,
        rawLookup: (String) -> List<SkkDictionaryCandidate>,
    ): NumericExpansion {
        if (!hasValidUtf16(candidate.text)) return expansionFailure(NumericDiagnostic.INVALID_INPUT)
        if (spans.size > MAX_SPANS || spans.any { it.isEmpty() || it.any { c -> !c.isAsciiDigit() } }) {
            return expansionFailure(NumericDiagnostic.INVALID_SPAN)
        }
        var variants = listOf("")
        var spanIndex = 0
        var textIndex = 0
        var literalStart = 0
        while (textIndex < candidate.text.length) {
            if (candidate.text[textIndex] != '#' ||
                textIndex + 1 >= candidate.text.length ||
                !candidate.text[textIndex + 1].isAsciiDigit()) {
                textIndex++
                continue
            }
            val withLiteral = appendLiteral(variants, candidate.text.substring(literalStart, textIndex))
            withLiteral.diagnostic?.let { return expansionFailure(it) }
            variants = withLiteral.values
            val digitsStart = textIndex + 1
            var markerEnd = digitsStart
            while (markerEnd < candidate.text.length && candidate.text[markerEnd].isAsciiDigit()) markerEnd++
            if (spanIndex >= spans.size) return expansionFailure(NumericDiagnostic.MISSING_SPAN)
            val type = markerType(candidate.text, digitsStart, markerEnd)
                ?: return expansionFailure(NumericDiagnostic.UNSUPPORTED_TYPE)
            val options = expandMarker(type, spans[spanIndex], rawLookup)
            options.diagnostic?.let { return expansionFailure(it) }
            val crossed = crossProduct(variants, options.values)
            crossed.diagnostic?.let { return expansionFailure(it) }
            variants = crossed.values
            spanIndex++
            textIndex = markerEnd
            literalStart = markerEnd
        }
        val completed = appendLiteral(variants, candidate.text.substring(literalStart))
        completed.diagnostic?.let { return expansionFailure(it) }
        variants = completed.values.distinct()
        return NumericExpansion(variants.map { candidate.copy(text = it) })
    }

    private fun expandMarker(
        type: Int,
        span: String,
        rawLookup: (String) -> List<SkkDictionaryCandidate>,
    ): MarkerExpansion = when (type) {
        0 -> boundedDigits(span) { span }
        1 -> boundedDigits(span) {
            buildString(span.length) { span.forEach { append('０' + (it - '0')) } }
        }
        2 -> boundedDigits(span) {
            buildString(span.length) { span.forEach { append(KANJI_DIGITS[it - '0']) } }
        }
        3 -> positional(span, daiji = false)
        4 -> if (span.length > MAX_TOTAL_OUTPUT_CHARS) {
            MarkerExpansion(diagnostic = NumericDiagnostic.OUTPUT_LIMIT)
        } else {
            expandRawLookup(span, rawLookup)
        }
        5 -> positional(span, daiji = true)
        8 -> grouped(span)
        9 -> if (span.length == 2) {
            MarkerExpansion(listOf("${'０' + (span[0] - '0')}${KANJI_DIGITS[span[1] - '0']}"))
        } else {
            MarkerExpansion(diagnostic = NumericDiagnostic.INVALID_NUMBER_FOR_TYPE)
        }
        else -> MarkerExpansion(diagnostic = NumericDiagnostic.UNSUPPORTED_TYPE)
    }

    private inline fun boundedDigits(span: String, convert: () -> String): MarkerExpansion =
        if (span.length > MAX_TOTAL_OUTPUT_CHARS) {
            MarkerExpansion(diagnostic = NumericDiagnostic.OUTPUT_LIMIT)
        } else {
            MarkerExpansion(listOf(convert()))
        }

    private fun expandRawLookup(
        span: String,
        lookup: (String) -> List<SkkDictionaryCandidate>,
    ): MarkerExpansion {
        val found = try {
            lookup(span)
        } catch (pending: se.haya.skk.core.dictionary.DeferredDictionaryReadException) {
            throw pending
        } catch (_: Exception) {
            return MarkerExpansion(diagnostic = NumericDiagnostic.LOOKUP_FAILED)
        }
        if (found.size > MAX_LOOKUP_RESULTS) {
            return MarkerExpansion(diagnostic = NumericDiagnostic.LOOKUP_RESULT_LIMIT)
        }
        val texts = linkedSetOf<String>()
        found.forEach {
            if (!hasValidUtf16(it.text)) return MarkerExpansion(diagnostic = NumericDiagnostic.INVALID_INPUT)
            texts += it.text
        }
        return MarkerExpansion(if (texts.isEmpty()) listOf(span) else texts.toList())
    }

    private fun positional(span: String, daiji: Boolean): MarkerExpansion {
        if (span.length > MAX_POSITIONAL_DIGITS) {
            return MarkerExpansion(diagnostic = NumericDiagnostic.NUMBER_TOO_LARGE)
        }
        val digits = if (daiji) DAIJI_DIGITS else KANJI_DIGITS
        val smallUnits = if (daiji) DAIJI_SMALL_UNITS else KANJI_SMALL_UNITS
        val firstNonZero = span.indexOfFirst { it != '0' }
        if (firstNonZero < 0) return MarkerExpansion(listOf(if (daiji) "零" else "〇"))
        val normalized = span.substring(firstNonZero)
        val groups = normalized.reversed().chunked(4).map { it.reversed() }.reversed()
        val output = StringBuilder()
        groups.forEachIndexed { index, group ->
            var rendered = renderGroup(group, digits, smallUnits, omitOne = !daiji)
            val largeUnitIndex = groups.lastIndex - index
            if (!daiji && largeUnitIndex == 1 && rendered == "千") rendered = "一千"
            if (rendered.isNotEmpty()) {
                output.append(rendered)
                output.append(if (daiji) DAIJI_LARGE_UNITS[largeUnitIndex] else KANJI_LARGE_UNITS[largeUnitIndex])
            }
        }
        return MarkerExpansion(listOf(output.toString()))
    }

    private fun renderGroup(
        group: String,
        digits: String,
        units: Array<String>,
        omitOne: Boolean,
    ): String = buildString {
        group.forEachIndexed { index, character ->
            val value = character - '0'
            if (value == 0) return@forEachIndexed
            val unitIndex = group.length - 1 - index
            if (!(omitOne && value == 1 && unitIndex > 0)) append(digits[value])
            append(units[unitIndex])
        }
    }

    private fun grouped(span: String): MarkerExpansion {
        val firstNonZero = span.indexOfFirst { it != '0' }
        val normalizedStart = if (firstNonZero < 0) span.lastIndex else firstNonZero
        val digitCount = span.length - normalizedStart
        val outputLength = digitCount.toLong() + (digitCount - 1) / 3
        if (outputLength > MAX_TOTAL_OUTPUT_CHARS) {
            return MarkerExpansion(diagnostic = NumericDiagnostic.OUTPUT_LIMIT)
        }
        val firstGroup = digitCount % 3
        return MarkerExpansion(listOf(buildString(outputLength.toInt()) {
            for (index in 0 until digitCount) {
                if (index > 0 && (index - firstGroup).mod(3) == 0) append(',')
                append(span[normalizedStart + index])
            }
        }))
    }

    private fun markerType(text: String, start: Int, end: Int): Int? {
        var significant = start
        while (significant < end && text[significant] == '0') significant++
        if (significant == end) return 0
        if (end - significant != 1) return null
        return (text[significant] - '0').takeIf { it in SUPPORTED_TYPES }
    }

    private fun appendLiteral(variants: List<String>, literal: String): MarkerExpansion {
        if (literal.isEmpty()) return MarkerExpansion(variants)
        val total = variants.sumOf { it.length.toLong() } + literal.length.toLong() * variants.size
        if (total > MAX_TOTAL_OUTPUT_CHARS) {
            return MarkerExpansion(diagnostic = NumericDiagnostic.OUTPUT_LIMIT)
        }
        return MarkerExpansion(variants.map { it + literal })
    }

    private fun crossProduct(
        prefixes: List<String>,
        suffixes: List<String>,
    ): MarkerExpansion {
        val count = prefixes.size.toLong() * suffixes.size
        if (count > MAX_VARIANTS) return MarkerExpansion(diagnostic = NumericDiagnostic.VARIANT_LIMIT)
        val total = suffixes.size.toLong() * prefixes.sumOf { it.length.toLong() } +
            prefixes.size.toLong() * suffixes.sumOf { it.length.toLong() }
        if (total > MAX_TOTAL_OUTPUT_CHARS) {
            return MarkerExpansion(diagnostic = NumericDiagnostic.OUTPUT_LIMIT)
        }
        return MarkerExpansion(buildList(count.toInt()) {
            prefixes.forEach { prefix -> suffixes.forEach { suffix -> add(prefix + suffix) } }
        })
    }

    private fun extractionFailure(diagnostic: NumericDiagnostic) =
        NumericExtraction(null, emptyList(), listOf(diagnostic))

    private fun expansionFailure(diagnostic: NumericDiagnostic) =
        NumericExpansion(emptyList(), listOf(diagnostic))

    private fun hasValidUtf16(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (Character.isLowSurrogate(character)) return false
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                index++
            }
            index++
        }
        return true
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private data class MarkerExpansion(
        val values: List<String> = emptyList(),
        val diagnostic: NumericDiagnostic? = null,
    )

    private val SUPPORTED_TYPES = setOf(0, 1, 2, 3, 4, 5, 8, 9)
    private const val KANJI_DIGITS = "〇一二三四五六七八九"
    private const val DAIJI_DIGITS = "零壱弐参四伍六七八九"
    private val KANJI_SMALL_UNITS = arrayOf("", "十", "百", "千")
    private val DAIJI_SMALL_UNITS = arrayOf("", "拾", "百", "阡")
    private val KANJI_LARGE_UNITS = arrayOf("", "万", "億", "兆", "京")
    private val DAIJI_LARGE_UNITS = arrayOf("", "萬", "億", "兆", "京")
}
