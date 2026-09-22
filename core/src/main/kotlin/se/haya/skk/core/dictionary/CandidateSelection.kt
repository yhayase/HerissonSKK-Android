package se.haya.skk.core.dictionary

import java.util.Collections

/** 表示候補を構成した、展開前の辞書候補一件です。 */
data class SelectedCandidateOrigin(
    val dictionaryId: String,
    val generation: Long,
    val personal: Boolean,
    val entryKey: String,
    val text: String,
    val okuriCondition: String?,
) {
    init {
        require(dictionaryId.isNotBlank()) { "候補由来の辞書IDが不正です" }
        require(generation >= 0) { "候補由来の辞書世代が不正です" }
        require(entryKey.isNotEmpty() && text.isNotEmpty()) { "候補由来が不正です" }
        require(okuriCondition?.isNotEmpty() != false) { "送り条件が不正です" }
    }
}

/** 削除確認まで固定する候補由来です。呼出側のリスト変更から独立します。 */
class CandidateSelection(
    val personalGeneration: Long,
    origins: List<SelectedCandidateOrigin>,
    val numericTemplate: Boolean = false,
) {
    val origins: List<SelectedCandidateOrigin> = Collections.unmodifiableList(ArrayList(origins))

    init {
        require(personalGeneration >= 0) { "個人辞書世代が不正です" }
        require(origins.isNotEmpty()) { "候補由来は一件以上必要です" }
        require(origins.distinct().size == origins.size) { "候補由来が重複しています" }
    }
}
