package se.haya.skk.dictionary

/** 保存禁止への変更前に待機していた要求を、再有効化後にも復活させない許可証です。 */
class PersonalDataPolicy(initiallyAllowed: Boolean = true) {
    class Permit internal constructor(internal val revision: Long)
    private var revision = 0L
    private var allowed = initiallyAllowed

    @Synchronized fun setAllowed(value: Boolean) {
        if (allowed != value) {
            allowed = value
            revision++
        }
    }

    @Synchronized fun request(originAllowsSaving: Boolean): Permit? =
        if (allowed && originAllowsSaving) Permit(revision) else null

    /** トランザクション開始直後、本文を書き込む前に再検査します。 */
    @Synchronized fun accepts(permit: Permit): Boolean = allowed && permit.revision == revision
}

internal class PersonalDataPolicyRejectedException : IllegalStateException("個人データの保存は禁止されています")

enum class PersonalWriteFailure { CAPACITY, CONFLICT, POLICY_REJECTED, GENERAL }

sealed interface PersonalWriteResult {
    data object Applied : PersonalWriteResult
    data object SavedButNotApplied : PersonalWriteResult
    data class Failed(val reason: PersonalWriteFailure) : PersonalWriteResult
}
