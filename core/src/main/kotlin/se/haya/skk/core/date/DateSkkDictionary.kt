package se.haya.skk.core.date

import java.time.Clock
import java.time.ZoneId
import se.haya.skk.core.BasicSkkDictionary
import se.haya.skk.core.DictionaryCandidate
import se.haya.skk.core.DictionaryQuery
import se.haya.skk.core.NumericLearningTarget
import se.haya.skk.core.PredictionQuery
import se.haya.skk.core.PredictionSearchResult
import se.haya.skk.core.dictionary.SkkDictionarySource
import se.haya.skk.core.dictionary.SkkDictionaryEntry
import se.haya.skk.core.dictionary.SkkDictionaryCandidate

/** キャッシュした元候補を、変換ごとに一つの時刻・時間帯で展開します。 */
class DateSkkDictionary(
    private val raw: BasicSkkDictionary,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val defaultFormat: DateFormat = DateFormat.DDSKK_ERA_FULLWIDTH,
) : BasicSkkDictionary by raw {
    override fun lookup(query: DictionaryQuery): List<DictionaryCandidate> {
        val source = raw.lookup(query)
        val instant = clock.instant()
        val zone = zoneProvider()
        return source.mapNotNull { candidate ->
            if (!candidate.dateExpression) return@mapNotNull candidate
            val original = candidate.learningTarget?.templateText ?: candidate.text
            val expression = DateExpression.parse(original) ?: return@mapNotNull null
            val rendered = DateRenderer.render(expression, instant, zone, defaultFormat) ?: return@mapNotNull null
            candidate.copy(text = rendered, learningTarget = candidate.learningTarget ?: NumericLearningTarget(
                query, original, candidate.annotation, candidate.okuriCondition,
                candidate.annotationIsPersonal, candidate.entrySection, dateExpression = true,
            ))
        }
    }

    override fun predict(query: PredictionQuery): PredictionSearchResult = when (val result = raw.predict(query)) {
        is PredictionSearchResult.Ready -> result.copy(items = result.items.filterNot { it.candidate.dateExpression })
        else -> result
    }

    companion object {
        /** アプリでは通常の辞書由来として合成し、削除抑止とバックアップも適用します。 */
        fun builtinSource(): SkkDictionarySource = SkkDictionarySource("builtin-date-formats", 0, listOf(
            SkkDictionaryEntry("today", listOf(
                DateExpression(),
                DateExpression(format = DateFormat.GREGORIAN_JAPANESE),
                DateExpression(format = DateFormat.ISO),
            ).map { SkkDictionaryCandidate(it.canonicalSource(), dateExpression = true) }),
        ))
    }
}
