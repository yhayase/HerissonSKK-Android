package se.haya.skk

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import se.haya.skk.core.CandidatePageMode
import se.haya.skk.core.keys.KeyBindings
import se.haya.skk.core.keys.KeyGesture
import se.haya.skk.core.keys.SkkCommand
import se.haya.skk.core.romaji.RomajiRule
import se.haya.skk.core.romaji.Romanizer
import se.haya.skk.settings.CustomizationProfile
import se.haya.skk.settings.EmacsEditingMode
import se.haya.skk.settings.KeyGestureText

/** Android 標準の設定階層で、選択された一ページだけを表示します。 */
class CustomizationPageFragment : PreferenceFragmentCompat() {
    private val host: CustomizationSettingsActivity
        get() = requireActivity() as CustomizationSettingsActivity
    private var controlsEnabled = true

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        refresh()
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is EditTextPreference && preference.key.startsWith(KEY_BINDING_PREFIX)) {
            if (parentFragmentManager.findFragmentByTag(DIALOG_TAG) == null) {
                KeyBindingPreferenceDialog.newInstance(preference.key).apply {
                    setTargetFragment(this@CustomizationPageFragment, 0)
                }.show(parentFragmentManager, DIALOG_TAG)
            }
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    fun refresh() {
        if (!isAdded) return
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen
        val draft = host.currentDraft()
        if (draft == null) {
            screen.addPreference(info("設定を読み込んでいます"))
            return
        }
        when (requireArguments().getInt(ARG_PAGE)) {
            CustomizationSettingsActivity.PAGE_ROMAJI -> buildRomaji(screen, draft)
            CustomizationSettingsActivity.PAGE_PUNCTUATION -> buildPunctuation(screen, draft)
            CustomizationSettingsActivity.PAGE_CANDIDATES -> buildCandidates(screen, draft)
            else -> buildKeys(screen, draft)
        }
        setControlsEnabled(controlsEnabled)
    }

    fun setControlsEnabled(enabled: Boolean) {
        controlsEnabled = enabled
        preferenceScreen?.isEnabled = enabled
    }

    private fun buildRomaji(screen: PreferenceScreen, draft: CustomizationDraft) {
        val inputMethod = category("入力方式")
        screen.addPreference(inputMethod)
        inputMethod.addPreference(listPreference(
            "romaji_profile",
            "ローマ字規則",
            arrayOf("標準", "AZIK", "カスタム"),
            arrayOf("STANDARD", "AZIK", "CUSTOM"),
            draft.profile.name,
        ) { selected ->
            val profile = CustomizationProfile.valueOf(selected)
            host.updateDraft {
                updateDefaultToggle(profile)
                this.profile = profile
            }
            refresh()
        })
        if (draft.profile != CustomizationProfile.CUSTOM) return

        val customRules = category("カスタム規則（${draft.rules.size}件）")
        screen.addPreference(customRules)
        if (draft.rules.isEmpty()) {
            customRules.addPreference(action("標準規則をコピーして編集", "標準の打ち方を基に一部だけ変更します。") {
                host.updateDraft { rules += Romanizer.standardRules }
                refresh()
            })
        }
        customRules.addPreference(EditTextPreference(requireContext()).apply {
            key = "rule_search"
            title = "規則を検索"
            dialogTitle = title
            summary = draft.ruleSearch.ifBlank { "打つ文字または出力で絞り込みます" }
            isPersistent = false
            setDefaultValue(draft.ruleSearch)
            text = draft.ruleSearch
            setOnBindEditTextListener { field ->
                field.hint = "例: ka、か"
                field.inputType = InputType.TYPE_CLASS_TEXT
                field.selectAll()
            }
            setOnPreferenceChangeListener { _, value ->
                host.updateDraft { ruleSearch = value.toString().trim() }
                refresh()
                true
            }
        })
        customRules.addPreference(action("規則を追加", "打つ文字と入力結果を追加します。") { host.editRule(null) })

        val query = draft.ruleSearch.lowercase()
        val visible = draft.rules.withIndex().filter { (_, rule) ->
            query.isBlank() || listOf(rule.input, rule.output, rule.remaining, rule.terminalOutput.orEmpty())
                .any { query in it.lowercase() }
        }
        visible.forEach { (index, rule) ->
            customRules.addPreference(action(
                "${rule.input} → ${rule.terminalOutput ?: rule.output}",
                ruleSummary(rule),
            ) { host.editRule(index) })
        }
        if (visible.isEmpty()) customRules.addPreference(info("一致する規則はありません"))
    }

    private fun CustomizationDraft.updateDefaultToggle(next: CustomizationProfile) {
        val currentText = keyBindings.getValue(SkkCommand.TOGGLE_KANA)
        val oldDefault = KeyGestureText.format(
            CustomizationDraft.defaultBindings(profile).getValue(SkkCommand.TOGGLE_KANA),
        )
        if (currentText == oldDefault) {
            keyBindings[SkkCommand.TOGGLE_KANA] = KeyGestureText.format(
                CustomizationDraft.defaultBindings(next).getValue(SkkCommand.TOGGLE_KANA),
            )
        }
    }

    private fun buildPunctuation(screen: PreferenceScreen, draft: CustomizationDraft) {
        val punctuationCategory = category("かな入力で使う記号")
        screen.addPreference(punctuationCategory)
        punctuationCategory.addPreference(listPreference(
            "period", "句点",
            arrayOf("。　句点", "．　全角ピリオド", ".　半角ピリオド"),
            arrayOf("。", "．", "."), draft.punctuation.period,
        ) { host.updateDraft { punctuation = punctuation.copy(period = it) } })
        punctuationCategory.addPreference(listPreference(
            "comma", "読点",
            arrayOf("、　読点", "，　全角コンマ", ",　半角コンマ"),
            arrayOf("、", "，", ","), draft.punctuation.comma,
        ) { host.updateDraft { punctuation = punctuation.copy(comma = it) } })
        punctuationCategory.addPreference(booleanChoice(
            "parentheses", "丸括弧", "全角（ ）", "半角 ( )", draft.punctuation.fullwidthParentheses,
        ) { host.updateDraft { punctuation = punctuation.copy(fullwidthParentheses = it) } })
        punctuationCategory.addPreference(booleanChoice(
            "brackets", "波括弧", "全角 ｛ ｝", "半角 { }", draft.punctuation.fullwidthBrackets,
        ) { host.updateDraft { punctuation = punctuation.copy(fullwidthBrackets = it) } })
        punctuationCategory.addPreference(booleanChoice(
            "symbols", "その他の記号", "全角", "半角", draft.punctuation.fullwidthSymbols,
        ) { host.updateDraft { punctuation = punctuation.copy(fullwidthSymbols = it) } })
    }

    private fun buildCandidates(screen: PreferenceScreen, draft: CustomizationDraft) {
        val value = draft.candidateDisplay
        val candidates = category("候補一覧")
        screen.addPreference(candidates)
        candidates.addPreference(listPreference(
            "labels", "候補の選択キー",
            arrayOf("asdfjkl", "1234567"), arrayOf("asdfjkl", "1234567"), value.labels,
        ) { host.updateDraft { candidateDisplay = candidateDisplay.copy(labels = it) } })
        candidates.addPreference(listPreference(
            "page_mode", "一覧の候補数",
            arrayOf("上限を指定", "画面に合わせる"), arrayOf("FIXED", "AUTO"), value.pageMode.name,
        ) {
            host.updateDraft { candidateDisplay = candidateDisplay.copy(pageMode = CandidatePageMode.valueOf(it)) }
            refresh()
        })
        if (value.pageMode == CandidatePageMode.FIXED) {
            candidates.addPreference(listPreference(
                "page_count", "一度に表示する候補",
                (1..7).map { "${it}件" }.toTypedArray(),
                (1..7).map(Int::toString).toTypedArray(),
                value.fixedPageSize.toString(),
            ) { host.updateDraft { candidateDisplay = candidateDisplay.copy(fixedPageSize = it.toInt()) } })
        }
        candidates.addPreference(listPreference(
            "menu_start", "候補一覧を開く位置",
            (1..10).map { "${it}番目の候補" }.toTypedArray(),
            (0..9).map(Int::toString).toTypedArray(),
            value.inlineCandidateCount.toString(),
        ) { host.updateDraft { candidateDisplay = candidateDisplay.copy(inlineCandidateCount = it.toInt()) } })
        candidates.addPreference(switchPreference(
            "markers", "未確定文字に ▽／▼ を表示する",
            "読みと候補選択の状態を文字の前に表示します。", value.showCompositionMarkers,
        ) { host.updateDraft { candidateDisplay = candidateDisplay.copy(showCompositionMarkers = it) } })
    }

    private fun buildKeys(screen: PreferenceScreen, draft: CustomizationDraft) {
        val emacs = category("Emacs編集")
        screen.addPreference(emacs)
        emacs.addPreference(listPreference(
            "emacs_editing_mode", "Emacs キーバインド",
            EmacsEditingMode.entries.map { it.label }.toTypedArray(),
            EmacsEditingMode.entries.map { it.name }.toTypedArray(),
            draft.emacsEditingMode.name,
        ) { selected -> host.updateDraft { emacsEditingMode = EmacsEditingMode.valueOf(selected) } })

        val keyBindings = category("キー割り当て")
        screen.addPreference(keyBindings)
        SkkCommand.entries.forEach { command ->
            keyBindings.addPreference(keyBindingPreference(command, draft.keyBindings.getValue(command)))
        }
    }

    private fun keyBindingPreference(command: SkkCommand, current: String) = EditTextPreference(requireContext()).apply {
        key = "key_binding_${command.name}"
        title = command.title
        dialogTitle = title
        isPersistent = false
        text = current
        summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
            primaryValue(preference.text?.ifBlank { "未割当" } ?: "未割当")
        }
        dialogMessage = if (command in KeyBindings.optionalCommands) {
            "例: C-j（Ctrl+j）。空欄は未割当です。"
        } else {
            "例: C-j（Ctrl+j）。この操作にはキーが必要です。"
        }
        setOnBindEditTextListener { field ->
            field.hint = "例: C-j"
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            field.selectAll()
        }
        setOnPreferenceChangeListener { _, next ->
            host.updateKeyBinding(command, next.toString())
        }
    }

    private fun category(titleText: String) = PreferenceCategory(requireContext()).apply {
        title = titleText
        isPersistent = false
    }

    private fun primaryValue(value: String): CharSequence = SpannableString(value).apply {
        setSpan(ForegroundColorSpan(primaryTextColor()), 0, length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun primaryTextColor(): Int {
        val attributes = requireContext().obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        return try {
            attributes.getColorStateList(0)?.defaultColor ?: 0
        } finally {
            attributes.recycle()
        }
    }

    internal fun keyBindingValidationError(command: SkkCommand, value: String): String? =
        host.keyBindingValidationError(command, value)

    private fun info(titleText: String, summaryText: String? = null) = Preference(requireContext()).apply {
        title = titleText
        summary = summaryText
        isSelectable = false
        isPersistent = false
    }

    private fun action(titleText: String, summaryText: String? = null, action: () -> Unit) =
        Preference(requireContext()).apply {
            title = titleText
            summary = summaryText
            isPersistent = false
            setOnPreferenceClickListener { action(); true }
        }

    private fun listPreference(
        preferenceKey: String,
        titleText: String,
        labels: Array<String>,
        values: Array<String>,
        selected: String,
        changed: (String) -> Unit,
    ) = ListPreference(requireContext()).apply {
        key = preferenceKey
        title = titleText
        dialogTitle = titleText
        entries = labels
        entryValues = values
        isPersistent = false
        setDefaultValue(selected)
        value = selected
        summary = labels.getOrNull(values.indexOf(selected)) ?: selected
        setOnPreferenceChangeListener { _, next ->
            changed(next.toString())
            refresh()
            true
        }
    }

    private fun booleanChoice(
        preferenceKey: String,
        titleText: String,
        trueLabel: String,
        falseLabel: String,
        selected: Boolean,
        changed: (Boolean) -> Unit,
    ) = listPreference(
        preferenceKey, titleText, arrayOf(trueLabel, falseLabel), arrayOf("true", "false"), selected.toString(),
    ) { changed(it.toBoolean()) }

    private fun switchPreference(
        preferenceKey: String,
        titleText: String,
        summaryText: String,
        checked: Boolean,
        changed: (Boolean) -> Unit,
    ) = SwitchPreferenceCompat(requireContext()).apply {
        widgetLayoutResource = R.layout.preference_widget_material_switch
        key = preferenceKey
        title = titleText
        isSingleLineTitle = false
        summary = summaryText
        isPersistent = false
        setDefaultValue(checked)
        isChecked = checked
        setOnPreferenceChangeListener { _, value -> changed(value as Boolean); true }
    }

    private fun ruleSummary(rule: RomajiRule): String = when {
        rule.terminalOutput != null -> "確定時に「${rule.terminalOutput}」"
        rule.remaining.isNotEmpty() -> "「${rule.output}」を入力し、${rule.remaining} を次へ持ち越す"
        else -> "「${rule.output}」を入力"
    }

    companion object {
        private const val ARG_PAGE = "page"
        private const val KEY_BINDING_PREFIX = "key_binding_"
        private const val DIALOG_TAG = "key_binding_dialog"

        fun newInstance(page: Int) = CustomizationPageFragment().apply {
            arguments = Bundle().apply { putInt(ARG_PAGE, page) }
        }
    }

}

/** AndroidX の標準 EditTextPreference ダイアログに、確定前の入力検証だけを追加します。 */
class KeyBindingPreferenceDialog : EditTextPreferenceDialogFragmentCompat() {
    private var input: EditText? = null

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        input = view.findViewById(android.R.id.edit)
        input?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateValidation()
        })
    }

    override fun onStart() {
        super.onStart()
        updateValidation()
    }

    override fun onDestroyView() {
        input = null
        super.onDestroyView()
    }

    private fun updateValidation() {
        val field = input ?: return
        val page = targetFragment as? CustomizationPageFragment ?: return
        val command = runCatching {
            SkkCommand.valueOf((getPreference() as EditTextPreference).key.removePrefix("key_binding_"))
        }.getOrNull() ?: return
        val error = page.keyBindingValidationError(command, field.text.toString().trim())
        field.error = error
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = error == null
    }

    companion object {
        fun newInstance(key: String) = KeyBindingPreferenceDialog().apply {
            arguments = Bundle(1).apply { putString("key", key) }
        }
    }
}
