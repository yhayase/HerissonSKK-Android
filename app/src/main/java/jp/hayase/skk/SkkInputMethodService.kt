package jp.hayase.skk

import android.content.Context
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView
import jp.hayase.skk.settings.CustomizationRuntime
import jp.hayase.skk.settings.CustomizationStore
import jp.hayase.skk.settings.CustomizationStoreStatus
import jp.hayase.skk.input.EditorSession
import jp.hayase.skk.input.HardwareKeyMapper
import jp.hayase.skk.input.KeyPressLedger
import jp.hayase.skk.core.InputMode
import jp.hayase.skk.dictionary.DictionaryRuntime
import jp.hayase.skk.dictionary.DictionaryManager
import jp.hayase.skk.dictionary.DictionaryManagerStatus
import jp.hayase.skk.dictionary.DictionaryManagerSubscription
import jp.hayase.skk.dictionary.DictionaryFreshness
import jp.hayase.skk.dictionary.PersonalWriteResult
import jp.hayase.skk.dictionary.PersonalWriteFailure
import jp.hayase.skk.core.RegistrationSaveOutcome
import jp.hayase.skk.core.RegistrationSaveFailure
import jp.hayase.skk.core.CandidateDeletionOutcome
import jp.hayase.skk.core.CandidateDeletionFailure
import jp.hayase.skk.core.dictionary.SkkDictionaryCandidate
import jp.hayase.skk.dictionary.DictionaryInputWriteContext

class SkkInputMethodService : InputMethodService(), InputManager.InputDeviceListener {
    private var generation = 0L
    private var session: EditorSession? = null
    private class SessionWriteContext(var value: DictionaryInputWriteContext)
    private var sessionWriteContext: SessionWriteContext? = null
    private var dictionaryRestoreNotice: String? = null
    private var sessionCustomization: jp.hayase.skk.settings.CustomizationSettings? = null
    private val mapper = HardwareKeyMapper()
    private val presses = KeyPressLedger()
    private var statusView: TextView? = null
    private var candidateStatusView: CandidateStatusView? = null
    private var lastDevice: Int? = null
    private var requestedVisible = false
    private lateinit var customization: CustomizationStore
    private lateinit var dictionaries: DictionaryManager
    private var dictionarySubscription: DictionaryManagerSubscription? = null

    override fun onCreate() {
        super.onCreate()
        dictionaries = DictionaryRuntime.get(this)
        customization = CustomizationRuntime.get(this)
        dictionarySubscription = dictionaries.observe { onDictionaryStatusChanged() }
        getSystemService(InputManager::class.java).registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        session?.close()
        session = null
        sessionWriteContext = null
        dictionaryRestoreNotice = null
        generation++
        mapper.reset()
        lastDevice = null
        requestedVisible = false
        super.onStartInput(attribute, restarting)
        val connection = currentInputConnection ?: return
        val info = attribute ?: return
        val requestedGeneration = generation
        if (customization.status is CustomizationStoreStatus.Loading) {
            // 設定の準備前には標準規則で処理せず、元のキーを入力先へ渡します。
            customization.loadAsync {
                if (generation == requestedGeneration) startEditorSession(connection, info)
            }
        } else startEditorSession(connection, info)
    }

    private fun startEditorSession(connection: InputConnection, info: EditorInfo) {
        val writeContext = SessionWriteContext(dictionaries.captureInputWriteContext())
        sessionWriteContext = writeContext
        val custom = customization.snapshot
        sessionCustomization = custom
        val protected = isPassword(info.inputType) || info.inputType == InputType.TYPE_NULL
        val learningAllowed = !protected && info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        val completionConfig = jp.hayase.skk.core.CompletionConfig(
            dynamicEnabled = getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("dynamic_completion", false),
        )
        session = EditorSession(generation, connection, protected,
            learningAllowed,
            info.initialSelStart, info.initialSelEnd, dictionaries,
            registrationSaver = { request, complete ->
                dictionaries.savePersonalCandidate(request.readingKey,
                    SkkDictionaryCandidate(request.candidateText, okuriCondition = request.okuriCondition),
                    learningAllowed, writeContext.value) { result ->
                    complete(when (result) {
                        PersonalWriteResult.Applied -> RegistrationSaveOutcome.Applied
                        PersonalWriteResult.SavedButNotApplied -> RegistrationSaveOutcome.SavedButNotApplied
                        is PersonalWriteResult.Failed -> RegistrationSaveOutcome.Failed(when (result.reason) {
                            PersonalWriteFailure.CAPACITY -> RegistrationSaveFailure.CAPACITY
                            PersonalWriteFailure.CONFLICT -> RegistrationSaveFailure.CONFLICT
                            PersonalWriteFailure.POLICY_REJECTED -> RegistrationSaveFailure.POLICY_REJECTED
                            PersonalWriteFailure.GENERAL -> RegistrationSaveFailure.GENERAL
                        })
                    })
                }
            }, onStateChanged = ::render,
            candidateLearner = { request, complete ->
                dictionaries.savePersonalCandidate(request.query.readingKey,
                    SkkDictionaryCandidate(request.candidate.text, request.candidate.annotation,
                        request.query.okuri ?: request.candidate.okuriCondition), learningAllowed,
                    writeContext.value) { result ->
                    complete(when (result) {
                        PersonalWriteResult.Applied -> RegistrationSaveOutcome.Applied
                        PersonalWriteResult.SavedButNotApplied -> RegistrationSaveOutcome.SavedButNotApplied
                        is PersonalWriteResult.Failed -> RegistrationSaveOutcome.Failed(when (result.reason) {
                            PersonalWriteFailure.CAPACITY -> RegistrationSaveFailure.CAPACITY
                            PersonalWriteFailure.CONFLICT -> RegistrationSaveFailure.CONFLICT
                            PersonalWriteFailure.POLICY_REJECTED -> RegistrationSaveFailure.POLICY_REJECTED
                            PersonalWriteFailure.GENERAL -> RegistrationSaveFailure.GENERAL
                        })
                    })
                }
            }, candidateDeleter = { request, complete ->
                dictionaries.deleteSelection(request.selection, learningAllowed, writeContext.value) { result ->
                    complete(when (result) {
                        PersonalWriteResult.Applied -> CandidateDeletionOutcome.Applied
                        PersonalWriteResult.SavedButNotApplied -> CandidateDeletionOutcome.SavedButNotApplied
                        is PersonalWriteResult.Failed -> CandidateDeletionOutcome.Failed(when (result.reason) {
                            PersonalWriteFailure.CAPACITY -> CandidateDeletionFailure.CAPACITY
                            PersonalWriteFailure.CONFLICT -> CandidateDeletionFailure.CONFLICT
                            PersonalWriteFailure.POLICY_REJECTED -> CandidateDeletionFailure.POLICY_REJECTED
                            PersonalWriteFailure.GENERAL -> CandidateDeletionFailure.GENERAL
                        })
                    })
                }
            }, completionConfig = completionConfig, romanRuleSet = custom.romanRuleSet,
            emacsEnabled = custom.emacsEnabled,
            punctuationConfig = custom.punctuation, candidateDisplayConfig = custom.candidateDisplay,
            candidatePageSizeProvider = {
                custom.candidateDisplay.pageSize(
                    candidateStatusView?.availableContentWidthDp()
                        ?: resources.configuration.screenWidthDp.toFloat(),
                    resources.configuration.fontScale,
                )
            })
        render()
    }

    override fun onFinishInput() {
        session?.close()
        session = null
        generation++
        mapper.reset()
        setCandidatesViewShown(false)
        super.onFinishInput()
    }

    override fun onUnbindInput() {
        session?.close()
        session = null
        generation++
        mapper.reset()
        super.onUnbindInput()
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return false
    }
    override fun onEvaluateFullscreenMode() = false

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean =
        session?.let { !it.protectedInput && it.active } == true

    override fun onWindowHidden() {
        requestedVisible = false
        super.onWindowHidden()
    }

    override fun onCreateCandidatesView(): View = CandidateStatusView(this).apply {
        candidateStatusView = this
        statusView = statusTextView
        updateStatus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handled = presses.down(event.deviceId, keyCode, event.downTime, generation,
            event.repeatCount, keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER &&
                keyCode != KeyEvent.KEYCODE_ESCAPE && !event.isCtrlPressed) {
            val current = session
            if (current == null || current.protectedInput || current.failed) false
            else {
                if (candidateStatusView?.handleDetailPaging(event) == true) return@down true
                if (!KeyEvent.isModifierKey(keyCode)) candidateStatusView?.closeDetail()
                if (lastDevice != null && lastDevice != event.deviceId) {
                    current.preserveText()
                    mapper.reset()
                }
                lastDevice = event.deviceId
                val config = checkNotNull(sessionCustomization)
                when (val decoded = mapper.decodeConfigured(event, current.engine.state, current.view,
                    config.keyBindings, config.emacsEnabled, config.romanRuleSet)) {
                    HardwareKeyMapper.Decoded.Pass -> {
                        if (!KeyEvent.isModifierKey(keyCode)) {
                            current.preserveText()
                            mapper.reset()
                        }
                        false
                    }
                    HardwareKeyMapper.Decoded.Wait -> true
                    is HardwareKeyMapper.Decoded.Action -> current.handle(decoded.action)
                }
            }
        }
        render()
        return handled || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        presses.up(event.deviceId, keyCode, event.downTime) || super.onKeyUp(keyCode, event)

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (session?.onSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd) == true) {
            mapper.reset()
            generation++
        }
        render()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        generation++
        session?.preserveText()
        mapper.reset()
        super.onConfigurationChanged(newConfig)
        candidateStatusView?.requestLayout()
    }

    override fun onInputDeviceAdded(deviceId: Int) = Unit
    override fun onInputDeviceRemoved(deviceId: Int) {
        presses.removeDevice(deviceId)
        resetDevice(deviceId)
    }
    override fun onInputDeviceChanged(deviceId: Int) = resetDevice(deviceId)

    private fun resetDevice(deviceId: Int) {
        if (lastDevice == deviceId) {
            generation++
            session?.preserveText()
            mapper.reset()
            lastDevice = null
            render()
        }
    }

    override fun onDestroy() {
        generation++
        dictionarySubscription?.close()
        dictionarySubscription = null
        session?.close()
        session = null
        mapper.reset()
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
        super.onDestroy()
    }

    /** 復元前の確認を終了してから、新しい辞書への保存を許可します。本文は置換しません。 */
    private fun onDictionaryStatusChanged() {
        val context = sessionWriteContext
        val current = session
        if (context != null && current != null && !dictionaries.isInputWriteContextCurrent(context.value)) {
            current.preserveText()
            mapper.reset()
            context.value = dictionaries.captureInputWriteContext()
            dictionaryRestoreNotice = "辞書を復元しました。未確定の表示文字を残し、変換・登録・削除の確認を終了しました。"
        }
        render()
    }

    private fun render() {
        updateStatus()
        val current = session
        val enabled = getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("show_status", true)
        val show = current != null && !current.protectedInput &&
            (enabled || current.hasComposition || current.failed)
        if (show && !requestedVisible) {
            requestedVisible = true
            setCandidatesViewShown(true)
            // Android のウィンドウ管理にも表示を要求します。文字キーの画面は作りません。
            if (Build.VERSION.SDK_INT >= 28) requestShowSelf(0)
        } else if (!show && requestedVisible) {
            requestedVisible = false
            setCandidatesViewShown(false)
            requestHideSelf(0)
        }
    }

    private fun updateStatus() {
        val current = session
        val presentation = when {
            current == null || current.protectedInput -> CandidateStatusPresentation("")
            current.failed -> CandidateStatusPresentation(getString(R.string.input_failed))
            else -> {
                val mode = getString(when (current.engine.state.mode) {
                    InputMode.HIRAGANA -> R.string.status_hiragana
                    InputMode.KATAKANA -> R.string.status_katakana
                    InputMode.HALFWIDTH -> R.string.status_halfwidth
                    InputMode.DIRECT -> R.string.status_ascii
                    InputMode.FULLWIDTH -> R.string.status_fullwidth
                })
                val registration = current.view.registration
                val candidate = if (registration == null) current.view.candidate else registration.innerCandidate
                var completionSuffixStart = -1
                var completionSuffixLength = 0
                val details = buildList<String> {
                    add(getString(R.string.local_dictionary_status))
                    when (val status = dictionaries.status) {
                        DictionaryManagerStatus.Loading -> add(getString(R.string.dictionary_loading_status))
                        is DictionaryManagerStatus.Unavailable -> add(getString(R.string.dictionary_failed_status))
                        is DictionaryManagerStatus.Ready -> when (status.freshness) {
                            DictionaryFreshness.REFRESHING -> add(getString(R.string.dictionary_refreshing_status))
                            DictionaryFreshness.STALE -> add(getString(R.string.dictionary_stale_status))
                            DictionaryFreshness.CURRENT -> Unit
                        }
                    }
                    registration?.let {
                        add(getString(R.string.registration_heading, it.depth, preview(it.readingKey, 64)))
                        val cursor = it.cursor.coerceIn(0, it.body.length)
                        add(previewAroundCursor(it.body, cursor))
                        it.innerComposing?.takeIf(String::isNotEmpty)?.let { value -> add("▽${preview(value)}") }
                        add(getString(if (it.saving) R.string.registration_saving else R.string.registration_help))
                    }
                    current.view.completion?.let { completion ->
                        val prefix = preview(completion.prefix, 64)
                        val suffix = preview(completion.suffix, 64)
                        val line = "補完候補: $prefix【$suffix】"
                        completionSuffixStart = detailsLength(this) + "補完候補: $prefix【".length
                        completionSuffixLength = suffix.removeSuffix("…").length
                        add("$line\nRight: 受諾 / Tab: 通常補完")
                    }
                    current.view.deletion?.let { deletion ->
                        add("削除確認: ${preview(deletion.readingKey, 64)} → ${preview(deletion.candidateText)}")
                        add("個人候補 ${deletion.personalOriginCount} 件を削除し、システム由来 ${deletion.systemOriginCount} 件を非表示にします")
                        deletion.okuri?.let { add("送り: ${preview(it, 64)}") }
                        if (deletion.numericTemplate) add("元の数値テンプレートと、その展開候補すべてが対象です")
                        add(if (deletion.saving) "削除を保存しています" else "y: 削除する / n・Ctrl+g: 戻る")
                    }
                    candidate?.let {
                        add("${it.index + 1}/${it.total} ${preview(it.committedText)}")
                        it.selected.annotation?.let { note -> add(preview(note, 64)) }
                        if (it.menu.isNotEmpty()) add(it.menu.joinToString("\n") { item ->
                            val annotation = item.candidate.annotation?.let { note -> "（${preview(note, 32)}）" }.orEmpty()
                            "${item.label}: ${preview(item.committedText, 48)}$annotation"
                        })
                    }
                    current.notice?.let { add(preview(it, 64)) }
                    dictionaryRestoreNotice?.let { add(preview(it, 64)) }
                }
                val text = "$mode  ${details.joinToString("\n")}"
                val styled = if (completionSuffixStart >= 0) {
                    // mode と区切りの長さを加え、通常表示に残った範囲だけを強調します。
                    styledCompletionText(text, mode.length + 2 + completionSuffixStart, completionSuffixLength)
                } else text
                val identity = candidate?.let {
                    CandidateDetailIdentity(it.index, it.selected.text, it.selected.annotation)
                }
                CandidateStatusPresentation(styled, identity, buildList {
                    candidate?.let {
                        add(CandidateDetailSection("候補本文", it.committedText))
                        it.selected.annotation?.let { annotation ->
                            add(CandidateDetailSection("注釈", annotation))
                        }
                    }
                })
            }
        }
        candidateStatusView?.show(presentation) ?: run { statusView?.text = presentation.text }
    }

    private fun preview(value: String, clusters: Int = CandidateTextBounds.NORMAL_CLUSTERS): String {
        val bounded = CandidateTextBounds.preview(value, clusters)
        return bounded.text + if (bounded.truncated && bounded.text != CandidateTextBounds.LONG_GRAPHEME_PLACEHOLDER) "…" else ""
    }

    private fun previewAroundCursor(value: String, cursor: Int): String {
        val before = CandidateTextBounds.previewBeforeCursor(value, cursor, 48)
        val after = CandidateTextBounds.preview(value.substring(cursor), 48)
        return (if (before.truncated && before.text != CandidateTextBounds.LONG_GRAPHEME_PLACEHOLDER) "…" else "") +
            before.text + "│" + after.text +
            if (after.truncated && after.text != CandidateTextBounds.LONG_GRAPHEME_PLACEHOLDER) "…" else ""
    }

    private fun detailsLength(lines: List<String>): Int = lines.sumOf { it.length + 1 }

    companion object {
        internal fun isPassword(type: Int): Boolean {
            val variation = type and InputType.TYPE_MASK_VARIATION
            return when (type and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                InputType.TYPE_CLASS_TEXT -> variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                else -> false
            }
        }
    }
}
