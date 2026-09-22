package se.haya.skk.dictionary

import java.util.Collections

/** 削除確認時に固定する、表示前の辞書候補の由来種別です。 */
enum class CandidateOriginKind { PERSONAL, STORED_SYSTEM, IMMUTABLE_SYSTEM }

/** 表示候補ではなく、辞書に保存された候補テンプレートを指します。 */
data class StoredCandidateOriginRef(
    val sourceId: String,
    val sourceGeneration: Long,
    val kind: CandidateOriginKind,
    val entryKey: String,
    val templateText: String,
    val okuriCondition: String?,
) {
    init {
        require(sourceId.isNotBlank()) { "候補由来の辞書IDが不正です" }
        require(sourceGeneration >= 0) { "候補由来の辞書世代が不正です" }
        require(entryKey.isNotEmpty() && templateText.isNotEmpty()) { "候補由来が不正です" }
        require(okuriCondition?.isNotEmpty() != false) { "送り条件が不正です" }
    }
}

class DeleteCandidateRequest(
    val expectedPersonalGeneration: Long,
    origins: List<StoredCandidateOriginRef>,
) {
    val origins: List<StoredCandidateOriginRef> = Collections.unmodifiableList(ArrayList(origins))

    init {
        require(expectedPersonalGeneration >= 0) { "個人辞書世代が不正です" }
        require(origins.isNotEmpty()) { "削除対象がありません" }
        require(origins.distinct().size == origins.size) { "削除対象が重複しています" }
    }
}

data class CandidateSuppressionKey(
    val sourceId: String,
    val entryKey: String,
    val templateText: String,
    val okuriCondition: String?,
) {
    init {
        require(sourceId.isNotBlank() && sourceId != SQLiteDictionaryRepository.PERSONAL_SOURCE_ID) {
            "抑止対象の辞書IDが不正です"
        }
        require(entryKey.isNotEmpty() && templateText.isNotEmpty()) { "抑止対象が不正です" }
        require(okuriCondition?.isNotEmpty() != false) { "送り条件が不正です" }
    }
}

data class CandidateSuppressionInfo(val key: CandidateSuppressionKey)

class CandidateSuppressionSnapshot(
    val personalGeneration: Long,
    suppressions: List<CandidateSuppressionInfo>,
) {
    val suppressions: List<CandidateSuppressionInfo> =
        Collections.unmodifiableList(ArrayList(suppressions))

    init {
        require(personalGeneration >= 0) { "個人辞書世代が不正です" }
    }
}

class CandidateOriginMismatchException : IllegalStateException("削除対象が更新されています")

class CandidateSuppressionMissingException : IllegalStateException("非表示候補が更新されています")
