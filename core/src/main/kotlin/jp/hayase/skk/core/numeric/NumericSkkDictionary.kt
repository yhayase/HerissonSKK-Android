package jp.hayase.skk.core.numeric

import jp.hayase.skk.core.BasicSkkDictionary
import jp.hayase.skk.core.DictionaryCandidate
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.NumericLearningTarget
import jp.hayase.skk.core.RegistrationPreparation
import jp.hayase.skk.core.dictionary.SkkDictionaryCandidate

enum class NumericLookupFailure { EXTRACTION, EXPANSION }

class NumericLookupException(val failure: NumericLookupFailure) : RuntimeException("数値を展開できません")

/** 数値を含む読みだけを正規化キーで検索し、表示候補を安全に展開します。 */
class NumericSkkDictionary(private val raw: BasicSkkDictionary) : BasicSkkDictionary {
    override fun lookup(query: DictionaryQuery): List<DictionaryCandidate> {
        val extraction = extract(query)
        if (!extraction.hasNumericSpans) return raw.lookup(query)
        val normalized = query.copy(readingKey = checkNotNull(extraction.lookupKey))
        val templates = raw.lookup(normalized)
        if (templates.isEmpty()) return emptyList()
        if (templates.size > MAX_TEMPLATES) throw NumericLookupException(NumericLookupFailure.EXPANSION)
        val output = linkedMapOf<String, DictionaryCandidate>()
        var anySuccess = false
        var total = 0L
        for (template in templates) {
            val expanded = NumericConversion.expand(template.asSkk(), extraction.spans) { number ->
                raw.lookup(DictionaryQuery(number)).map { it.asSkk() }
            }
            if (expanded.candidates.isEmpty()) continue
            anySuccess = true
            for (value in expanded.candidates) {
                if (output.containsKey(value.text)) continue
                total += value.text.length
                if (output.size >= NumericConversion.MAX_VARIANTS || total > NumericConversion.MAX_TOTAL_OUTPUT_CHARS) {
                    throw NumericLookupException(NumericLookupFailure.EXPANSION)
                }
                output[value.text] = DictionaryCandidate(value.text, value.annotation, value.okuriCondition,
                    NumericLearningTarget(normalized, template.text, template.annotation, template.okuriCondition))
            }
        }
        if (!anySuccess || output.isEmpty()) throw NumericLookupException(NumericLookupFailure.EXPANSION)
        return output.values.toList()
    }

    override fun registrationQuery(original: DictionaryQuery): DictionaryQuery {
        val extraction = extract(original)
        return if (extraction.hasNumericSpans) {
            original.copy(readingKey = checkNotNull(extraction.lookupKey))
        } else {
            raw.registrationQuery(original)
        }
    }

    override fun prepareRegistration(original: DictionaryQuery, templateText: String): RegistrationPreparation {
        val extraction = extract(original)
        if (!extraction.hasNumericSpans) return raw.prepareRegistration(original, templateText)
        val expanded = NumericConversion.expand(SkkDictionaryCandidate(templateText), extraction.spans) { number ->
            raw.lookup(DictionaryQuery(number)).map { it.asSkk() }
        }
        val committed = expanded.candidates.firstOrNull()?.text
            ?: throw NumericLookupException(NumericLookupFailure.EXPANSION)
        return RegistrationPreparation(committed)
    }

    private fun extract(query: DictionaryQuery) = NumericConversion.extract(query.readingKey).also {
        if (!it.isUsable) throw NumericLookupException(NumericLookupFailure.EXTRACTION)
    }

    private fun DictionaryCandidate.asSkk() = SkkDictionaryCandidate(text, annotation, okuriCondition)

    private companion object {
        const val MAX_TEMPLATES = 1_024
    }
}
