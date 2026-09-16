package jp.hayase.skk

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import jp.hayase.skk.core.CandidateDisplayConfig
import jp.hayase.skk.core.CandidatePageMode
import jp.hayase.skk.core.PunctuationConfig
import jp.hayase.skk.core.romaji.RomajiRule
import jp.hayase.skk.core.romaji.Romanizer
import jp.hayase.skk.core.keys.KeyBindings
import jp.hayase.skk.core.keys.KeyGesture
import jp.hayase.skk.core.keys.SkkCommand
import jp.hayase.skk.settings.*

/** 編集中の値を公開設定から分離し、保存または明示的な取消だけで終了します。 */
class CustomizationSettingsActivity : Activity() {
    private lateinit var validation: ExecutorService
    private val controls = mutableListOf<View>()
    private val ruleControls = mutableListOf<View>()
    private lateinit var store: CustomizationStore
    private lateinit var layout: LinearLayout
    private lateinit var status: TextView
    private lateinit var profile: Spinner
    private lateinit var period: Spinner
    private lateinit var comma: Spinner
    private lateinit var parentheses: Spinner
    private lateinit var brackets: Spinner
    private lateinit var labels: Spinner
    private lateinit var pageMode: Spinner
    private lateinit var count: Spinner
    private lateinit var ruleList: Spinner
    private lateinit var input: EditText
    private lateinit var output: EditText
    private lateinit var remaining: EditText
    private lateinit var terminal: EditText
    private lateinit var terminalEnabled: Switch
    private lateinit var emacsEnabled: Switch
    private val bindingFields = linkedMapOf<SkkCommand, EditText>()
    private var rules = mutableListOf<RomajiRule>()
    private var loaded: CustomizationSettings? = null
    private var busy = false
    private var displayedRuleIndex = -1
    private var changingRuleSelection = false
    private var changingProfileSelection = false
    private var displayedProfileIndex = 0
    private var pendingOperation: PendingOperation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        validation = validationFactoryForTest?.invoke() ?: Executors.newSingleThreadExecutor()
        store = storeFactoryForTest?.invoke(this) ?: CustomizationRuntime.get(this)
        layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        label("入力規則・句読点・候補", 22f)
        label("保存した設定は次の入力欄から使います。入力中の文字や個人辞書は変更しません。")
        status = label("設定を読み込んでいます")
        profile = choice("ローマ字規則（標準: 標準規則）", listOf("標準規則", "カスタム規則", "AZIK規則"))
        button("標準規則をコピーして編集") {
            rules = Romanizer.standardRules.toMutableList()
            profile.setSelection(1)
            refreshRules()
        }
        ruleList = choice("編集する規則", listOf("規則なし")).also(ruleControls::add)
        input = field("入力列（英小文字・記号、最大16文字）", 16, literal = true)
        output = field("出力（最大64文字）", 64)
        remaining = field("残す入力列（省略可、最大16文字）", 16, literal = true)
        terminalEnabled = Switch(this).apply { text = "確定キーを押した時だけ出力する" }
        this@CustomizationSettingsActivity.layout.addView(terminalEnabled); controls += terminalEnabled; ruleControls += terminalEnabled
        terminal = field("終端出力（最大64文字）", 64)
        ruleControls += button("規則を追加") { updateRule(replace = false) }
        ruleControls += button("選択した規則を変更") { updateRule(replace = true) }
        ruleControls += button("選択した規則を削除") {
            if (ruleList.selectedItemPosition in rules.indices) {
                rules.removeAt(ruleList.selectedItemPosition); refreshRules()
            }
        }
        label("同じ入力列の重複・残余の循環・無効な文字は保存前に検査します。終端出力を使う規則では、通常出力と残余を空にします。")
        period = choice("句点（標準: 。）", listOf("。", "．", "."))
        comma = choice("読点（標準: 、）", listOf("、", "，", ","))
        parentheses = choice("丸括弧（標準: 全角）", listOf("全角（ ）", "半角( )"))
        brackets = choice("角括弧・波括弧（標準: 半角）", listOf("半角[]{}", "全角［］｛｝"))
        labels = choice("候補の選択キー（標準: asdfjkl）", listOf("asdfjkl", "1234567"))
        pageMode = choice("一覧の候補数（標準: 固定）", listOf("固定", "画面幅と文字サイズに合わせる"))
        count = choice("固定時の候補数（標準: 7）", (1..7).map(Int::toString))
        label("最初の3候補は単独表示です。表示中のラベルは画面幅が変わっても動かしません。")
        emacsEnabled = Switch(this).apply { text = "Emacs 編集キーを有効にする" }
        layout.addView(emacsEnabled); controls += emacsEnabled
        label("標準ではオフです。保存後、次の入力欄から反映します。")
        label("キー表記は C-（Ctrl）、M-（Alt）、S-（Shift）、U-（Shift を区別しない）、<ENTER>、<TAB>、<SPACE> を使います。例: C-g、M-b、U-q、U-Q")
        SkkCommand.entries.forEach { command ->
            label(command.title)
            bindingFields[command] = keyField(command.title)
            label("標準規則の初期キー: ${KeyGestureText.format(KeyBindings.defaults.getValue(command))}")
        }
        button("保存") { saveDraft() }
        button("変更を破棄して閉じる") { confirmDiscard() }
        button("入力設定を標準に戻す") {
            AlertDialog.Builder(this).setTitle("入力設定を標準に戻す")
                .setMessage("ローマ字規則・句読点・候補設定・Emacs 編集キー・各コマンドのキー設定を標準に戻して保存します。個人辞書と学習の設定は変更しません。")
                .setPositiveButton("標準に戻す") { _, _ ->
                    resetToDefaults()
                }.setNegativeButton("戻る", null).show()
        }
        button("保存済み設定を再読込する") {
            AlertDialog.Builder(this).setMessage("編集内容を破棄して、保存済み設定を読み直します。")
                .setPositiveButton("再読込する") { _, _ -> load() }.setNegativeButton("戻る", null).show()
        }
        profile.onItemSelectedListener = selectionListener { selectProfile() }
        ruleList.onItemSelectedListener = selectionListener { selectRule() }
        pageMode.onItemSelectedListener = selectionListener { updateEnabled() }
        setContentView(ScrollView(this).apply { addView(layout) })
        applySystemInsets()
        val retained = lastNonConfigurationInstance as? RetainedDraft
        if (retained != null) {
            restoreDraft(retained.draft)
            pendingOperation = retained.pendingOperation
            retained.pendingOperation?.let(::reconcilePendingOperation)
        } else load()
    }

    private fun load() {
        setBusy(true)
        store.loadAsync { result ->
            if (!active()) return@loadAsync
            populate(result.settings)
            status.text = when (result) {
                is CustomizationStoreStatus.Ready -> "保存済み設定を表示しています"
                is CustomizationStoreStatus.DefaultDueToCorrupt -> "設定を読み取れません。元のファイルを残し、標準値を表示しています。保存または標準へ戻す操作で復旧できます。"
                is CustomizationStoreStatus.Error -> "設定を読み込めません。再読込してください。保存済みファイルは変更していません。"
                is CustomizationStoreStatus.Loading -> "設定を読み込んでいます"
            }
            setBusy(false)
        }
    }

    private fun populate(value: CustomizationSettings) {
        loaded = value
        rules = value.customRules.toMutableList()
        changingProfileSelection = true
        profile.setSelection(profileIndex(value.profile))
        displayedProfileIndex = profile.selectedItemPosition
        changingProfileSelection = false
        period.setSelection(listOf("。", "．", ".").indexOf(value.punctuation.period))
        comma.setSelection(listOf("、", "，", ",").indexOf(value.punctuation.comma))
        parentheses.setSelection(if (value.punctuation.fullwidthParentheses) 0 else 1)
        brackets.setSelection(if (value.punctuation.fullwidthBrackets) 1 else 0)
        labels.setSelection(if (value.candidateDisplay.labels == "asdfjkl") 0 else 1)
        pageMode.setSelection(if (value.candidateDisplay.pageMode == CandidatePageMode.FIXED) 0 else 1)
        count.setSelection(value.candidateDisplay.fixedPageSize - 1)
        emacsEnabled.isChecked = value.emacsEnabled
        value.keyBindings.bindings.forEach { (command, key) ->
            bindingFields.getValue(command).setText(KeyGestureText.format(key))
        }
        refreshRules()
    }

    private fun saveDraft() {
        val draft = captureDraft() ?: return
        if (draft.profile == profileIndex(CustomizationProfile.CUSTOM)) {
            val editing = draft.ruleEditor.let {
                RomajiRule(it.input, it.output, it.remaining, if (it.terminalEnabled) it.terminal else null)
            }
            val selected = draft.rules.getOrNull(draft.ruleSelection) ?: RomajiRule("", "")
            if (editing != selected) {
                status.text = "編集中の規則を「追加」または「変更」してから保存してください。"
                return
            }
        }
        setBusy(true)
        status.text = "設定を検証しています"
        validation.execute {
            val result = runCatching {
                CustomizationSettings(draft.base.generation,
                    profileForIndex(draft.profile),
                    if (draft.profile == profileIndex(CustomizationProfile.CUSTOM)) draft.rules else emptyList(),
                    PunctuationConfig(listOf("。", "．", ".")[draft.period], listOf("、", "，", ",")[draft.comma],
                        draft.parentheses == 0, draft.brackets == 1),
                    CandidateDisplayConfig(listOf("asdfjkl", "1234567")[draft.labels],
                        if (draft.pageMode == 0) CandidatePageMode.FIXED else CandidatePageMode.AUTO, draft.count + 1),
                    draft.emacsEnabled,
                    KeyBindings(SkkCommand.entries.associateWith { command ->
                        KeyGestureText.parse(draft.keyBindings.getValue(command))
                    }))
            }
            runOnUiThread {
                if (!active()) return@runOnUiThread
                result.fold(
                    onSuccess = {
                        pendingOperation = PendingOperation(it, draft.base.generation, false)
                        store.save(it, draft.base.generation, ::onSaved)
                    },
                    onFailure = {
                        status.text = "設定を保存できません: ${it.message ?: "入力内容を確認してください"}"
                        setBusy(false)
                    },
                )
            }
        }
    }

    private fun onSaved(result: CustomizationWriteResult) {
        if (!active()) return
        val reset = pendingOperation?.reset == true
        pendingOperation = null
        when (result) {
            is CustomizationWriteResult.Applied -> {
                populate(result.settings)
                status.text = if (reset) "入力設定を標準に戻しました。次の入力欄から反映します。"
                    else "入力設定を保存しました。次の入力欄から反映します。"
            }
            is CustomizationWriteResult.Conflict -> status.text = "別の画面で設定が更新されました。編集内容は保持しています。保存済み設定を再読込して確認してください。"
            is CustomizationWriteResult.Failed -> status.text = "入力設定を保存できませんでした。編集内容と以前の設定を保持します。"
        }
        setBusy(false)
    }

    private fun resetToDefaults() {
        loaded?.let { current ->
            pendingOperation = PendingOperation(
                CustomizationSettings.defaults(current.generation), current.generation, true,
            )
            setBusy(true); store.reset(current.generation, ::onSaved)
        }
    }

    private fun updateRule(replace: Boolean) {
        if (rules.size >= 4_096 && !replace) { status.text = "規則は4,096件までです"; return }
        val value = RomajiRule(input.text.toString(), output.text.toString(), remaining.text.toString(),
            if (terminalEnabled.isChecked) terminal.text.toString() else null)
        if (replace) {
            val index = ruleList.selectedItemPosition
            if (index !in rules.indices) return
            rules[index] = value
        } else rules += value
        refreshRules(if (replace) ruleList.selectedItemPosition else rules.lastIndex)
        status.text = "編集内容は未保存です。保存時にすべての規則を検証します。"
    }

    private fun refreshRules(selection: Int = 0) {
        changingRuleSelection = true
        ruleList.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (rules.isEmpty()) listOf("規則なし") else rules.map { "${it.input} → ${it.terminalOutput ?: it.output}" })
        ruleList.setSelection(selection.coerceIn(0, (rules.size - 1).coerceAtLeast(0)))
        fillRule(force = true)
        changingRuleSelection = false
        updateEnabled()
    }

    private fun selectRule() {
        if (changingRuleSelection) return
        val next = ruleList.selectedItemPosition
        if (next != displayedRuleIndex && hasUnappliedRuleEditor()) {
            status.text = "編集中の規則を「追加」または「変更」してから別の規則を選んでください。"
            changingRuleSelection = true
            ruleList.setSelection(displayedRuleIndex.coerceIn(0, (rules.size - 1).coerceAtLeast(0)))
            changingRuleSelection = false
            return
        }
        fillRule()
    }

    private fun hasUnappliedRuleEditor(): Boolean {
        val editor = RuleEditor(input.text.toString(), output.text.toString(), remaining.text.toString(),
            terminal.text.toString(), terminalEnabled.isChecked)
        val selected = rules.getOrNull(displayedRuleIndex)
        return if (selected == null) editor != RuleEditor("", "", "", "", false)
        else editor != RuleEditor(selected.input, selected.output, selected.remaining,
            selected.terminalOutput.orEmpty(), selected.terminalOutput != null)
    }

    private fun fillRule(force: Boolean = false) {
        if (!force && displayedRuleIndex == ruleList.selectedItemPosition) return
        displayedRuleIndex = ruleList.selectedItemPosition
        val value = rules.getOrNull(ruleList.selectedItemPosition)
        input.setText(value?.input.orEmpty()); output.setText(value?.output.orEmpty())
        remaining.setText(value?.remaining.orEmpty()); terminal.setText(value?.terminalOutput.orEmpty())
        terminalEnabled.isChecked = value?.terminalOutput != null
    }

    private fun selectProfile() {
        if (changingProfileSelection) return
        val next = profile.selectedItemPosition
        val previous = displayedProfileIndex
        if (next != previous) {
            val toggle = bindingFields[SkkCommand.TOGGLE_KANA]
            val oldDefault = KeyGestureText.format(defaultToggleFor(profileForIndex(previous)))
            if (toggle?.text?.toString() == oldDefault) {
                toggle.setText(KeyGestureText.format(defaultToggleFor(profileForIndex(next))))
            }
            displayedProfileIndex = next
        }
        updateEnabled()
    }

    private fun profileIndex(value: CustomizationProfile): Int = when (value) {
        CustomizationProfile.STANDARD -> 0
        CustomizationProfile.CUSTOM -> 1
        CustomizationProfile.AZIK -> 2
    }

    private fun profileForIndex(value: Int): CustomizationProfile = when (value) {
        0 -> CustomizationProfile.STANDARD
        1 -> CustomizationProfile.CUSTOM
        2 -> CustomizationProfile.AZIK
        else -> throw IllegalArgumentException("ローマ字規則の選択が不正です")
    }

    private fun defaultToggleFor(value: CustomizationProfile): KeyGesture =
        if (value == CustomizationProfile.AZIK) KeyGesture("[", ignoreShift = true)
        else KeyBindings.defaults.getValue(SkkCommand.TOGGLE_KANA)

    private fun label(text: String, size: Float = 16f): TextView = TextView(this).apply {
        this.text = text; textSize = size; this@CustomizationSettingsActivity.layout.addView(this)
    }
    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text; setOnClickListener { action() }; this@CustomizationSettingsActivity.layout.addView(this); controls += this
    }
    private fun choice(title: String, values: List<String>): Spinner {
        label(title)
        return Spinner(this).apply {
            contentDescription = title
            adapter = ArrayAdapter(this@CustomizationSettingsActivity, android.R.layout.simple_spinner_dropdown_item, values)
            this@CustomizationSettingsActivity.layout.addView(this); controls += this
        }
    }
    private fun field(title: String, max: Int, literal: Boolean = false): EditText {
        label(title)
        return EditText(this).apply {
            contentDescription = title
            inputType = InputType.TYPE_CLASS_TEXT or if (literal) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(max))
            this@CustomizationSettingsActivity.layout.addView(this); controls += this; ruleControls += this
        }
    }
    private fun keyField(title: String): EditText = EditText(this).apply {
        contentDescription = title
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(40))
        this@CustomizationSettingsActivity.layout.addView(this); controls += this
    }
    private fun selectionListener(action: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action()
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
    private fun setBusy(value: Boolean) { busy = value; updateEnabled() }
    private fun updateEnabled() {
        controls.forEach { it.isEnabled = !busy }
        ruleControls.forEach { it.isEnabled = !busy && profile.selectedItemPosition == profileIndex(CustomizationProfile.CUSTOM) }
        if (::count.isInitialized) count.isEnabled = !busy && pageMode.selectedItemPosition == 0
    }
    private fun active() = !isFinishing && !isDestroyed
    private fun confirmDiscard() {
        if (busy) return
        AlertDialog.Builder(this).setMessage("保存していない変更を破棄して閉じますか。")
            .setPositiveButton("破棄して閉じる") { _, _ -> finish() }.setNegativeButton("編集を続ける", null).show()
    }
    @Deprecated("Androidの戻る操作を確認付きで扱います")
    override fun onBackPressed() = confirmDiscard()
    override fun onDestroy() { validation.shutdown(); super.onDestroy() }

    private data class Draft(val base: CustomizationSettings, val rules: List<RomajiRule>, val profile: Int,
        val period: Int, val comma: Int, val parentheses: Int, val brackets: Int, val labels: Int,
        val pageMode: Int, val count: Int, val ruleSelection: Int, val ruleEditor: RuleEditor,
        val emacsEnabled: Boolean, val keyBindings: Map<SkkCommand, String>)
    private data class RuleEditor(val input: String, val output: String, val remaining: String,
        val terminal: String, val terminalEnabled: Boolean)
    private data class PendingOperation(
        val requested: CustomizationSettings,
        val expectedGeneration: Long,
        val reset: Boolean,
    )
    private data class RetainedDraft(val draft: Draft, val pendingOperation: PendingOperation?)
    private fun captureDraft(): Draft? = loaded?.let {
        Draft(it, rules.toList(), profile.selectedItemPosition, period.selectedItemPosition, comma.selectedItemPosition,
            parentheses.selectedItemPosition, brackets.selectedItemPosition, labels.selectedItemPosition,
            pageMode.selectedItemPosition, count.selectedItemPosition, ruleList.selectedItemPosition,
            RuleEditor(input.text.toString(), output.text.toString(), remaining.text.toString(),
                terminal.text.toString(), terminalEnabled.isChecked), emacsEnabled.isChecked,
            bindingFields.mapValues { (_, field) -> field.text.toString() }.toMap())
    }
    private fun restoreDraft(draft: Draft) {
        populate(draft.base); rules = draft.rules.toMutableList()
        changingProfileSelection = true
        profile.setSelection(draft.profile); displayedProfileIndex = draft.profile
        changingProfileSelection = false
        period.setSelection(draft.period); comma.setSelection(draft.comma)
        parentheses.setSelection(draft.parentheses); brackets.setSelection(draft.brackets)
        labels.setSelection(draft.labels); pageMode.setSelection(draft.pageMode); count.setSelection(draft.count)
        refreshRules(draft.ruleSelection)
        input.setText(draft.ruleEditor.input); output.setText(draft.ruleEditor.output)
        remaining.setText(draft.ruleEditor.remaining); terminal.setText(draft.ruleEditor.terminal)
        terminalEnabled.isChecked = draft.ruleEditor.terminalEnabled
        emacsEnabled.isChecked = draft.emacsEnabled
        draft.keyBindings.forEach { (command, text) -> bindingFields.getValue(command).setText(text) }
        updateEnabled()
        status.text = "編集内容を保持しました。変更は未保存です。"
    }
    private fun reconcilePendingOperation(pending: PendingOperation) {
        setBusy(true)
        store.loadAsync { current ->
            if (!active()) return@loadAsync
            val expected = runCatching { pending.requested.withGeneration(Math.addExact(pending.expectedGeneration, 1)) }
                .getOrNull()
            pendingOperation = null
            if (expected != null && current.settings == expected) {
                populate(expected)
                status.text = if (pending.reset) "入力設定を標準に戻しました。次の入力欄から反映します。"
                else "入力設定を保存しました。次の入力欄から反映します。"
            } else {
                status.text = "保存結果を確認できませんでした。編集内容を保持しています。保存済み設定を再読込して確認してください。"
            }
            setBusy(false)
        }
    }
    @Deprecated("未保存の編集はプロセス内の画面再作成で保持します")
    override fun onRetainNonConfigurationInstance(): Any? =
        captureDraft()?.let { RetainedDraft(it, pendingOperation) }

    internal companion object {
        var storeFactoryForTest: ((android.content.Context) -> CustomizationStore)? = null
        var validationFactoryForTest: (() -> ExecutorService)? = null
    }
}
