package se.haya.skk

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.IBinder
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.TextView
import android.widget.FrameLayout
import se.haya.skk.settings.CustomizationRuntime
import se.haya.skk.settings.AppEmacsEditingRuntime
import se.haya.skk.settings.AppEmacsEditingStore
import se.haya.skk.settings.CustomizationStore
import se.haya.skk.settings.CustomizationStoreStatus
import se.haya.skk.input.EditorSession
import se.haya.skk.input.HardwareKeyMapper
import se.haya.skk.input.KeyPressLedger
import se.haya.skk.input.QuoteNextKey
import se.haya.skk.input.HardwareKeyboardPresence
import se.haya.skk.input.TouchKeyboardCommand
import se.haya.skk.input.TouchKeyboardView
import se.haya.skk.core.InputMode
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.dictionary.DictionaryRuntime
import se.haya.skk.dictionary.DictionaryManager
import se.haya.skk.dictionary.DictionaryManagerStatus
import se.haya.skk.dictionary.DictionaryManagerSubscription
import se.haya.skk.dictionary.DictionaryFreshness
import se.haya.skk.dictionary.PersonalWriteResult
import se.haya.skk.dictionary.PersonalWriteFailure
import se.haya.skk.core.RegistrationSaveOutcome
import se.haya.skk.core.RegistrationSaveFailure
import se.haya.skk.core.CandidateDeletionOutcome
import se.haya.skk.core.CandidateDeletionFailure
import se.haya.skk.core.CandidateView
import se.haya.skk.core.LabeledCandidate
import se.haya.skk.core.RegistrationView
import se.haya.skk.core.dictionary.SkkDictionaryCandidate
import se.haya.skk.dictionary.DictionaryInputWriteContext
import java.util.concurrent.Executors
import java.util.concurrent.Executor

class SkkInputMethodService : InputMethodService(), InputManager.InputDeviceListener {
    private class PhysicalHostView(context: Context) : View(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(resolveSize(0, widthMeasureSpec), 0)
        }
    }

    private var generation = 0L
    private var session: EditorSession? = null
    private var candidatePresentationEpoch = 0L
    private class CandidatePresentationBoundary(
        val session: EditorSession?,
        val view: se.haya.skk.core.BasicSkkView?,
        val generation: Long,
        val touch: Boolean,
        val targets: List<CandidateTapTarget>,
    ) {
        fun sameAs(other: CandidatePresentationBoundary): Boolean =
            session === other.session && view === other.view && generation == other.generation &&
                touch == other.touch && targets.size == other.targets.size &&
                targets.zip(other.targets).all { (first, second) ->
                    first.kind == second.kind && first.index == second.index &&
                        first.candidate === second.candidate && first.registrationDepth == second.registrationDepth
                }
    }
    private var candidatePresentationBoundary: CandidatePresentationBoundary? = null
    private class SessionWriteContext(var value: DictionaryInputWriteContext)
    private var sessionWriteContext: SessionWriteContext? = null
    private var dictionaryRestoreNotice: String? = null
    private var appEmacsNotice: String? = null
    private var sessionCustomization: se.haya.skk.settings.CustomizationSettings? = null
    private val mapper = HardwareKeyMapper()
    private val presses = KeyPressLedger()
    private val quoteNext = QuoteNextKey()
    private var statusView: TextView? = null
    private var candidateStatusView: CandidateStatusView? = null
    private var touchKeyboardView: TouchKeyboardView? = null
    private var lastDevice: Int? = null
    private var physicalHostAllowed = false
    private var physicalHostRequested = false
    private var changingPhysicalHost = false
    private var inputHost: FrameLayout? = null
    private var physicalPopup: PhysicalInputPopup? = null
    private var physicalPopupHost: PhysicalPopupHostPort? = null
    private var physicalBackCallback: PhysicalBackCallback? = null
    private var physicalPopupHostFactory: (() -> PhysicalPopupHostPort) = {
        PhysicalPopupHost(this, ::renderPhysicalPopup)
    }
    private var physicalHostWindowToken: () -> IBinder? = { window?.window?.attributes?.token }
    private var cursorAnchor: CursorAnchorInfo? = null
    private var cursorMonitorGeneration: Long? = null
    private var renderedPresentation = CandidateStatusPresentation("")
    /** 描画した登録枠。遅れて届いたポップアップ操作を別の枠へ適用しないために使います。 */
    private var renderedRegistration: RegistrationView? = null
    private var renderedMode = ""
    private data class ConfigurationRestart(
        val identity: ConfigurationRestartIdentity,
        val session: EditorSession,
        val connection: InputConnection,
        val checkpoint: EditorSession.ConfigurationCheckpoint,
        var spanLoss: Boolean = false,
    )
    private var configurationRestart: ConfigurationRestart? = null
    private var changingConfiguration = false

    @android.annotation.TargetApi(33)
    private class PhysicalBackCallback(onBack: () -> Unit) {
        private val callback = OnBackInvokedCallback(onBack)
        private var dispatcher: OnBackInvokedDispatcher? = null

        fun register(next: OnBackInvokedDispatcher) {
            if (dispatcher === next) return
            unregister()
            next.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
            dispatcher = next
        }

        fun unregister() {
            dispatcher?.unregisterOnBackInvokedCallback(callback)
            dispatcher = null
        }
    }

    private lateinit var customization: CustomizationStore
    private lateinit var appEmacsSettings: AppEmacsEditingStore
    private lateinit var dictionaries: DictionaryManager
    private lateinit var clipboard: ClipboardManager
    private var dictionarySubscription: DictionaryManagerSubscription? = null
    private var inlineAnnotation: InlineCandidateAnnotationPopup? = null
    private data class AnnotationTarget(val generation: Long, val composition: String, val annotation: String)
    private var annotationTarget: AnnotationTarget? = null
    private var annotationConnection: InputConnection? = null
    private val annotationWorker = Executors.newSingleThreadExecutor()
    private val annotationMonitor = CursorAnchorMonitor(annotationWorker,
        Executor { Handler(Looper.getMainLooper()).post(it) })

    override fun onCreate() {
        super.onCreate()
        // Android 8ではClipboardManagerの初期化に呼出し元のLooperが必要です。
        clipboard = getSystemService(ClipboardManager::class.java)
        dictionaries = DictionaryRuntime.get(this)
        customization = CustomizationRuntime.get(this)
        appEmacsSettings = AppEmacsEditingRuntime.get(this)
        dictionarySubscription = dictionaries.observe { onDictionaryStatusChanged() }
        getSystemService(InputManager::class.java).registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        val pending = configurationRestart
        if (restarting && pending != null && pending.session === session &&
            pending.session.active && pending.identity.matches(attribute, currentInputBinding,
                window?.window?.attributes?.token, android.os.SystemClock.uptimeMillis())) {
            val connection = currentInputConnection
            if (connection === pending.connection || (connection != null &&
                    pending.session.rebindAfterConfiguration(pending.checkpoint, connection))) {
                if (connection !== pending.connection) configurationRestart = null
                super.onStartInput(attribute, restarting)
                clearInlineAnnotation()
                render()
                return
            }
        }
        configurationRestart = null
        val allowPhysicalHostForSession = physicalHostAllowedForStartedInput(restarting, physicalHostAllowed)
        physicalHostAllowed = false
        setPhysicalHostShown(false)
        clearInlineAnnotation()
        session?.close()
        session = null
        sessionWriteContext = null
        dictionaryRestoreNotice = null
        generation++
        mapper.reset()
        quoteNext.reset()
        lastDevice = null
        super.onStartInput(attribute, restarting)
        val connection = currentInputConnection
        val info = attribute
        if (connection == null || info == null) {
            clearStatusWindow()
            return
        }
        val requestedGeneration = generation
        if (customization.status is CustomizationStoreStatus.Loading) {
            // 設定の準備前には標準規則で処理せず、元のキーを入力先へ渡します。
            customization.loadAsync {
                if (generation == requestedGeneration) {
                    startEditorSession(connection, info, allowPhysicalHostForSession)
                }
            }
        } else startEditorSession(connection, info, allowPhysicalHostForSession)
    }

    private fun startEditorSession(
        connection: InputConnection,
        info: EditorInfo,
        allowPhysicalHost: Boolean,
    ) {
        val writeContext = SessionWriteContext(dictionaries.captureInputWriteContext())
        sessionWriteContext = writeContext
        val global = customization.snapshot
        appEmacsNotice = null
        appEmacsSettings.migrateLegacy(global.internalEmacsEnabled)
        val custom = try {
            global.withEmacsEditingMode(appEmacsSettings.emacsEditingMode(info.packageName, global.emacsEditingMode))
        } catch (_: IllegalArgumentException) {
            appEmacsNotice = "編集キーが他の入力操作と重複するため、この入力欄ではEmacs編集を無効にしています。キー設定を確認してください。"
            global.withEmacsEditingMode(se.haya.skk.settings.EmacsEditingMode.DISABLED)
        }
        sessionCustomization = custom
        val protected = isPassword(info.inputType) || info.inputType == InputType.TYPE_NULL
        val learningAllowed = !protected && info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        val completionConfig = se.haya.skk.core.CompletionConfig(
            dynamicEnabled = getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("dynamic_completion", false),
        )
        val basicPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val killedClipLabel = "削除したテキスト:${java.util.UUID.randomUUID()}"
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
                    writeContext.value, request.historyTarget) { result ->
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
            internalEmacsEnabled = custom.internalEmacsEnabled,
            touchEditorAction = info.imeOptions and EditorInfo.IME_MASK_ACTION,
            touchEditorNoEnterAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0,
            touchEditorMultiline = info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0,
            confirmOnlyEnter = basicPreferences.getBoolean("confirm_only_enter", false),
            copyKilledText = { text ->
                runCatching {
                    clipboard.setPrimaryClip(ClipData.newPlainText(killedClipLabel, text))
                }.isSuccess
            },
            ownsKilledText = { text ->
                runCatching {
                    val clip = clipboard.primaryClip
                    clip?.description?.label == killedClipLabel && clip.itemCount == 1 &&
                        clip.getItemAt(0).text?.toString() == text
                }.getOrDefault(false)
            },
            punctuationConfig = custom.punctuation, candidateDisplayConfig = custom.candidateDisplay,
            candidatePageCapacityProvider = { candidates, start ->
                if (shouldShowTouchCharacters()) {
                    candidateStatusView?.pageCapacity(candidates.map { it.text }, start) ?: 1
                } else {
                    val viewport = window?.window?.decorView?.let(PhysicalInputPopup::viewport)
                    val width = viewport?.width() ?: resources.displayMetrics.widthPixels
                    val presenter = candidateStatusView
                    presenter?.physicalPageCapacity(
                        candidates.map { it.text to it.annotation }, start, custom.candidateDisplay.labels.length,
                        availableWidthPx = width,
                        availableHeightPx = viewport?.height() ?: resources.displayMetrics.heightPixels,
                        reservedHeaderHeightPx = presenter.physicalHeaderHeight(renderedPresentation, width),
                    ) ?: 1
                }
            },
            candidatePageSizeProvider = {
                custom.candidateDisplay.pageSize(
                    candidateStatusView?.availableContentWidthDp()
                        ?: resources.configuration.screenWidthDp.toFloat(),
                    resources.configuration.fontScale,
                ).coerceAtMost(candidateStatusView?.visibleMenuCapacity(physical = true) ?: 1)
            })
        physicalHostAllowed = allowPhysicalHost && info.inputType != InputType.TYPE_NULL
        touchKeyboardView?.resetForEditorSession()
        updateTouchKeyboardVisibility()
        render()
    }

    override fun onFinishInput() {
        configurationRestart = null
        session?.close()
        session = null
        generation++
        mapper.reset()
        quoteNext.reset()
        clearStatusWindow()
        super.onFinishInput()
    }

    override fun onUnbindInput() {
        configurationRestart = null
        session?.close()
        session = null
        generation++
        mapper.reset()
        quoteNext.reset()
        clearStatusWindow()
        super.onUnbindInput()
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return acceptsTouchShowRequest(currentInputEditorInfo, currentInputConnection,
            shouldShowTouchCharacters())
    }
    override fun onEvaluateFullscreenMode() = false

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean =
        acceptsTouchShowRequest(currentInputEditorInfo, currentInputConnection,
            shouldShowTouchCharacters())

    override fun onWindowHidden() {
        if (!changingConfiguration) configurationRestart = null
        if (!changingConfiguration && !changingPhysicalHost) {
            physicalHostAllowed = false
            physicalHostRequested = false
        }
        physicalPopup?.dismiss()
        unregisterPhysicalBackCallback()
        physicalPopupHost?.dismiss()
        clearInlineAnnotation()
        super.onWindowHidden()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // 構成変更中に IME のトークンがまだなければ、ホストの追加は失敗します。
        // 窓が表示された時点で再試行し、登録表示だけが失われる状態を避けます。
        if (Build.VERSION.SDK_INT >= 33 && physicalHostAllowed && !physicalHostRequested) {
            updatePhysicalHostVisibility()
        }
        updateInlineAnnotation()
        renderPhysicalPopup()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        clearInlineAnnotation()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        if (!shouldShowTouchCharacters()) {
            val current = session
            cursorAnchor = cursorAnchorInfo.takeIf {
                current?.active == true && cursorMonitorGeneration == current.generation &&
                    annotationConnection != null && annotationConnection === currentInputConnection &&
                    CursorAnchorAcceptance.matches(it, current.expectedCursorSelection, current.expectedCursorComposition)
            }
            renderPhysicalPopup()
            return
        }
        val target = annotationTarget ?: return
        if (cursorAnchorInfo == null || target != currentAnnotationTarget()) {
            inlineAnnotation?.dismiss()
            return
        }
        val parent = window?.window?.decorView ?: return
        val popup = inlineAnnotation ?: InlineCandidateAnnotationPopup(this).also { inlineAnnotation = it }
        popup.show(parent, candidateStatusView, cursorAnchorInfo, target.composition, target.annotation)
    }

    override fun onCreateInputView(): View {
        val keys = TouchKeyboardView(this, ::onTouchCommand).also {
            touchKeyboardView = it
            candidateStatusView = it.candidateStatusView
            statusView = it.candidateStatusView.statusTextView
            it.candidateStatusView.setOnCandidateTapListener(::onCandidateTapped)
            it.candidateStatusView.setOnPageListeners(
                { configurationRestart = null; session?.handleTouch(BasicSkkAction.PreviousCandidatePage); render() },
                { configurationRestart = null; session?.handleTouch(BasicSkkAction.NextCandidatePage); render() },
                { configurationRestart = null; session?.cancelTouchRegistration(); render() })
        }
        return FrameLayout(this).apply {
            inputHost = this
            addView(keys, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> renderPhysicalPopup() }
            updateTouchKeyboardVisibility()
            updateStatus()
        }
    }

    override fun onCreateCandidatesView(): View {
        ensureCandidatePresenter()
        // 物理ポップアップのトークンだけを保持し、入力先の下部を予約しません。
        return PhysicalHostView(this).apply {
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            minimumHeight = 0
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!shouldShowTouchCharacters()) {
            val height = window?.window?.decorView?.height ?: 0
            outInsets.contentTopInsets = height
            outInsets.visibleTopInsets = height
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.setEmpty()
        }
    }

    private fun renderPhysicalPopup() {
        if (!physicalHostRequested || shouldShowTouchCharacters() || session?.active != true ||
            session?.protectedInput == true) {
            physicalPopup?.dismiss()
            return
        }
        val parent = if (Build.VERSION.SDK_INT >= 33) physicalPopupHost?.view
            else window?.window?.decorView
        if (parent == null) return
        if (!parent.isShown || parent.windowToken == null) return
        val popup = physicalPopup ?: PhysicalInputPopup(this, ::onCandidateTapped).also { physicalPopup = it }
        bindRegistrationCallbacks(popup.content, renderedRegistration)
        val current = session ?: return
        val validAnchor = cursorAnchor.takeIf {
            CursorAnchorAcceptance.matches(it, current.expectedCursorSelection, current.expectedCursorComposition)
        }
        popup.show(parent, renderedPresentation, renderedMode, validAnchor,
            renderedPresentation.menuItems.isNotEmpty() || renderedPresentation.expandedStatus ||
                renderedPresentation.text.isNotEmpty())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        configurationRestart = null
        if (keyCode == KeyEvent.KEYCODE_BACK && physicalHostRequested &&
            !shouldShowTouchCharacters()) {
            // 予測型の戻るでも DOWN は IME へ届きます。UP より前に窓を閉じると、
            // 転送コールバックが解除され、入力先の終了処理へ切り替わります。
            event.startTracking()
            return true
        }
        val currentSession = session
        if (keyCode != KeyEvent.KEYCODE_BACK && shouldRestorePhysicalHostForKey(shouldShowTouchCharacters(),
                currentSession?.active == true, currentSession?.protectedInput == true)) {
            // 戻る操作で閉じた物理表示は、次の実入力でだけ再開します。
            physicalHostAllowed = true
        }
        val handled = presses.down(event.deviceId, keyCode, event.downTime, generation,
            event.repeatCount, keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER &&
                keyCode != KeyEvent.KEYCODE_ESCAPE) {
            if (quoteNext.consumeDown(event)) {
                session?.breakKillChain()
                return@down false
            }
            if (isImeSwitchGesture(event)) {
                session?.breakKillChain()
                showImePicker()
                return@down true
            }
            val current = session
            if (current == null || current.failed) {
                quoteNext.reset()
                false
            }
            else {
                if (current.protectedInput) return@down false
                if (candidateStatusView?.handleDetailPaging(event) == true) {
                    current.breakKillChain()
                    return@down true
                }
                if (!KeyEvent.isModifierKey(keyCode)) candidateStatusView?.closeDetail()
                if (lastDevice != null && lastDevice != event.deviceId) {
                    current.preserveText()
                    mapper.reset()
                }
                lastDevice = event.deviceId
                handleConfiguredKey(current, event)
            }
        }
        render()
        if (quoteNext.quotedDown(event)) return false
        return handled || super.onKeyDown(keyCode, event)
    }

    private fun handleConfiguredKey(current: EditorSession, event: KeyEvent, replay: Boolean = false): Boolean {
        val keyCode = event.keyCode
        val config = checkNotNull(sessionCustomization)
        if (current.dictionaryReadPending) {
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && !event.isCtrlPressed && !event.isAltPressed) {
                current.handle(BasicSkkAction.Cancel)
                return true
            }
            val preview = mapper.previewConfigured(event, current.engine.state, current.view,
                config.keyBindings, config.emacsEnabled, config.romanRuleSet, config.internalEmacsEnabled)
            val stateDependentPass = preview == HardwareKeyMapper.Decoded.Pass &&
                !KeyEvent.isModifierKey(keyCode) && !event.isMetaPressed && !event.isCtrlPressed &&
                !event.isAltPressed && keyCode != KeyEvent.KEYCODE_BACK &&
                (event.unicodeChar != 0 || keyCode in setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_FORWARD_DEL))
            if ((preview != HardwareKeyMapper.Decoded.Pass || stateDependentPass) &&
                preview != HardwareKeyMapper.Decoded.QuoteNext &&
                (preview as? HardwareKeyMapper.Decoded.Action)?.action != BasicSkkAction.Cancel) {
                return current.deferKey {
                    if (handleConfiguredKey(current, event, replay = true)) true else current.replayUnhandledKey(event)
                }
            }
        }
        return when (val decoded = mapper.decodeConfigured(event, current.engine.state, current.view,
            config.keyBindings, config.emacsEnabled, config.romanRuleSet, config.internalEmacsEnabled)) {
            HardwareKeyMapper.Decoded.QuoteNext -> {
                if (event.repeatCount == 0) {
                    current.preserveText()
                    quoteNext.begin(event)
                }
                true
            }
            HardwareKeyMapper.Decoded.Pass -> {
                if (!KeyEvent.isModifierKey(keyCode)) {
                    if (!replay) current.preserveText()
                    mapper.reset()
                }
                false
            }
            HardwareKeyMapper.Decoded.Wait -> {
                current.breakKillChain()
                true
            }
            is HardwareKeyMapper.Decoded.Action -> {
                val repeatableEdit = (decoded.action as? BasicSkkAction.Edit)?.command in setOf(
                    se.haya.skk.core.editing.EditCommand.HOME,
                    se.haya.skk.core.editing.EditCommand.END,
                    se.haya.skk.core.editing.EditCommand.LEFT,
                    se.haya.skk.core.editing.EditCommand.RIGHT,
                    se.haya.skk.core.editing.EditCommand.UP,
                    se.haya.skk.core.editing.EditCommand.DOWN,
                    se.haya.skk.core.editing.EditCommand.WORD_BACKWARD,
                    se.haya.skk.core.editing.EditCommand.WORD_FORWARD,
                    se.haya.skk.core.editing.EditCommand.PAGE_DOWN,
                    se.haya.skk.core.editing.EditCommand.PAGE_UP,
                    se.haya.skk.core.editing.EditCommand.BACKSPACE,
                    se.haya.skk.core.editing.EditCommand.DELETE,
                    se.haya.skk.core.editing.EditCommand.KILL_LINE,
                )
                val repeatableAction = repeatableEdit || decoded.action is BasicSkkAction.Text ||
                    decoded.action in setOf(BasicSkkAction.Backspace, BasicSkkAction.Delete,
                        BasicSkkAction.Left, BasicSkkAction.Right, BasicSkkAction.Home, BasicSkkAction.End,
                        BasicSkkAction.ConvertNext, BasicSkkAction.PreviousCandidate,
                        BasicSkkAction.CompleteForward, BasicSkkAction.CompleteBackward)
                if (event.repeatCount > 0 && !repeatableAction) true
                else if (decoded.action == BasicSkkAction.Enter &&
                    keyCode in setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    current.handleHardwareEnter()
                } else current.handle(decoded.action)
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.isTracking && physicalHostRequested &&
            !shouldShowTouchCharacters()) {
            if (!event.isCanceled) handlePhysicalHostBack()
            return true
        }
        val handled = presses.up(event.deviceId, keyCode, event.downTime, generation)
        if (quoteNext.quotedUp(event)) return false
        return handled || super.onKeyUp(keyCode, event)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val pending = configurationRestart
        if (pending != null && pending.session === session && pending.identity.matches(
                currentInputEditorInfo, currentInputBinding, window?.window?.attributes?.token,
                android.os.SystemClock.uptimeMillis()) && pending.session.matchesConfigurationSpanLoss(
                pending.checkpoint, newSelStart, newSelEnd, candidatesStart, candidatesEnd)) {
            pending.spanLoss = true
            return
        }
        configurationRestart = null
        if (session?.onSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd) == true) {
            mapper.reset()
            quoteNext.reset()
            generation++
        }
        render()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        clearInlineAnnotation()
        generation++
        mapper.reset()
        quoteNext.reset()
        val current = session
        val checkpoint = current?.configurationCheckpoint()
        val connection = currentInputConnection
        val identity = ConfigurationRestartIdentity.capture(currentInputEditorInfo, currentInputBinding,
            window?.window?.attributes?.token, android.os.SystemClock.uptimeMillis())
        val pending = if (current != null && checkpoint != null && connection != null && identity != null)
            ConfigurationRestart(identity, current, connection, checkpoint) else null
        configurationRestart = pending
        if (pending != null) Handler(Looper.getMainLooper()).postDelayed({
            if (configurationRestart === pending) {
                configurationRestart = null
                // 再接続が来なければ、保留した構成消失を通常の外部編集として扱います。
                if (pending.spanLoss && session === pending.session) {
                    pending.session.onSelection(pending.checkpoint.selectionStart,
                        pending.checkpoint.selectionEnd, -1, -1)
                    render()
                }
            }
        }, ConfigurationRestartIdentity.MAX_AGE_MS)
        val restorePhysicalHost = physicalHostRequested
        if (Build.VERSION.SDK_INT >= 33) {
            physicalPopup?.dismiss()
            unregisterPhysicalBackCallback()
            physicalPopupHost?.dismiss()
        }
        changingConfiguration = true
        try {
            super.onConfigurationChanged(newConfig)
        } finally {
            // framework のビュー再生成で候補領域の可視状態も初期化されるため再要求します。
            if (restorePhysicalHost) physicalHostRequested = false
            changingConfiguration = false
        }
        candidateStatusView?.requestLayout()
        touchKeyboardView?.configurationChanged()
        updateTouchKeyboardVisibility()
        updatePhysicalHostVisibility()
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        updateTouchKeyboardVisibility()
        render()
    }
    override fun onInputDeviceRemoved(deviceId: Int) {
        presses.removeDevice(deviceId)
        resetDevice(deviceId)
        updateTouchKeyboardVisibility()
        render()
    }
    override fun onInputDeviceChanged(deviceId: Int) {
        resetDevice(deviceId)
        updateTouchKeyboardVisibility()
        render()
    }

    private fun resetDevice(deviceId: Int) {
        if (lastDevice == deviceId) {
            generation++
            mapper.reset()
            quoteNext.reset()
            lastDevice = null
            render()
        }
    }

    override fun onDestroy() {
        candidatePresentationBoundary = null
        physicalHostAllowed = false
        physicalHostRequested = false
        physicalPopup?.dismiss()
        physicalPopup = null
        unregisterPhysicalBackCallback()
        physicalPopupHost?.dismiss()
        physicalPopupHost = null
        clearInlineAnnotation()
        annotationWorker.shutdown()
        generation++
        dictionarySubscription?.close()
        dictionarySubscription = null
        session?.close()
        session = null
        mapper.reset()
        quoteNext.reset()
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
            quoteNext.reset()
            context.value = dictionaries.captureInputWriteContext()
            dictionaryRestoreNotice = "辞書を復元しました。未確定の表示文字を残し、変換・登録・削除の確認を終了しました。"
        }
        current?.refreshPredictionDictionary()
        render()
    }

    private fun render() {
        updateStatus()
        updateTouchKeyboardVisibility()
        updateInlineAnnotation()
        updatePhysicalHostVisibility()
    }

    private fun updatePhysicalHostVisibility() {
        setPhysicalHostShown(physicalHostVisibility())
    }

    private var physicalHostVisibility: () -> Boolean = {
        val current = session
        physicalHostAllowed && !shouldShowTouchCharacters() && current?.active == true &&
            !current.protectedInput && currentInputEditorInfo?.inputType?.let {
                it != InputType.TYPE_NULL
            } == true
    }

    private fun setPhysicalHostShown(shown: Boolean) {
        if (physicalHostRequested == shown) {
            if (shown) schedulePhysicalBackCallback()
            else unregisterPhysicalBackCallback()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (shown) {
                ensureCandidatePresenter()
                val host = physicalPopupHost ?: physicalPopupHostFactory().also { physicalPopupHost = it }
                physicalHostRequested = physicalHostWindowToken()?.let(host::show) == true
                if (physicalHostRequested) schedulePhysicalBackCallback()
                else unregisterPhysicalBackCallback()
                renderPhysicalPopup()
            } else {
                physicalHostRequested = false
                physicalPopup?.dismiss()
                unregisterPhysicalBackCallback()
                physicalPopupHost?.dismiss()
            }
            return
        }
        physicalHostRequested = shown
        changingPhysicalHost = true
        try {
            setCandidatesViewShown(shown)
        } finally {
            changingPhysicalHost = false
        }
        if (!shown) physicalPopup?.dismiss()
    }

    @android.annotation.TargetApi(33)
    private fun schedulePhysicalBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return
        Handler(Looper.getMainLooper()).post {
            if (!physicalHostRequested) return@post
            val dispatcher = window?.window?.onBackInvokedDispatcher ?: return@post
            val callback = physicalBackCallback ?: PhysicalBackCallback(::handlePhysicalHostBack)
                .also { physicalBackCallback = it }
            callback.register(dispatcher)
        }
    }

    private fun unregisterPhysicalBackCallback() {
        if (Build.VERSION.SDK_INT >= 33) physicalBackCallback?.unregister()
    }

    internal fun handlePhysicalHostBack() {
        if (!physicalHostRequested) return
        physicalHostAllowed = false
        setPhysicalHostShown(false)
    }

    private fun clearStatusWindow() {
        candidatePresentationBoundary = null
        renderedPresentation = CandidateStatusPresentation("")
        renderedRegistration = null
        physicalHostAllowed = false
        setPhysicalHostShown(false)
        physicalPopup?.dismiss()
        clearInlineAnnotation()
        candidateStatusView?.show(CandidateStatusPresentation(""))
    }

    private fun ensureCandidatePresenter() {
        if (candidateStatusView != null) return
        CandidateStatusView(this).also {
            candidateStatusView = it
            statusView = it.statusTextView
        }
    }

    private fun currentAnnotationTarget(): AnnotationTarget? {
        val current = session?.takeIf { it.active && !it.failed && !it.protectedInput } ?: return null
        if (current.view.registration != null) return null
        val candidate = current.view.candidate?.takeIf { it.menu.isEmpty() } ?: return null
        val annotation = candidate.selected.annotation?.takeIf { it.isNotBlank() } ?: return null
        return AnnotationTarget(generation, current.displayedComposition, annotation)
    }

    private fun updateInlineAnnotation() {
        if (!shouldShowTouchCharacters()) {
            val current = session?.takeIf { it.active } ?: run {
                clearInlineAnnotation()
                return
            }
            val connection = currentInputConnection ?: return
            if (annotationConnection !== connection) {
                annotationConnection = connection
                cursorMonitorGeneration = current.generation
                cursorAnchor = null
                annotationMonitor.start(connection) { accepted ->
                    if (!accepted && annotationConnection === connection) {
                        cursorAnchor = null
                        renderPhysicalPopup()
                    }
                }
            }
            return
        }
        inlineAnnotation?.dismiss()
    }

    private fun clearInlineAnnotation() {
        annotationTarget = null
        cursorMonitorGeneration = null
        cursorAnchor = null
        inlineAnnotation?.dismiss()
        annotationConnection = null
        annotationMonitor.stop()
    }

    private fun updateStatus() {
        val current = session
        val presentationEpoch = candidatePresentationEpoch
        val touch = shouldShowTouchCharacters()
        var presentation = when {
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
                val prediction = current.view.prediction.takeIf { shouldShowTouchCharacters() }
                // 物理候補の menu はインライン選択を終えた後だけ作られます。
                // タッチ専用 pageItems を代用すると、まだ選べないラベルを表示してしまいます。
                val items = candidateItemsForPresentation(candidate, shouldShowTouchCharacters())
                var completionSuffixStart = -1
                var completionSuffixLength = 0
                val details = buildList<String> {
                    if (registration == null) {
                        appEmacsNotice?.let(::add)
                        when (val status = dictionaries.status) {
                            DictionaryManagerStatus.Loading -> add(getString(R.string.dictionary_loading_status))
                            is DictionaryManagerStatus.Unavailable -> add(getString(R.string.dictionary_failed_status))
                            is DictionaryManagerStatus.Ready -> when (status.freshness) {
                                DictionaryFreshness.REFRESHING -> add(getString(R.string.dictionary_refreshing_status))
                                DictionaryFreshness.STALE -> add(getString(R.string.dictionary_stale_status))
                                DictionaryFreshness.CURRENT -> Unit
                            }
                        }
                        current.view.deletion?.let { deletion ->
                            add("削除確認: ${preview(deletion.readingKey, 64)} → ${preview(deletion.candidateText)}")
                            add("個人候補 ${deletion.personalOriginCount} 件を削除し、システム由来 ${deletion.systemOriginCount} 件を非表示にします")
                            deletion.okuri?.let { add("送り: ${preview(it, 64)}") }
                            if (deletion.numericTemplate) add("元の数値テンプレートと、その展開候補すべてが対象です")
                            add(if (deletion.saving) "削除を保存しています" else "y: 削除する / n・Ctrl+g: 戻る")
                        }
                        dictionaryRestoreNotice?.let { add(preview(it, 64)) }
                    }
                    current.view.completion?.takeIf { registration == null }?.let { completion ->
                        val prefix = preview(completion.prefix, 64)
                        val suffix = preview(completion.suffix, 64)
                        val line = "補完候補: $prefix【$suffix】"
                        completionSuffixStart = detailsLength(this) + "補完候補: $prefix【".length
                        completionSuffixLength = suffix.removeSuffix("…").length
                        add("$line\nRight: 受諾 / Tab: 通常補完")
                    }
                    current.view.deletion?.takeIf { registration != null }?.let { deletion ->
                        add(if (deletion.saving) "削除を保存しています" else
                            "削除確認: ${preview(deletion.readingKey, 64)} → ${preview(deletion.candidateText)}\ny: 削除する / n・Ctrl+g: 戻る")
                    }
                    current.notice?.let { add(preview(it, 64)) }
                }
                val text = details.joinToString("\n")
                val styled = if (completionSuffixStart >= 0) {
                    // mode と区切りの長さを加え、通常表示に残った範囲だけを強調します。
                    styledCompletionText(text, completionSuffixStart, completionSuffixLength)
                } else text
                val identity = candidate?.takeIf { it.menu.isNotEmpty() }?.let {
                    CandidateDetailIdentity(it.index, it.selected.text, it.selected.annotation)
                }
                val selectedMenuIndex = candidate?.takeIf { it.menu.isNotEmpty() }?.menu
                    ?.indexOfFirst { item -> item.candidate === candidate.selected }
                    ?.takeIf { it >= 0 }
                val selectedPreviewTruncated = candidate?.takeIf { selectedMenuIndex != null }?.let {
                    CandidateTextBounds.preview(it.committedText, 32).truncated ||
                        (it.selected.annotation?.let { annotation ->
                            CandidateTextBounds.preview(annotation, 24).truncated
                        } ?: false)
                } ?: false
                CandidateStatusPresentation(styled, identity, buildList {
                    candidate?.takeIf { it.menu.isNotEmpty() }?.let {
                        add(CandidateDetailSection("候補本文", it.committedText))
                        it.selected.annotation?.let { annotation ->
                            add(CandidateDetailSection("注釈", annotation))
                        }
                    }
                }, menuItems = if (prediction != null) {
                    val capacity = candidateStatusView?.pageCapacity(prediction.items.map { it.committedText }, 0,
                        pageControls = false) ?: 1
                    prediction.items.take(capacity).mapIndexed { index, item ->
                        CandidateMenuItem(' ', item.committedText, item.candidate.annotation,
                            CandidateTapTarget(current.generation, presentationEpoch, index, item,
                                registration?.depth ?: 0, CandidateTargetKind.PREDICTION))
                    }.ifEmpty {
                        prediction.fallbackText?.let { text -> listOf(CandidateMenuItem(' ', text, null,
                            CandidateTapTarget(current.generation, presentationEpoch, 0, prediction,
                                registration?.depth ?: 0, CandidateTargetKind.READING_FALLBACK))) }.orEmpty()
                    }
                } else items.map { item ->
                    CandidateMenuItem(item.label, item.committedText,
                        item.candidate.annotation,
                        CandidateTapTarget(current.generation, presentationEpoch, item.index,
                            item.candidate, registration?.depth ?: 0))
                }, expandedStatus = registration != null, selectedMenuIndex = selectedMenuIndex,
                    selectedPreviewTruncated = selectedPreviewTruncated,
                    canPreviousPage = candidate?.canPreviousPage == true,
                    canNextPage = candidate?.canNextPage == true,
                    showPageControls = candidate != null,
                    registration = registration?.let {
                        RegistrationPresentation.from(it, touch, current.view.completion)
                    })
            }
        }
        val boundary = CandidatePresentationBoundary(current, current?.view, generation, touch,
            presentation.menuItems.mapNotNull { it.target })
        if (candidatePresentationBoundary?.sameAs(boundary) != true) {
            candidatePresentationBoundary = boundary
            candidatePresentationEpoch++
            presentation = presentation.copy(menuItems = presentation.menuItems.map { item ->
                item.copy(target = item.target?.let { target ->
                    CandidateTapTarget(target.sessionGeneration, candidatePresentationEpoch, target.index,
                        target.candidate, target.registrationDepth, target.kind)
                })
            })
        }
        renderedPresentation = presentation
        renderedRegistration = current?.view?.registration
        renderedMode = current?.engine?.state?.mode?.let { mode -> when (mode) {
            InputMode.HIRAGANA -> "あ"
            InputMode.KATAKANA -> "ア"
            InputMode.HALFWIDTH -> "ｱ"
            InputMode.DIRECT -> "A"
            InputMode.FULLWIDTH -> "Ａ"
        } }.orEmpty()
        candidateStatusView?.let {
            bindRegistrationCallbacks(it, renderedRegistration)
            it.show(presentation)
        } ?: run { statusView?.text = presentation.text }
        renderPhysicalPopup()
        val statusEnabled = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("show_status", true)
        candidateStatusView?.visibility = if (current == null || current.protectedInput ||
            (!statusEnabled && !current.hasComposition && !current.failed && appEmacsNotice == null)) View.GONE else View.VISIBLE
        touchKeyboardView?.updateMode(current?.engine?.state?.mode,
            current != null && current.active && !current.failed && !current.protectedInput &&
                current.view.registration?.saving != true && current.view.deletion == null)
        touchKeyboardView?.updatePrimaryAction(when {
            current?.protectedInput != true && current?.view?.registration != null &&
                current.view.registration?.innerCandidate == null && current.view.registration?.innerComposing == null -> "登録"
            current?.protectedInput != true && current?.hasComposition == true -> "確定"
            else -> when (currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) {
                EditorInfo.IME_ACTION_SEARCH -> "検索"
                EditorInfo.IME_ACTION_SEND -> "送信"
                EditorInfo.IME_ACTION_DONE -> "完了"
                EditorInfo.IME_ACTION_GO -> "実行"
                EditorInfo.IME_ACTION_NEXT -> "次へ"
                else -> "↵"
            }
        }, enabled = current?.view?.prediction?.failure == null)
    }

    private fun onCandidateTapped(target: CandidateTapTarget) {
        configurationRestart = null
        val current = session ?: return
        if (!current.active || current.failed || current.protectedInput ||
            current.generation != target.sessionGeneration || target.presentationEpoch != candidatePresentationEpoch) return
        val registration = current.view.registration
        if ((registration?.depth ?: 0) != target.registrationDepth) return
        if (target.kind != CandidateTargetKind.CONVERSION) {
            val prediction = current.view.prediction ?: return
            val matches = when (target.kind) {
                CandidateTargetKind.PREDICTION -> prediction.items.getOrNull(target.index) === target.candidate
                CandidateTargetKind.READING_FALLBACK -> prediction === target.candidate && prediction.fallbackText != null
                else -> false
            }
            if (!matches || !shouldShowTouchCharacters()) return
            current.handleTouch(BasicSkkAction.CommitPrediction(target.index,
                expectedCandidate = prediction.items.getOrNull(target.index), expectedView = prediction))
            render()
            return
        }
        val candidate = if (registration == null) current.view.candidate else registration.innerCandidate
        val item = (candidate?.menu.orEmpty() + candidate?.pageItems.orEmpty())
            .firstOrNull { it.index == target.index } ?: return
        if (item.candidate !== target.candidate) return
        current.handle(BasicSkkAction.SelectCandidate(target.index, item.candidate))
        render()
    }

    /**
     * 登録操作は描画した枠そのものがまだ最前面である場合だけ受け付けます。
     * 非同期保存や再帰登録の復帰後に残ったポップアップの操作で、別の本文を保存しません。
     */
    private fun bindRegistrationCallbacks(surface: CandidateStatusView, expected: RegistrationView?) {
        surface.setRegistrationCallbacks(
            onRegister = {
                val current = session
                val registration = current?.view?.registration
                if (current != null && current.active && !current.failed && !current.protectedInput &&
                    registration === expected && registration != null && !registration.saving &&
                    registration.innerCandidate == null && registration.innerComposing == null) {
                    current.saveRegistration(registration)
                    render()
                }
            },
            onCancel = {
                val current = session
                if (current != null && current.active && !current.failed && !current.protectedInput &&
                    current.view.registration === expected && expected != null) {
                    current.cancelRegistration(expected)
                    render()
                }
            },
            onCaret = { direction ->
                val current = session
                if (current != null && current.active && !current.failed && !current.protectedInput &&
                    current.view.registration === expected && expected != null) {
                    current.moveRegistrationCursor(direction, expected)
                    render()
                }
            },
        )
    }

    private fun onTouchCommand(command: TouchKeyboardCommand) {
        configurationRestart = null
        if (command == TouchKeyboardCommand.SwitchIme) {
            showImePicker()
            return
        }
        val current = session ?: return
        if (!current.active || current.failed) return
        when (command) {
            is TouchKeyboardCommand.Skk -> when (val action = command.action) {
                is BasicSkkAction.Text -> current.handleTouchText(action.text)
                BasicSkkAction.Enter -> current.handleTouchPrimary()
                BasicSkkAction.Backspace -> current.handleTouchBackspace()
                is BasicSkkAction.SetInputMode -> current.handleTouchSetInputMode(action.mode)
                else -> current.handleTouch(action)
            }
            TouchKeyboardCommand.SpaceOrConvert -> current.handleTouchSpaceOrConvert()
            TouchKeyboardCommand.SwitchIme -> return
        }
        render()
    }

    private fun shouldShowTouchCharacters(): Boolean {
        val hideForHardware = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("hide_touch_keyboard_with_hardware", true)
        return !hideForHardware || !HardwareKeyboardPresence.isAlphabeticKeyboardConnected()
    }

    private fun updateTouchKeyboardVisibility() = applyPresentationMode(shouldShowTouchCharacters())

    private fun applyPresentationMode(touch: Boolean) {
        session?.setTouchCandidatePresentation(touch)
        touchKeyboardView?.visibility = if (touch) View.VISIBLE else View.GONE
        touchKeyboardView?.setCharacterAreaVisible(touch)
        updateInputViewShown()
        if (touch) {
            physicalPopup?.dismiss()
            if (annotationConnection != null) clearInlineAnnotation()
        }
        inputHost?.requestLayout()
    }

    private fun isImeSwitchGesture(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed && !event.isAltPressed &&
            !event.isMetaPressed && !event.isShiftPressed && event.repeatCount == 0

    private fun showImePicker() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
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

internal fun candidateItemsForPresentation(
    candidate: CandidateView?,
    touch: Boolean,
): List<LabeledCandidate> = if (touch) candidate?.pageItems.orEmpty() else candidate?.menu.orEmpty()

internal fun acceptsTouchShowRequest(
    info: EditorInfo?,
    connection: InputConnection?,
    touchCharacters: Boolean,
): Boolean = info != null && connection != null && info.inputType != InputType.TYPE_NULL && touchCharacters

internal fun shouldRestorePhysicalHostForKey(
    touchCharacters: Boolean,
    active: Boolean,
    protectedInput: Boolean,
): Boolean = !touchCharacters && active && !protectedInput

internal fun physicalHostAllowedForStartedInput(
    restarting: Boolean,
    previouslyAllowed: Boolean,
): Boolean = !restarting || previouslyAllowed
