package se.haya.skk

import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import se.haya.skk.core.CandidateDisplayConfig
import se.haya.skk.core.PunctuationConfig
import se.haya.skk.core.keys.KeyBindings
import se.haya.skk.core.keys.KeyGesture
import se.haya.skk.core.keys.SkkCommand
import se.haya.skk.core.romaji.RomajiRule
import se.haya.skk.settings.CustomizationProfile
import se.haya.skk.settings.AppEmacsEditingRuntime
import se.haya.skk.settings.AppEmacsEditingStore
import se.haya.skk.settings.CustomizationRuntime
import se.haya.skk.settings.CustomizationSettings
import se.haya.skk.settings.EmacsEditingMode
import se.haya.skk.settings.CustomizationStore
import se.haya.skk.settings.CustomizationStoreFailure
import se.haya.skk.settings.CustomizationStoreStatus
import se.haya.skk.settings.CustomizationWriteResult
import se.haya.skk.settings.KeyGestureText

/** 一つの目的だけを表示し、下書きを保存するまで公開設定から分離します。 */
class CustomizationSettingsActivity : SettingsPageActivity() {
    private lateinit var validation: ExecutorService
    private lateinit var store: CustomizationStore
    private lateinit var appEmacsStore: AppEmacsEditingStore
    private lateinit var pageFragment: CustomizationPageFragment
    private var draft: CustomizationDraft? = null
    private var busy = true
    private var statusText: CharSequence? = "設定を読み込んでいます"
    private var pendingOperation: PendingOperation? = null
    private var page = PAGE_ROMAJI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        validation = validationFactoryForTest?.invoke() ?: Executors.newSingleThreadExecutor()
        store = storeFactoryForTest?.invoke(this) ?: CustomizationRuntime.get(this)
        appEmacsStore = appEmacsStoreFactoryForTest?.invoke(this) ?: AppEmacsEditingRuntime.get(this)
        page = (savedInstanceState?.getInt(STATE_PAGE)
            ?: intent.getIntExtra(EXTRA_SECTION, PAGE_ROMAJI)).coerceIn(PAGE_ROMAJI, PAGE_KEYS)

        val requestedPage = CustomizationPageFragment.newInstance(page)
        installSettingsPage(pageTitle(page), requestedPage)
        pageFragment = supportFragmentManager.findFragmentById(R.id.settings_content)
            as? CustomizationPageFragment ?: requestedPage
        setPageActions(::saveAndClose, ::resetDraftToDefaults)
        renderShell()

        val retained = lastCustomNonConfigurationInstance as? RetainedDraft
        if (retained != null) {
            draft = retained.draft
            pendingOperation = retained.pendingOperation
            busy = retained.pendingOperation != null
            statusText = if (busy) "保存結果を確認しています" else "編集内容は未保存です"
            pageFragment.refresh()
            renderShell()
            retained.pendingOperation?.let(::reconcilePendingOperation)
        } else {
            load()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PAGE, page)
        super.onSaveInstanceState(outState)
    }

    private fun load() {
        busy = true
        statusText = "設定を読み込んでいます"
        renderShell()
        store.loadAsync { result ->
            if (!active()) return@loadAsync
            finishLoadAfterMigration(result)
        }
    }

    private fun finishLoadAfterMigration(result: CustomizationStoreStatus) {
        appEmacsStore.migrateLegacy(result.settings.internalEmacsEnabled) { migrated ->
            if (!active()) return@migrateLegacy
            if (!migrated) {
                busy = true
                statusText = "アプリ別 Emacs 編集の旧設定を移行できませんでした。"
                renderShell()
                MaterialAlertDialogBuilder(this)
                    .setTitle("旧設定を移行できません")
                    .setMessage("全体設定を変更する前に、アプリ別設定の保存を再試行します。")
                    .setPositiveButton("再試行") { _, _ -> finishLoadAfterMigration(result) }
                    .setNegativeButton("閉じる") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
                return@migrateLegacy
            }
            draft = CustomizationDraft.from(result.settings)
            busy = false
            statusText = when (result) {
                is CustomizationStoreStatus.Ready -> null
                is CustomizationStoreStatus.DefaultDueToCorrupt ->
                    "設定を読み取れないため、この画面には標準値を表示しています。"
                is CustomizationStoreStatus.Error -> "設定を読み込めませんでした。画面を開き直してください。"
                is CustomizationStoreStatus.Loading -> "設定を読み込んでいます"
            }
            pageFragment.refresh()
            renderShell()
        }
    }

    public override fun saveAndClose() {
        if (busy) return
        val current = draft ?: return
        busy = true
        statusText = "設定を確認しています"
        renderShell()
        val snapshot = current.copy(
            rules = current.rules.toMutableList(),
            keyBindings = current.keyBindings.toMutableMap(),
        )
        validation.execute {
            val result = runCatching { snapshot.toSettings() }
            runOnUiThread {
                if (!active()) return@runOnUiThread
                result.fold(
                    onSuccess = { settings ->
                        pendingOperation = PendingOperation(settings, snapshot.base.generation)
                        statusText = "設定を保存しています"
                        renderShell()
                        store.save(settings, snapshot.base.generation, ::onSaved)
                    },
                    onFailure = { error ->
                        busy = false
                        statusText = "保存できません: ${error.message ?: "入力内容を確認してください"}"
                        renderShell()
                    },
                )
            }
        }
    }

    private fun onSaved(result: CustomizationWriteResult) {
        if (!active()) return
        pendingOperation = null
        when (result) {
            is CustomizationWriteResult.Applied -> {
                draft = CustomizationDraft.from(result.settings)
                busy = false
                statusText = "保存しました。次の入力欄から反映します。"
                renderShell()
                finish()
            }
            is CustomizationWriteResult.Conflict -> {
                busy = false
                reconcileConflict()
            }
            is CustomizationWriteResult.Failed -> {
                busy = false
                statusText = if (result.failure == CustomizationStoreFailure.INVALID_DATA) {
                    "設定の内容を確認してください。変更内容は残しています。"
                } else {
                    "設定を保存できませんでした。変更内容は残しています。"
                }
                renderShell()
            }
        }
    }

    private fun reconcileConflict() {
        busy = true
        statusText = "別の画面の変更を確認しています"
        renderShell()
        store.loadAsync { current ->
            if (!active()) return@loadAsync
            val local = draft
            if (local == null) {
                busy = false
                statusText = "最新の設定を読み込めませんでした。画面を開き直してください。"
                renderShell()
                return@loadAsync
            }
            val rebase = local.rebaseOnto(current.settings)
            busy = false
            if (rebase.conflictCount == 0) {
                draft = rebase.localWins
                statusText = "別の画面の変更を取り込みました。保存をもう一度押してください。"
                pageFragment.refresh()
                renderShell()
                return@loadAsync
            }
            statusText = "同じ項目が別の画面でも変更されました。どちらを残すか選んでください。"
            renderShell()
            MaterialAlertDialogBuilder(this)
                .setTitle("同じ項目に別の変更があります")
                .setMessage("自分の変更を残すか、別の画面で保存された最新値を使うか選んでください。ほかの項目の変更は両方とも残します。")
                .setPositiveButton("自分の変更を残す") { _, _ -> applyRebase(rebase.localWins) }
                .setNegativeButton("最新値を使う") { _, _ -> applyRebase(rebase.remoteWins) }
                .setNeutralButton("キャンセル", null)
                .show()
        }
    }

    private fun applyRebase(rebased: CustomizationDraft) {
        draft = rebased
        statusText = "変更を整理しました。保存をもう一度押してください。"
        pageFragment.refresh()
        renderShell()
    }

    /** シェル側で確認済みなので、この画面の値だけを下書きへ戻します。 */
    public override fun resetDraftToDefaults() {
        if (busy) return
        draft?.resetPage(page)
        statusText = "標準値を表示しています。保存すると反映します。"
        pageFragment.refresh()
        renderShell()
    }

    public override fun discardAndClose() {
        pendingOperation = null
        super.discardAndClose()
    }

    internal fun currentDraft(): CustomizationDraft? = draft

    internal fun updateDraft(change: CustomizationDraft.() -> Unit) {
        if (busy) return
        draft?.change()
        statusText = if (isDirty()) "変更はまだ保存されていません" else null
        renderShell()
    }

    internal fun editRule(index: Int?) {
        if (busy) return
        val current = draft ?: return
        val existing = index?.let(current.rules::getOrNull)
        showRuleDialog(existing) { rule ->
            updateDraft {
                if (index == null) rules += rule else rules[index] = rule
                profile = CustomizationProfile.CUSTOM
            }
            pageFragment.refresh()
        }
    }

    private fun deleteRule(index: Int) {
        val rule = draft?.rules?.getOrNull(index) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("規則を削除する")
            .setMessage("${rule.input} → ${rule.terminalOutput ?: rule.output} を削除します。")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("削除") { _, _ ->
                updateDraft { rules.removeAt(index) }
                pageFragment.refresh()
            }
            .show()
    }

    /** 標準の EditTextPreference から受け取り、下書きへ反映する前に構文を検証します。 */
    internal fun updateKeyBinding(command: SkkCommand, value: String): Boolean {
        if (busy) return false
        val text = value.trim()
        val error = keyBindingValidationError(command, text)
        if (error != null) {
            statusText = "変更できません: $error"
            renderShell()
            return false
        }
        updateDraft { keyBindings[command] = text }
        return true
    }

    internal fun keyBindingValidationError(command: SkkCommand, value: String): String? =
        validateKeyText(command, value)

    private fun validateKeyText(command: SkkCommand, text: String): String? = when {
        text.isBlank() && command !in KeyBindings.optionalCommands -> "この操作にはキーが必要です"
        text.isBlank() -> null
        else -> runCatching { KeyGestureText.parse(text) }.exceptionOrNull()?.message
    }

    private fun showRuleDialog(existing: RomajiRule?, accept: (RomajiRule) -> Unit) {
        val input = ruleField("打つ文字", existing?.input.orEmpty(), 16, literal = true)
        val output = ruleField("入力される文字", existing?.output.orEmpty(), 64)
        val remaining = ruleField("次へ持ち越す文字", existing?.remaining.orEmpty(), 16, literal = true)
        val terminalEnabled = CheckBox(this).apply {
            text = "確定キーを押したときだけ出力する"
            isChecked = existing?.terminalOutput != null
        }
        val terminal = ruleField("確定時の出力", existing?.terminalOutput.orEmpty(), 64)
        fun updateTerminalState() {
            terminal.isEnabled = terminalEnabled.isChecked
            output.isEnabled = !terminalEnabled.isChecked
            remaining.isEnabled = !terminalEnabled.isChecked
        }
        terminalEnabled.setOnCheckedChangeListener { _, _ -> updateTerminalState() }
        updateTerminalState()
        val form = dialogContainer().apply {
            addLabeledField(this, "打つ文字（例: ka）", input)
            addLabeledField(this, "入力される文字（例: か）", output)
            addLabeledField(this, "次へ持ち越す文字（例: tt の t）", remaining)
            addView(terminalEnabled)
            addLabeledField(this, "確定時の出力", terminal)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "規則を追加" else "規則を編集")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton(android.R.string.cancel, null)
            .apply { if (existing != null) setNeutralButton("削除", null) }
            .setPositiveButton(if (existing == null) "追加" else "変更", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = RomajiRule(
                    input.text.toString(),
                    if (terminalEnabled.isChecked) "" else output.text.toString(),
                    if (terminalEnabled.isChecked) "" else remaining.text.toString(),
                    if (terminalEnabled.isChecked) terminal.text.toString() else null,
                )
                val error = validateRule(value, existing)
                if (error != null) input.error = error else {
                    accept(value)
                    dialog.dismiss()
                }
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    val index = draft?.rules?.indexOf(existing) ?: -1
                    dialog.dismiss()
                    if (index >= 0) deleteRule(index)
                }
            }
        }
        dialog.show()
    }

    private fun validateRule(value: RomajiRule, existing: RomajiRule?): String? {
        val current = draft ?: return "設定を読み込めません"
        if (value.input.isBlank()) return "打つ文字を入力してください"
        if (current.rules.size >= 4_096 && existing == null) return "規則は4,096件までです"
        if (current.rules.any { it !== existing && it.input == value.input }) return "同じ打つ文字の規則があります"
        return runCatching {
            val candidate = current.rules.toMutableList().apply {
                if (existing == null) add(value) else set(indexOf(existing), value)
            }
            current.toSettings(profile = CustomizationProfile.CUSTOM, rules = candidate)
        }.exceptionOrNull()?.message
    }

    private fun ruleField(description: String, value: String, max: Int, literal: Boolean = false) =
        EditText(this).apply {
            contentDescription = description
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or if (literal) {
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            filters = arrayOf(InputFilter.LengthFilter(max))
        }

    private fun dialogContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding / 2, padding, 0)
    }

    private fun addLabeledField(parent: LinearLayout, label: String, field: EditText) {
        parent.addView(TextView(this).apply { text = label })
        parent.addView(field)
    }

    private fun renderShell() {
        renderPageState(isDirty(), busy, statusText)
        if (::pageFragment.isInitialized) pageFragment.setControlsEnabled(!busy)
    }

    private fun isDirty(): Boolean = draft?.isPageDirty(page) == true
    private fun active(): Boolean = !isFinishing && !isDestroyed

    private fun reconcilePendingOperation(pending: PendingOperation) {
        store.loadAsync { current ->
            if (!active()) return@loadAsync
            val expected = runCatching {
                pending.requested.withGeneration(Math.addExact(pending.expectedGeneration, 1))
            }.getOrNull()
            pendingOperation = null
            if (expected != null && current.settings == expected) {
                draft = CustomizationDraft.from(expected)
                busy = false
                statusText = "保存しました。次の入力欄から反映します。"
                renderShell()
                finish()
            } else {
                busy = false
                statusText = "保存結果を確認できませんでした。変更内容は残しています。"
                renderShell()
            }
        }
    }

    @Deprecated("未保存の編集はプロセス内の画面再作成で保持します")
    override fun onRetainCustomNonConfigurationInstance(): Any? =
        draft?.let { RetainedDraft(it, pendingOperation) }

    override fun onDestroy() {
        validation.shutdown()
        super.onDestroy()
    }

    private data class PendingOperation(val requested: CustomizationSettings, val expectedGeneration: Long)
    private data class RetainedDraft(val draft: CustomizationDraft, val pendingOperation: PendingOperation?)

    internal companion object {
        const val EXTRA_SECTION = "settings_section"
        const val PAGE_ROMAJI = 0
        const val PAGE_PUNCTUATION = 1
        const val PAGE_CANDIDATES = 2
        const val PAGE_KEYS = 3
        private const val STATE_PAGE = "customization_page"

        var storeFactoryForTest: ((android.content.Context) -> CustomizationStore)? = null
        var appEmacsStoreFactoryForTest: ((android.content.Context) -> AppEmacsEditingStore)? = null
        var validationFactoryForTest: (() -> ExecutorService)? = null

        private fun pageTitle(page: Int): String = when (page) {
            PAGE_ROMAJI -> "ローマ字の打ち方"
            PAGE_PUNCTUATION -> "句読点と記号"
            PAGE_CANDIDATES -> "候補の表示と選び方"
            else -> "キー操作とEmacs編集"
        }
    }
}

internal data class CustomizationDraft(
    var base: CustomizationSettings,
    var profile: CustomizationProfile,
    val rules: MutableList<RomajiRule>,
    var punctuation: PunctuationConfig,
    var candidateDisplay: CandidateDisplayConfig,
    var emacsEnabled: Boolean,
    var internalEmacsEnabled: Boolean,
    val keyBindings: MutableMap<SkkCommand, String>,
    var ruleSearch: String = "",
) {
    var emacsEditingMode: EmacsEditingMode
        get() = when {
            emacsEnabled -> EmacsEditingMode.IME_AND_APP
            internalEmacsEnabled -> EmacsEditingMode.IME_ONLY
            else -> EmacsEditingMode.DISABLED
        }
        set(value) {
            when (value) {
                EmacsEditingMode.DISABLED -> { emacsEnabled = false; internalEmacsEnabled = false }
                EmacsEditingMode.IME_ONLY -> { emacsEnabled = false; internalEmacsEnabled = true }
                EmacsEditingMode.IME_AND_APP -> { emacsEnabled = true; internalEmacsEnabled = false }
            }
        }

    fun parsedKeyBindings(): KeyBindings = KeyBindings(SkkCommand.entries.mapNotNull { command ->
        val text = keyBindings.getValue(command)
        if (text.isBlank() && command in KeyBindings.optionalCommands) null
        else command to KeyGestureText.parse(text)
    }.toMap())

    fun toSettings(
        profile: CustomizationProfile = this.profile,
        rules: List<RomajiRule> = this.rules,
    ): CustomizationSettings = CustomizationSettings(
        base.generation,
        profile,
        if (profile == CustomizationProfile.CUSTOM) rules else emptyList(),
        punctuation,
        candidateDisplay,
        emacsEnabled, parsedKeyBindings(), internalEmacsEnabled,
    ).withEmacsEditingMode(emacsEditingMode)

    fun resetPage(page: Int) {
        when (page) {
            CustomizationSettingsActivity.PAGE_ROMAJI -> {
                profile = CustomizationProfile.STANDARD
                rules.clear()
                ruleSearch = ""
            }
            CustomizationSettingsActivity.PAGE_PUNCTUATION -> punctuation = PunctuationConfig()
            CustomizationSettingsActivity.PAGE_CANDIDATES -> candidateDisplay = CandidateDisplayConfig()
            CustomizationSettingsActivity.PAGE_KEYS -> {
                emacsEnabled = false
                internalEmacsEnabled = false
                keyBindings.clear()
                keyBindings.putAll(defaultBindings(profile).mapValues { (_, value) -> KeyGestureText.format(value) })
                KeyBindings.optionalCommands.filterNot(keyBindings::containsKey).forEach { keyBindings[it] = "" }
            }
        }
    }

    fun isPageDirty(page: Int): Boolean = when (page) {
        CustomizationSettingsActivity.PAGE_ROMAJI -> profile != base.profile ||
            (if (profile == CustomizationProfile.CUSTOM) rules else emptyList()) != base.customRules
        CustomizationSettingsActivity.PAGE_PUNCTUATION -> punctuation != base.punctuation
        CustomizationSettingsActivity.PAGE_CANDIDATES -> candidateDisplay != base.candidateDisplay
        else -> emacsEnabled != base.emacsEnabled || internalEmacsEnabled != base.internalEmacsEnabled ||
            keyBindings.any { (command, text) ->
                text != base.keyBindings.bindings[command]?.let(KeyGestureText::format).orEmpty()
            }
    }

    /** 保存済みの最新値を土台にし、互いに触っていない項目を三方向マージします。 */
    fun rebaseOnto(latest: CustomizationSettings): CustomizationRebase {
        var conflicts = 0
        fun <T> merged(old: T, local: T, new: T, preferLocal: Boolean): T = when {
            local == old -> new
            new == old || local == new -> local
            else -> { conflicts++; if (preferLocal) local else new }
        }
        fun build(preferLocal: Boolean, countConflicts: Boolean): CustomizationDraft {
            val before = conflicts
            val romaji = merged(base.profile to base.customRules, profile to rules.toList(),
                latest.profile to latest.customRules, preferLocal)
            val punctuationValue = PunctuationConfig(
                merged(base.punctuation.period, punctuation.period, latest.punctuation.period, preferLocal),
                merged(base.punctuation.comma, punctuation.comma, latest.punctuation.comma, preferLocal),
                merged(base.punctuation.fullwidthParentheses, punctuation.fullwidthParentheses,
                    latest.punctuation.fullwidthParentheses, preferLocal),
                merged(base.punctuation.fullwidthBrackets, punctuation.fullwidthBrackets,
                    latest.punctuation.fullwidthBrackets, preferLocal),
                merged(base.punctuation.fullwidthSymbols, punctuation.fullwidthSymbols,
                    latest.punctuation.fullwidthSymbols, preferLocal),
            )
            // labels と fixedPageSize には相互制約があるため、候補表示は一つの設定として選びます。
            val candidates = merged(base.candidateDisplay, candidateDisplay, latest.candidateDisplay, preferLocal)
            val bindings = SkkCommand.entries.associateWith { command ->
                val old = base.keyBindings.bindings[command]?.let(KeyGestureText::format).orEmpty()
                val new = latest.keyBindings.bindings[command]?.let(KeyGestureText::format).orEmpty()
                merged(old, keyBindings.getValue(command), new, preferLocal)
            }.toMutableMap()
            val result = CustomizationDraft(
                latest, romaji.first, romaji.second.toMutableList(), punctuationValue, candidates,
                false, false,
                bindings, ruleSearch,
            )
            result.emacsEditingMode = merged(base.emacsEditingMode, emacsEditingMode,
                latest.emacsEditingMode, preferLocal)
            if (!countConflicts) conflicts = before
            return result
        }
        val localWins = build(preferLocal = true, countConflicts = true)
        val conflictCount = conflicts
        val remoteWins = build(preferLocal = false, countConflicts = false)
        return CustomizationRebase(localWins, remoteWins, conflictCount)
    }

    companion object {
        fun from(value: CustomizationSettings): CustomizationDraft = CustomizationDraft(
            value,
            value.profile,
            value.customRules.toMutableList(),
            value.punctuation,
            value.candidateDisplay,
            value.emacsEnabled,
            value.internalEmacsEnabled,
            SkkCommand.entries.associateWith { command ->
                value.keyBindings.bindings[command]?.let(KeyGestureText::format).orEmpty()
            }.toMutableMap(),
        )

        fun defaultBindings(profile: CustomizationProfile): Map<SkkCommand, KeyGesture> =
            if (profile == CustomizationProfile.AZIK) {
                KeyBindings.defaults + (SkkCommand.TOGGLE_KANA to KeyGesture("[", ignoreShift = true))
            } else {
                KeyBindings.defaults
            }
    }
}

internal data class CustomizationRebase(
    val localWins: CustomizationDraft,
    val remoteWins: CustomizationDraft,
    val conflictCount: Int,
)
