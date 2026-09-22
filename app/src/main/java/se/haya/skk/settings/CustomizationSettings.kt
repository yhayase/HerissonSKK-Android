package se.haya.skk.settings

import java.util.Collections
import se.haya.skk.core.CandidateDisplayConfig
import se.haya.skk.core.PunctuationConfig
import se.haya.skk.core.romaji.RomajiRule
import se.haya.skk.core.romaji.RomanRuleSet
import se.haya.skk.core.romaji.AzikRules
import se.haya.skk.core.keys.KeyBindings
import se.haya.skk.core.keys.KeyGesture
import se.haya.skk.core.keys.SkkCommand
import se.haya.skk.core.keys.KeyBindingState
import se.haya.skk.core.romaji.Romanizer

enum class CustomizationProfile { STANDARD, CUSTOM, AZIK }

/** Emacs 編集キーを適用する範囲です。 */
enum class EmacsEditingMode(val label: String) {
    DISABLED("無効"),
    IME_ONLY("IME 内のみ"),
    IME_AND_APP("IME とアプリ"),
}

/** 入力セッションへ渡す前に全件検証した、変更不能なカスタマイズ設定です。 */
class CustomizationSettings(
    val generation: Long,
    val profile: CustomizationProfile = CustomizationProfile.STANDARD,
    customRules: List<RomajiRule> = emptyList(),
    val punctuation: PunctuationConfig = PunctuationConfig(),
    val candidateDisplay: CandidateDisplayConfig = CandidateDisplayConfig(),
    val emacsEnabled: Boolean = false,
    val keyBindings: KeyBindings = KeyBindings(if (profile == CustomizationProfile.AZIK)
        KeyBindings.defaults + (SkkCommand.TOGGLE_KANA to KeyGesture("[", ignoreShift = true)) else KeyBindings.defaults),
    /** 入力先ではなく IME 内の未確定入力だけに Emacs 編集キーを適用します。 */
    val internalEmacsEnabled: Boolean = false,
) {
    val customRules: List<RomajiRule> = Collections.unmodifiableList(customRules.map { it.copy() })
    val romanRuleSet: RomanRuleSet
    /** 互換用のフラグから導出した Emacs 編集キーの適用範囲です。 */
    val emacsEditingMode: EmacsEditingMode = when {
        emacsEnabled -> EmacsEditingMode.IME_AND_APP
        internalEmacsEnabled -> EmacsEditingMode.IME_ONLY
        else -> EmacsEditingMode.DISABLED
    }

    init {
        require(generation >= 0) { "設定世代は0以上にします" }
        val anyEmacsEditingEnabled = emacsEnabled || internalEmacsEnabled
        keyBindings.validate(anyEmacsEditingEnabled)
        keyBindings.validateLabels(candidateDisplay.labels, anyEmacsEditingEnabled)
        require(candidateDisplay.labels in CANDIDATE_LABEL_PRESETS) {
            "候補ラベルは初期対応のプリセットから選びます"
        }
        when (profile) {
            CustomizationProfile.STANDARD -> {
                require(this.customRules.isEmpty()) {
                    "標準プロファイルにカスタム規則は保存できません"
                }
                romanRuleSet = RomanRuleSet.standard
            }
            CustomizationProfile.AZIK -> {
                require(this.customRules.isEmpty()) { "AZIKプロファイルにカスタム規則は保存できません" }
                romanRuleSet = AzikRules.ruleSet
            }
            CustomizationProfile.CUSTOM -> {
                require(this.customRules.none { rule ->
                    (rule.input.any { it.isUpperCase() } || rule.remaining.any { it.isUpperCase() }) &&
                        rule !in Romanizer.standardRules
                }) { "カスタム規則の入力と残余に大文字は使えません" }
                romanRuleSet = RomanRuleSet.compile(this.customRules)
            }
        }
        val ownedStarts = when (profile) {
            CustomizationProfile.STANDARD -> emptySet()
            CustomizationProfile.AZIK -> setOf('q', 'x', ';', ':', '-')
            CustomizationProfile.CUSTOM -> this.customRules.filter { it !in Romanizer.standardRules }
                .map { it.input.first() }.toSet()
        }
        val startingStates = setOf(KeyBindingState.IDLE, KeyBindingState.READING,
            KeyBindingState.REGISTRATION_BODY, KeyBindingState.REGISTRATION_READING)
        keyBindings.bindings.forEach { (command, key) ->
            require(command.emacsOnly && !anyEmacsEditingEnabled || key.ctrl || key.alt ||
                key.text?.singleOrNull() !in ownedStarts || command.states.intersect(startingStates).isEmpty()) {
                "${command.title}のキーが入力規則の先頭文字と重複しています。別のキーを割り当ててください"
            }
        }
    }

    fun withGeneration(value: Long): CustomizationSettings = CustomizationSettings(
        value,
        profile,
        customRules,
        punctuation,
        candidateDisplay,
        emacsEnabled,
        keyBindings,
        internalEmacsEnabled,
    )

    fun withEmacsEnabled(value: Boolean): CustomizationSettings = if (value == emacsEnabled) this else
        CustomizationSettings(generation, profile, customRules, punctuation, candidateDisplay, value, keyBindings,
            internalEmacsEnabled)

    fun withEmacsEditingMode(mode: EmacsEditingMode): CustomizationSettings {
        val flags = when (mode) {
            EmacsEditingMode.DISABLED -> false to false
            EmacsEditingMode.IME_ONLY -> false to true
            EmacsEditingMode.IME_AND_APP -> true to false
        }
        return if (emacsEnabled == flags.first && internalEmacsEnabled == flags.second) this else
            CustomizationSettings(generation, profile, customRules, punctuation, candidateDisplay, flags.first,
                keyBindings, flags.second)
    }

    override fun equals(other: Any?): Boolean = other is CustomizationSettings &&
        generation == other.generation && profile == other.profile && customRules == other.customRules &&
        punctuation == other.punctuation && candidateDisplay == other.candidateDisplay &&
        emacsEnabled == other.emacsEnabled && keyBindings == other.keyBindings &&
        internalEmacsEnabled == other.internalEmacsEnabled

    override fun hashCode(): Int {
        var result = generation.hashCode()
        result = 31 * result + profile.hashCode()
        result = 31 * result + customRules.hashCode()
        result = 31 * result + punctuation.hashCode()
        result = 31 * result + candidateDisplay.hashCode()
        result = 31 * result + emacsEnabled.hashCode()
        result = 31 * result + keyBindings.hashCode()
        result = 31 * result + internalEmacsEnabled.hashCode()
        return result
    }

    override fun toString(): String =
        "CustomizationSettings(generation=$generation, profile=$profile, " +
            "customRuleCount=${customRules.size}, punctuation=$punctuation, " +
            "candidateDisplay=$candidateDisplay, emacsEnabled=$emacsEnabled, " +
            "internalEmacsEnabled=$internalEmacsEnabled)"

    companion object {
        val CANDIDATE_LABEL_PRESETS: Set<String> =
            Collections.unmodifiableSet(linkedSetOf("asdfjkl", "1234567"))
        fun defaults(generation: Long = 0): CustomizationSettings = CustomizationSettings(generation)
    }
}
