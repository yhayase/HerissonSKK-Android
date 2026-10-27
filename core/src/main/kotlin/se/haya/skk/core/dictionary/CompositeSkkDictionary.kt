package se.haya.skk.core.dictionary

import java.util.Collections
import java.util.PriorityQueue
import se.haya.skk.core.BasicSkkDictionary
import se.haya.skk.core.CompletionException
import se.haya.skk.core.CompletionFailure
import se.haya.skk.core.CompletionQuery
import se.haya.skk.core.CompletionScope
import se.haya.skk.core.DictionaryCandidate
import se.haya.skk.core.DictionaryQuery
import se.haya.skk.core.PredictionCandidate
import se.haya.skk.core.PredictionHistoryTarget
import se.haya.skk.core.PredictionCancellation
import se.haya.skk.core.PredictionQuery
import se.haya.skk.core.PredictionSearchFailure
import se.haya.skk.core.PredictionSearchResult
import se.haya.skk.core.PredictionCandidateCollector
import se.haya.skk.core.predictionCandidateRetainedChars
import se.haya.skk.core.CODE_POINT_ORDER as PREDICTION_CODE_POINT_ORDER

/** 公開済み辞書の読み取り専用世代です。構築後は呼出側のリスト変更に影響されません。 */
class SkkDictionarySource(
    val id: String,
    val generation: Long,
    entries: List<SkkDictionaryEntry>,
    val enabled: Boolean = true,
) {
    private val retainedEntries = Collections.unmodifiableList(ArrayList(entries))
    private val entryIndex = retainedEntries.associateBy { it.key }
    private val index = entryIndex.mapValues { it.value.candidates }
    private val sortedKeys = index.keys.sortedWith(CODE_POINT_ORDER)

    init {
        require(id.isNotBlank()) { "辞書IDは空にできません" }
        require(generation >= 0) { "辞書世代は0以上にします" }
        require(index.size == entries.size) { "辞書の見出し語が重複しています" }
    }

    fun candidates(key: String): List<SkkDictionaryCandidate> = index[key].orEmpty()

    fun section(key: String): SkkDictionarySection? = entryIndex[key]?.section

    /** 辞書の区分と入力順を保持して列挙します。通常検索では使用しません。 */
    fun entriesForBackup(): Sequence<SkkDictionaryEntry> = retainedEntries.asSequence()

    /** ソート済み索引の一致範囲だけを列挙し、辞書全件を通常経路で走査しません。 */
    fun completionKeys(prefix: String): Sequence<String> = sequence {
        var index = sortedKeys.lowerBound(prefix)
        while (index < sortedKeys.size) {
            val key = sortedKeys[index]
            if (!key.startsWith(prefix)) break
            yield(key)
            index++
        }
    }

    private fun List<String>.lowerBound(value: String): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (CODE_POINT_ORDER.compare(this[middle], value) < 0) low = middle + 1 else high = middle
        }
        return low
    }
    private companion object {
        val CODE_POINT_ORDER = Comparator<String> { left, right ->
            var a = 0
            var b = 0
            var result = 0
            while (a < left.length && b < right.length) {
                val x = left.codePointAt(a)
                val y = right.codePointAt(b)
                result = x.compareTo(y)
                if (result != 0) break
                a += Character.charCount(x)
                b += Character.charCount(y)
            }
            if (result != 0) result else (left.length - a).compareTo(right.length - b)
        }
    }

}

data class CandidateOrigin(
    val dictionaryId: String,
    val generation: Long,
    val personal: Boolean,
    val candidate: SkkDictionaryCandidate,
)

/** 個人領域で非表示にしたシステム候補です。注釈や辞書更新世代は同一性に含めません。 */
data class SuppressedDictionaryCandidate(
    val sourceId: String,
    val entryKey: String,
    val text: String,
    val okuriCondition: String? = null,
    val dateExpression: Boolean = false,
)

class ResolvedDictionaryCandidate internal constructor(
    val candidate: SkkDictionaryCandidate,
    origins: List<CandidateOrigin>,
) {
    val origins: List<CandidateOrigin> = Collections.unmodifiableList(ArrayList(origins))
}

/**
 * K04/K12 の検索順位を確定する、I/O のない辞書スナップショットです。
 * 個人辞書、システム辞書の指定順を優先し、送り条件は各辞書内だけで優先します。
 * 学習・削除・永続化はこの読み取り層では行いません。
 */
class CompositeSkkDictionary(
    personal: SkkDictionarySource? = null,
    systems: List<SkkDictionarySource> = emptyList(),
    suppressions: Collection<SuppressedDictionaryCandidate> = emptyList(),
    private val usageLookup: (PredictionHistoryTarget) -> Long? = { null },
) : BasicSkkDictionary {
    private val personalSource = personal
    private val personalGeneration = personal?.generation
    private val sources = listOfNotNull(personal?.let { it to true }) + systems.map { it to false }
    private val suppressions = suppressions.toSet()

    init {
        require(sources.map { it.first.id }.distinct().size == sources.size) { "辞書IDが重複しています" }
    }

    override fun lookup(query: DictionaryQuery): List<DictionaryCandidate> = resolve(query).map { resolved ->
        DictionaryCandidate(
            text = resolved.candidate.text,
            annotation = resolved.candidate.annotation,
            annotationIsPersonal = resolved.candidate.annotationIsPersonal,
            dateExpression = resolved.candidate.dateExpression,
            entrySection = resolved.origins.firstOrNull()?.let { origin ->
                sources.firstOrNull { it.first.id == origin.dictionaryId }?.first?.section(query.readingKey)
            },
            okuriCondition = resolved.candidate.okuriCondition,
            selection = personalGeneration?.let { generation ->
                CandidateSelection(
                    personalGeneration = generation,
                    origins = resolved.origins.map { origin ->
                        SelectedCandidateOrigin(
                            dictionaryId = origin.dictionaryId,
                            generation = origin.generation,
                            personal = origin.personal,
                            entryKey = query.readingKey,
                            text = origin.candidate.text,
                            okuriCondition = origin.candidate.okuriCondition,
                            dateExpression = origin.candidate.dateExpression,
                        )
                    },
                )
            },
        )
    }

    override fun complete(query: CompletionQuery): List<String> {
        validateCompletionQuery(query)
        if (query.prefix.isEmpty()) return emptyList()
        val selectedSources = when (query.scope) {
            CompletionScope.ALL -> sources
            CompletionScope.PERSONAL_ONLY -> listOfNotNull(personalSource?.let { it to true })
        }
        val result = linkedSetOf<String>()
        var workItems = 0
        var totalChars = 0L
        for ((source, personal) in selectedSources) {
            if (!source.enabled) continue
            for (key in source.completionKeys(query.prefix)) {
                if (result.size >= query.limit) return result.toList()
                workItems++
                if (workItems > CompletionQuery.MAX_WORK_ITEMS) {
                    throw CompletionException(CompletionFailure.WORK_LIMIT)
                }
                if (key == query.prefix ||
                    !query.abbrev && source.section(key) == SkkDictionarySection.OKURI_ARI ||
                    query.abbrev && !key.isAscii()
                ) continue
                val candidates = source.candidates(key)
                if (candidates.isEmpty()) continue
                if (!personal) {
                    var visible = false
                    for (candidate in candidates) {
                        workItems++
                        if (workItems > CompletionQuery.MAX_WORK_ITEMS) {
                            throw CompletionException(CompletionFailure.WORK_LIMIT)
                        }
                        if (SuppressedDictionaryCandidate(
                            source.id,
                            key,
                            candidate.text,
                            candidate.okuriCondition,
                            candidate.dateExpression,
                        ) !in suppressions) {
                            visible = true
                            break
                        }
                    }
                    if (!visible) continue
                }
                if (!key.hasValidUtf16()) throw CompletionException(CompletionFailure.INVALID_INPUT)
                if (key.length > CompletionQuery.MAX_RESULT_CHARS) {
                    throw CompletionException(CompletionFailure.RESULT_LIMIT)
                }
                if (!result.add(key)) continue
                totalChars += key.length
                if (totalChars > CompletionQuery.MAX_TOTAL_RESULT_CHARS) {
                    throw CompletionException(CompletionFailure.RESULT_LIMIT)
                }
            }
        }
        return result.toList()
    }

    override fun predict(query: PredictionQuery): PredictionSearchResult {
        PredictionCancellation.check()
        val prefixes = query.prefixes.distinct()
        if (prefixes.isEmpty() || prefixes.size > PredictionQuery.MAX_PREFIXES ||
            prefixes.any { it.isEmpty() || it.length > PredictionQuery.MAX_PREFIX_CHARS || !it.hasValidUtf16() } ||
            query.limit !in 1..PredictionQuery.MAX_RESULTS ||
            query.workLimit !in 1..PredictionQuery.MAX_WORK_ITEMS ||
            query.totalResultCharsLimit !in 1..PredictionQuery.MAX_TOTAL_RESULT_CHARS
        ) return PredictionSearchResult.Indeterminate(PredictionSearchFailure.INVALID_INPUT)
        data class Cursor(val keys: Iterator<String>, var key: String)
        val cursors = PriorityQueue<Cursor> { left, right ->
            PREDICTION_CODE_POINT_ORDER.compare(left.key, right.key)
        }
        sources.forEach { (source, _) ->
            if (source.enabled) prefixes.forEach { prefix ->
                val keys = source.completionKeys(prefix).iterator()
                if (keys.hasNext()) cursors.add(Cursor(keys, keys.next()))
            }
        }
        var work = 0
        val collector = PredictionCandidateCollector(query.limit, query.totalResultCharsLimit)
        while (cursors.isNotEmpty()) {
            PredictionCancellation.check()
            val key = cursors.peek().key
            do {
                val cursor = cursors.remove()
                if (++work > query.workLimit) {
                    return PredictionSearchResult.Indeterminate(PredictionSearchFailure.WORK_LIMIT)
                }
                if (cursor.keys.hasNext()) {
                    cursor.key = cursor.keys.next()
                    cursors.add(cursor)
                }
            } while (cursors.peek()?.key == key)
            if (query.abbrev && !key.isAscii()) continue
            var readingChars = key.length.toLong()
            if (readingChars > query.totalResultCharsLimit) {
                return PredictionSearchResult.Indeterminate(PredictionSearchFailure.RESULT_LIMIT)
            }
            for ((source, _) in sources) {
                if (!source.enabled) continue
                for (candidate in source.candidates(key)) {
                    if (candidate.dateExpression) continue
                    readingChars += candidate.text.length + (candidate.annotation?.length ?: 0) +
                        (candidate.okuriCondition?.length ?: 0)
                    if (readingChars > query.totalResultCharsLimit) {
                        return PredictionSearchResult.Indeterminate(PredictionSearchFailure.RESULT_LIMIT)
                    }
                }
            }
            val resolved = resolveForPrediction(key)
            work += resolved.size
            if (work > query.workLimit) {
                return PredictionSearchResult.Indeterminate(PredictionSearchFailure.WORK_LIMIT)
            }
            val values = predictionCandidatesForReading(key, query.abbrev, resolved)
            if (values.sumOf(::predictionCandidateRetainedChars) > query.totalResultCharsLimit) {
                return PredictionSearchResult.Indeterminate(PredictionSearchFailure.RESULT_LIMIT)
            }
            values.forEach(collector::add)
        }
        return collector.result()
    }

    /** 呼出側で一読みの作業量と保持量を制限してから、全由来をまとめて解決します。 */
    fun predictionCandidatesForReading(key: String, abbrev: Boolean = false): List<PredictionCandidate> {
        if (abbrev && !key.isAscii()) return emptyList()
        return predictionCandidatesForReading(key, abbrev, resolveForPrediction(key))
    }

    private fun predictionCandidatesForReading(
        key: String,
        abbrev: Boolean,
        resolved: List<ResolvedDictionaryCandidate>,
    ): List<PredictionCandidate> {
        val values = mutableListOf<PredictionCandidate>()
        resolved.forEachIndexed { candidateOrder, value ->
            PredictionCancellation.check()
            val candidate = value.candidate
            val entrySection = value.origins.firstOrNull()?.let { origin ->
                sources.firstOrNull { it.first.id == origin.dictionaryId }?.first?.section(key)
            }
            val okuri = if (!abbrev && entrySection == SkkDictionarySection.OKURI_ARI) {
                candidate.okuriCondition ?: return@forEachIndexed
            } else ""
            val displayed = DictionaryCandidate(
                text = candidate.text,
                annotation = candidate.annotation,
                annotationIsPersonal = candidate.annotationIsPersonal,
                entrySection = entrySection,
                okuriCondition = candidate.okuriCondition,
                selection = personalGeneration?.let { generation ->
                    CandidateSelection(generation, value.origins.map { origin ->
                        SelectedCandidateOrigin(origin.dictionaryId, origin.generation, origin.personal,
                            key, origin.candidate.text, origin.candidate.okuriCondition)
                    })
                },
            )
            val committedText = displayed.text + okuri
            val historyTarget = PredictionHistoryTarget(
                key, candidate.text, candidate.okuriCondition, committedText,
            )
            values += PredictionCandidate(
                displayed,
                committedText,
                historyTarget,
                lastUsedSequence = usageLookup(historyTarget),
                dictionaryOrder = value.origins.firstOrNull()?.let { origin ->
                    sources.indexOfFirst { it.first.id == origin.dictionaryId }.coerceAtLeast(0)
                } ?: 0,
                candidateOrder = candidateOrder,
            )
        }
        return values
    }

    /** 同じ語幹でも送り条件ごとに完成語が異なるため、予測では条件を潰しません。 */
    private fun resolveForPrediction(key: String): List<ResolvedDictionaryCandidate> {
        val byIdentity = linkedMapOf<Triple<String, String?, Boolean>, MutableList<CandidateOrigin>>()
        for ((source, personal) in sources) {
            PredictionCancellation.check()
            if (!source.enabled) continue
            source.candidates(key).filterNot { candidate ->
                candidate.dateExpression || !personal && SuppressedDictionaryCandidate(
                    source.id, key, candidate.text, candidate.okuriCondition, candidate.dateExpression,
                ) in suppressions
            }.forEach { candidate ->
                byIdentity.getOrPut(Triple(candidate.text, candidate.okuriCondition, candidate.dateExpression)) { mutableListOf() }
                    .add(CandidateOrigin(source.id, source.generation, personal, candidate))
            }
        }
        return byIdentity.values.map { origins ->
            val first = origins.first().candidate
            val annotated = origins.firstOrNull { !it.candidate.annotation.isNullOrEmpty() }?.candidate
            ResolvedDictionaryCandidate(first.copy(annotation = annotated?.annotation, annotationIsPersonal = annotated?.annotationIsPersonal ?: false), origins)
        }
    }

    fun resolve(query: DictionaryQuery): List<ResolvedDictionaryCandidate> {
        val byText = linkedMapOf<Pair<String, Boolean>, MutableList<CandidateOrigin>>()
        for ((source, personal) in sources) {
            PredictionCancellation.check()
            if (!source.enabled) continue
            val candidates = source.candidates(query.readingKey).filterNot { candidate ->
                !personal && SuppressedDictionaryCandidate(
                    source.id, query.readingKey, candidate.text, candidate.okuriCondition, candidate.dateExpression,
                ) in suppressions
            }
            val ordered = if (query.okuri == null || query.abbrev) candidates else candidates.sortedBy {
                when (it.okuriCondition) {
                    query.okuri -> 0
                    null -> 1
                    else -> 2
                }
            }
            for (candidate in ordered) {
                byText.getOrPut(candidate.text to candidate.dateExpression) { mutableListOf() }
                    .add(CandidateOrigin(source.id, source.generation, personal, candidate))
            }
        }
        return byText.values.map { origins ->
            val first = origins.first().candidate
            val annotated = origins.firstOrNull { !it.candidate.annotation.isNullOrEmpty() }?.candidate
            ResolvedDictionaryCandidate(first.copy(annotation = annotated?.annotation, annotationIsPersonal = annotated?.annotationIsPersonal ?: false), origins)
        }
    }

    private fun validateCompletionQuery(query: CompletionQuery) {
        if (query.limit !in 1..CompletionQuery.MAX_RESULTS ||
            query.prefix.length > CompletionQuery.MAX_PREFIX_CHARS ||
            !query.prefix.hasValidUtf16()
        ) {
            throw CompletionException(CompletionFailure.INVALID_INPUT)
        }
    }

    private fun String.isAscii(): Boolean = all { it.code in 0x20..0x7e }

    private fun String.hasValidUtf16(): Boolean {
        var index = 0
        while (index < length) {
            val value = this[index]
            when {
                value.isHighSurrogate() -> {
                    if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                    index += 2
                }
                value.isLowSurrogate() -> return false
                else -> index++
            }
        }
        return true
    }
}
