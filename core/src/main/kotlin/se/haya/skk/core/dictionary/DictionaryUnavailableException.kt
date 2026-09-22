package se.haya.skk.core.dictionary

/** 公開済み辞書をまだ利用できない理由です。 */
enum class DictionaryUnavailableReason { INITIALIZING, FAILED }

/** 辞書の公開前または再読込失敗時に、検索を空結果として扱わないための例外です。 */
class DictionaryUnavailableException(
    val reason: DictionaryUnavailableReason,
) : IllegalStateException(
    when (reason) {
        DictionaryUnavailableReason.INITIALIZING -> "辞書を準備しています"
        DictionaryUnavailableReason.FAILED -> "辞書を利用できません"
    },
)
