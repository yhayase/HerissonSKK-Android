package jp.hayase.skk.core

/** 通常補完は全辞書、動的補完は個人辞書だけを明示して検索します。 */
enum class CompletionScope { ALL, PERSONAL_ONLY }

data class CompletionQuery(
    val prefix: String,
    val abbrev: Boolean = false,
    val limit: Int = MAX_RESULTS,
    val scope: CompletionScope = CompletionScope.ALL,
) {
    companion object {
        const val MAX_RESULTS = 64
        const val MAX_PREFIX_CHARS = 1_024
        const val MAX_RESULT_CHARS = 4_096
        const val MAX_TOTAL_RESULT_CHARS = 65_536
        const val MAX_WORK_ITEMS = 50_000
    }
}

enum class CompletionFailure { INVALID_INPUT, WORK_LIMIT, RESULT_LIMIT }

/** 入力本文や辞書見出しを例外文へ含めない、補完固有の失敗です。 */
class CompletionException(val failure: CompletionFailure) :
    RuntimeException("見出し語を補完できません")
