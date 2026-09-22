package se.haya.skk.core

/** 前方一致予測の検索条件です。完全一致も候補に含めます。 */
data class PredictionQuery(
    val prefix: String,
    val abbrev: Boolean = false,
    val limit: Int = MAX_RESULTS,
    /** 未消化ローマ字から到達できる、同じ入力に属する別の読みです。 */
    val alternativePrefixes: List<String> = emptyList(),
    val workLimit: Int = MAX_WORK_ITEMS,
    val totalResultCharsLimit: Int = MAX_TOTAL_RESULT_CHARS,
) {
    val prefixes: List<String> get() = listOf(prefix) + alternativePrefixes

    companion object {
        const val MAX_RESULTS = 64
        const val MAX_PREFIXES = 64
        const val MAX_PREFIX_CHARS = 1_024
        const val MAX_WORK_ITEMS = 50_000
        const val MAX_TOTAL_RESULT_CHARS = 65_536
    }
}

/** 辞書上の元候補と、実際に入力先へ確定した文字列を結ぶ履歴キーです。 */
data class PredictionHistoryTarget(
    val readingKey: String,
    val templateText: String,
    val okuriCondition: String?,
    val committedText: String,
)

/** UI には readingKey を表示せず、確定後の履歴更新にだけ使用します。 */
data class PredictionCandidate(
    val candidate: DictionaryCandidate,
    val committedText: String,
    val historyTarget: PredictionHistoryTarget,
    val lastUsedSequence: Long? = null,
    val dictionaryOrder: Int = 0,
    val candidateOrder: Int = 0,
)

enum class PredictionSearchFailure { PENDING, INVALID_INPUT, WORK_LIMIT, RESULT_LIMIT, UNAVAILABLE, UNSUPPORTED }

sealed interface PredictionSearchResult {
    data class Ready(
        val items: List<PredictionCandidate>,
        val hasMore: Boolean,
    ) : PredictionSearchResult

    data object ConfirmedEmpty : PredictionSearchResult
    data class Indeterminate(val failure: PredictionSearchFailure) : PredictionSearchResult
}

/** 描画可能な正しい先頭範囲だけを返します。未走査で順位が変わり得る場合は呼び出しません。 */
fun rankPredictionCandidates(
    values: Collection<PredictionCandidate>,
    limit: Int,
): PredictionSearchResult {
    if (values.isEmpty()) return PredictionSearchResult.ConfirmedEmpty
    val representative = linkedMapOf<String, PredictionCandidate>()
    values.forEach { value ->
        val previous = representative[value.committedText]
        if (previous == null || comparePrediction(value, previous) < 0) {
            representative[value.committedText] = value
        }
    }
    val ordered = representative.values.sortedWith(::comparePrediction)
    val items = ordered.take(limit)
    return PredictionSearchResult.Ready(items, ordered.size > items.size)
}

private fun comparePrediction(left: PredictionCandidate, right: PredictionCandidate): Int {
    val leftUsed = left.lastUsedSequence
    val rightUsed = right.lastUsedSequence
    if (leftUsed != null || rightUsed != null) {
        if (leftUsed == null) return 1
        if (rightUsed == null) return -1
        val recent = rightUsed.compareTo(leftUsed)
        if (recent != 0) return recent
    }
    val reading = CODE_POINT_ORDER.compare(left.historyTarget.readingKey, right.historyTarget.readingKey)
    if (reading != 0) return reading
    val dictionary = left.dictionaryOrder.compareTo(right.dictionaryOrder)
    if (dictionary != 0) return dictionary
    val candidate = left.candidateOrder.compareTo(right.candidateOrder)
    if (candidate != 0) return candidate
    return CODE_POINT_ORDER.compare(left.committedText, right.committedText)
}

internal val CODE_POINT_ORDER: Comparator<String> = Comparator { left, right ->
    val a = left.codePoints().iterator()
    val b = right.codePoints().iterator()
    var result = 0
    while (a.hasNext() && b.hasNext() && result == 0) result = a.nextInt().compareTo(b.nextInt())
    if (result != 0) result else a.hasNext().compareTo(b.hasNext())
}
