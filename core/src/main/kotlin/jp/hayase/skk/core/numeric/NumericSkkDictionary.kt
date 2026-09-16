package jp.hayase.skk.core.numeric

import jp.hayase.skk.core.BasicSkkDictionary
import jp.hayase.skk.core.DictionaryCandidate
import jp.hayase.skk.core.DictionaryQuery
import jp.hayase.skk.core.NumericLearningTarget
import jp.hayase.skk.core.RegistrationPreparation
import jp.hayase.skk.core.dictionary.CandidateSelection
import jp.hayase.skk.core.dictionary.SelectedCandidateOrigin
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
        var totalOrigins = 0L
        for (template in templates) {
            val expanded = NumericConversion.expand(template.asSkk(), extraction.spans) { number ->
                // #4 の内側候補は表示材料であり、削除対象は外側テンプレートだけです。
                raw.lookup(DictionaryQuery(number)).map { it.asSkk() }
            }
            if (expanded.candidates.isEmpty()) continue
            anySuccess = true
            for (value in expanded.candidates) {
                val previous = output[value.text]
                if (previous == null) {
                    total += value.text.length
                    if (output.size >= NumericConversion.MAX_VARIANTS ||
                        total > NumericConversion.MAX_TOTAL_OUTPUT_CHARS
                    ) {
                        throw NumericLookupException(NumericLookupFailure.EXPANSION)
                    }
                    val originCount = template.selection?.origins?.size ?: 0
                    ensureOriginLimit(totalOrigins + originCount)
                    val selection = template.selection?.asNumericTemplate()
                    totalOrigins += originCount
                    output[value.text] = DictionaryCandidate(
                        value.text,
                        value.annotation,
                        value.okuriCondition,
                        NumericLearningTarget(normalized, template.text, template.annotation, template.okuriCondition),
                        selection,
                    )
                } else {
                    val previousCount = previous.selection?.origins?.size ?: 0
                    val otherOrigins = totalOrigins - previousCount
                    val merged = mergeSelections(
                        previous.selection,
                        template.selection,
                        (MAX_SELECTION_ORIGINS - otherOrigins).toInt(),
                    )
                    totalOrigins += (merged?.origins?.size ?: 0) - (previous.selection?.origins?.size ?: 0)
                    ensureOriginLimit(totalOrigins)
                    output[value.text] = previous.copy(selection = merged)
                }
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

    private fun CandidateSelection.asNumericTemplate() = CandidateSelection(
        personalGeneration,
        origins,
        numericTemplate = true,
    )

    private fun mergeSelections(
        first: CandidateSelection?,
        second: CandidateSelection?,
        maximumOrigins: Int,
    ): CandidateSelection? {
        if (first == null || second == null) return null
        if (first.personalGeneration != second.personalGeneration) {
            throw NumericLookupException(NumericLookupFailure.EXPANSION)
        }
        val origins = LinkedHashSet(first.origins)
        second.origins.forEach { origin ->
            origins += origin
            if (origins.size > maximumOrigins) {
                throw NumericLookupException(NumericLookupFailure.EXPANSION)
            }
        }
        return CandidateSelection(first.personalGeneration, origins.toList(), numericTemplate = true)
    }

    private fun ensureOriginLimit(totalOrigins: Long) {
        if (totalOrigins > MAX_SELECTION_ORIGINS) {
            throw NumericLookupException(NumericLookupFailure.EXPANSION)
        }
    }

    private companion object {
        const val MAX_TEMPLATES = 1_024
        const val MAX_SELECTION_ORIGINS = 4_096
    }
}
