package jp.hayase.skk.core

import jp.hayase.skk.core.dictionary.CandidateSelection

/** 非同期削除の完了を現在の入力セッションへだけ戻す識別子です。 */
data class CandidateDeletionToken(
    val operationId: Long,
    val sessionGeneration: Long,
)

/** 確認時の表示と辞書由来を固定した削除要求です。 */
data class CandidateDeletionRequest(
    val token: CandidateDeletionToken,
    val query: DictionaryQuery,
    val displayedText: String,
    val annotation: String?,
    val selection: CandidateSelection,
)

enum class CandidateDeletionFailure { CAPACITY, CONFLICT, POLICY_REJECTED, GENERAL }

sealed interface CandidateDeletionOutcome {
    data object Applied : CandidateDeletionOutcome
    data object SavedButNotApplied : CandidateDeletionOutcome
    data class Failed(val reason: CandidateDeletionFailure) : CandidateDeletionOutcome
}

data class CandidateDeletionCompletion(
    val token: CandidateDeletionToken,
    val outcome: CandidateDeletionOutcome,
)

/** 候補を確定せず IME 内へ表示する削除確認です。 */
data class CandidateDeletionView(
    val readingKey: String,
    val candidateText: String,
    val okuri: String?,
    val originCount: Int,
    val personalOriginCount: Int,
    val systemOriginCount: Int,
    val numericTemplate: Boolean,
    val saving: Boolean,
)
