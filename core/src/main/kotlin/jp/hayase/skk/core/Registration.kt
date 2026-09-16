package jp.hayase.skk.core

/** 入力セッションごとに固定する登録方針です。既定では登録を開始しません。 */
data class RegistrationPolicy(
    val enabled: Boolean = false,
    val sessionGeneration: Long = 0,
    val savingAllowed: Boolean = true,
)

/** 保存完了を、要求した登録フレームだけへ戻すための不透明な識別子です。 */
data class RegistrationSaveToken(
    val operationId: Long,
    val frameId: Long,
    val frameRevision: Long,
    val parentFrameId: Long?,
    val sessionGeneration: Long,
)

data class RegistrationSaveRequest(
    val token: RegistrationSaveToken,
    val readingKey: String,
    val candidateText: String,
    val okuriCondition: String?,
    val committedText: String,
)

sealed interface BasicSkkEffect {
    data class SaveRegistration(val request: RegistrationSaveRequest) : BasicSkkEffect
    data class LearnCandidate(val request: CandidateCommitRequest) : BasicSkkEffect
}

/** 入力先での確定成功後だけ学習する候補を、確定操作の時点で固定します。 */
data class CandidateCommitRequest(
    val operationId: Long,
    val sessionGeneration: Long,
    val query: DictionaryQuery,
    val candidate: DictionaryCandidate,
)

enum class RegistrationSaveFailure { CAPACITY, CONFLICT, POLICY_REJECTED, GENERAL }

sealed interface RegistrationSaveOutcome {
    data object Applied : RegistrationSaveOutcome
    data object SavedButNotApplied : RegistrationSaveOutcome
    data class Failed(val reason: RegistrationSaveFailure) : RegistrationSaveOutcome
}

data class RegistrationSaveCompletion(
    val token: RegistrationSaveToken,
    val outcome: RegistrationSaveOutcome,
)

/** 入力先の composition と分離して IME 内へ表示する登録状態です。 */
data class RegistrationView(
    val depth: Int,
    val readingKey: String,
    val body: String,
    val cursor: Int,
    val innerComposing: String?,
    val innerCursor: Int?,
    val innerCandidate: CandidateView?,
    val saving: Boolean,
)
