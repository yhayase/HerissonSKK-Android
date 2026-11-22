package se.haya.skk.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import se.haya.skk.CandidateStatusView
import se.haya.skk.R
import se.haya.skk.core.BasicSkkAction
import se.haya.skk.core.editing.EditCommand
import se.haya.skk.core.InputMode
import se.haya.skk.settings.TouchKeyboardSettings
import se.haya.skk.settings.ScreenKeyboardLayout
import se.haya.skk.settings.TouchKeyboardSettingsStore
import kotlin.math.abs
import kotlin.math.roundToInt

/** 下部の候補領域と4段の文字面を持ち、物理側の補助行は作りません。 */
internal class TouchKeyboardView(
    context: Context,
    private val tenKeyController: TenKeyController = TenKeyController(),
    private val onCommand: (TouchKeyboardCommand) -> Unit,
) : LinearLayout(context) {
    val candidateStatusView = CandidateStatusView(context).apply { reserveTouchViewport = true }
    private val controller = TouchKeyboardController()
    private var keyboardSettings = TouchKeyboardSettings()
    private var tenKeyView: TenKeyKeyboardView? = null
    private var tenKeyAlignmentControl: Button? = null
    private val tenKeyAlignmentBounds = Rect()
    private var wideQwerty: WideQwertyKeyboard? = null
    private var wide = false
    internal var onLayoutChanged: ((ScreenKeyboardLayout) -> Unit)? = null
    private var generation = 0
    private var layoutSwitchKey: LayoutSwitchKey? = null
    internal var onGeometryChanged: ((TouchKeyboardSettings) -> Unit)? = null
    private var adjustmentPanel: ScrollView? = null
    private var qwertyGapSlider: SeekBar? = null
    private var qwertyWidthSlider: SeekBar? = null
    private var tenKeyPositionSlider: SeekBar? = null
    internal val adjustingGeometry get() = adjustmentPanel != null

    /** 候補・調整面・下の余白を除く、表示中の文字面に限って操作を引き受けます。 */
    internal fun isCharacterAreaHit(rawX: Float, rawY: Float): Boolean {
        if (!characterArea.isShown) return false
        val bounds = Rect()
        if (!characterArea.getLocalVisibleRect(bounds)) return false
        val location = IntArray(2)
        characterArea.getLocationOnScreen(location)
        bounds.offset(location[0], location[1])
        return rawX >= bounds.left && rawX < bounds.right &&
            rawY >= bounds.top && rawY < bounds.bottom
    }

    /** 調整操作は文字面の状態とSKKの入力状態を変えません。 */
    internal fun setTenKeyGeometry(heightDp: Int, widthPercent: Int, positionPercent: Int, bottomPaddingDp: Int = keyboardSettings.tenKeyBottomPaddingDp) {
        cancelTouches()
        val updated = keyboardSettings.copy(tenKeyHeightDp = heightDp.coerceIn(160, 400),
            tenKeyWidthPercent = widthPercent.coerceIn(60, 100), tenKeyPositionPercent = positionPercent.coerceIn(0, 100),
            tenKeyBottomPaddingDp = bottomPaddingDp.coerceIn(0, 160))
        if (TouchKeyboardSettingsStore.saveGeometry(context, updated.tenKeyHeightDp, updated.tenKeyWidthPercent, updated.tenKeyPositionPercent, updated.tenKeyBottomPaddingDp)) {
            keyboardSettings = updated
            onGeometryChanged?.invoke(updated)
        }
        tenKeyPositionSlider?.progress = keyboardSettings.tenKeyPositionPercent
        applyBottomInset()
        requestLayout()
    }

    internal fun showGeometryAdjustment() {
        val qwerty = keyboardSettings.layout == ScreenKeyboardLayout.QWERTY
        if (adjustmentPanel != null) return
        cancelTouches()
        val panel = LinearLayout(context).apply { orientation = VERTICAL; setBackgroundColor(KeyboardColors.resolve(context).panelBackground) }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(panel, android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        adjustmentPanel = scroll
        panel.addView(TextView(context).apply { text = "サイズ・位置を調整"; textSize = 14f; setTextColor(KeyboardColors.resolve(context).text) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(24)))
        fun slider(label: String, minimum: Int, maximum: Int, initial: Int, change: (Int) -> Unit): SeekBar {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val value = TextView(context).apply { text = "$label $initial"; textSize = 12f; setTextColor(KeyboardColors.resolve(context).text) }
            line.addView(value, LayoutParams(dp(100), LayoutParams.MATCH_PARENT))
            val bar = SeekBar(context).apply {
                contentDescription = "${if (qwerty) "QWERTY" else "10キー"}の$label"
                min = minimum; max = maximum; progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        value.text = "$label $progress"
                        if (fromUser) change(progress)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) { cancelTouches() }
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            }
            line.addView(bar, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            panel.addView(line, LayoutParams(LayoutParams.MATCH_PARENT, dp(36)))
            return bar
        }
        if (qwerty) {
            val height = TouchKeyboardSettings.QWERTY_HEIGHT_DP
            val width = qwertyWidthRange(measuredWidth)
            val position = TouchKeyboardSettings.QWERTY_POSITION_PERCENT
            val bottom = TouchKeyboardSettings.QWERTY_BOTTOM_PADDING_DP
            slider("高さ", height.first, height.last, keyboardSettings.qwertyHeightDp) {
                setQwertyGeometry(keyboardSettings.qwertyGapPercent, it)
            }
            qwertyWidthSlider = slider("幅", width.first, width.last, keyboardSettings.qwertyWidthPercent.coerceIn(width)) {
                setQwertyGeometry(widthPercent = it)
            }
            slider("左右位置", position.first, position.last, keyboardSettings.qwertyPositionPercent) {
                setQwertyGeometry(positionPercent = it)
            }
            slider("下の余白", bottom.first, bottom.last, keyboardSettings.qwertyBottomPaddingDp) {
                setQwertyGeometry(bottomPaddingDp = it)
            }
            if (wide) {
                val gap = qwertyGapPercentRange(qwertyWidth(measuredWidth))
                qwertyGapSlider = slider("分割時の間隔", gap.first, gap.last, keyboardSettings.qwertyGapPercent.coerceIn(gap)) {
                    setQwertyGeometry(it)
                }
            }
        } else {
            slider("高さ", 160, 400, keyboardSettings.tenKeyHeightDp) { setTenKeyGeometry(it, keyboardSettings.tenKeyWidthPercent, keyboardSettings.tenKeyPositionPercent) }
            slider("幅", 60, 100, keyboardSettings.tenKeyWidthPercent) { setTenKeyGeometry(keyboardSettings.tenKeyHeightDp, it, keyboardSettings.tenKeyPositionPercent) }
            tenKeyPositionSlider = slider("左右位置", 0, 100, keyboardSettings.tenKeyPositionPercent) {
                setTenKeyGeometry(keyboardSettings.tenKeyHeightDp, keyboardSettings.tenKeyWidthPercent, it)
            }
            slider("下の余白", 0, 160, keyboardSettings.tenKeyBottomPaddingDp) {
                setTenKeyGeometry(keyboardSettings.tenKeyHeightDp, keyboardSettings.tenKeyWidthPercent, keyboardSettings.tenKeyPositionPercent, it)
            }
        }
        panel.addView(LinearLayout(context).apply {
            addView(commandKey("標準に戻す") {
                if (qwerty) {
                    val defaults = TouchKeyboardSettings()
                    setQwertyGeometry(defaults.qwertyGapPercent, defaults.qwertyHeightDp, defaults.qwertyWidthPercent,
                        defaults.qwertyPositionPercent, defaults.qwertyBottomPaddingDp)
                } else setTenKeyGeometry(256, 100, 50, 0)
                hideGeometryAdjustment(); showGeometryAdjustment()
            }.apply { contentDescription = "サイズ・位置を標準に戻す" }, LayoutParams(0, dp(40), 1f))
            addView(commandKey("調整を閉じる") { hideGeometryAdjustment() }, LayoutParams(0, dp(40), 1f))
        })
        addView(scroll, 1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun setQwertyGeometry(
        gapPercent: Int = keyboardSettings.qwertyGapPercent,
        heightDp: Int = keyboardSettings.qwertyHeightDp,
        widthPercent: Int? = null,
        positionPercent: Int? = null,
        bottomPaddingDp: Int? = null,
    ) {
        val updated = keyboardSettings.copy(qwertyGapPercent = gapPercent, qwertyHeightDp = heightDp,
            qwertyWidthPercent = widthPercent ?: keyboardSettings.qwertyWidthPercent,
            qwertyPositionPercent = positionPercent ?: keyboardSettings.qwertyPositionPercent,
            qwertyBottomPaddingDp = bottomPaddingDp ?: keyboardSettings.qwertyBottomPaddingDp)
        if (updated != keyboardSettings && TouchKeyboardSettingsStore.saveQwertyGeometry(
                context, gapPercent, heightDp, widthPercent, positionPercent, bottomPaddingDp)) {
            keyboardSettings = updated
            onGeometryChanged?.invoke(updated)
        }
        applyBottomInset()
        wideQwerty?.requestLayout()
        requestLayout()
    }

    private fun qwertyWidthRange(width: Int): IntRange {
        val range = TouchKeyboardSettings.QWERTY_WIDTH_PERCENT
        val minimum = if (wide && width > 0)
            ((dp(WIDE_QWERTY_MIN_WIDTH_DP) * 100 + width - 1) / width).coerceIn(range)
        else range.first
        return minimum..range.last
    }

    private fun qwertyWidth(available: Int): Int =
        available * keyboardSettings.qwertyWidthPercent.coerceIn(qwertyWidthRange(available)) / 100

    private fun qwertyGapPercentRange(width: Int): IntRange {
        val maxKeyGap = width - (dp(MIN_WIDE_KEY_WIDTH_DP) * SPLIT_HALF_UNITS * 2).roundToInt()
        val maxPercent = minOf(TouchKeyboardSettings.QWERTY_GAP_PERCENT.last, maxKeyGap * 100 / width)
        val minPercent = (dp(HANDLE_WIDTH_DP * 2) * 100 + width - 1) / width
        return minPercent..maxPercent
    }

    private fun updateQwertyGapSlider(bar: SeekBar, width: Int) {
        val range = qwertyGapPercentRange(maxOf(dp(WIDE_QWERTY_MIN_WIDTH_DP), width))
        bar.max = range.last
        bar.min = range.first
        bar.progress = keyboardSettings.qwertyGapPercent.coerceIn(range)
    }

    /** 配列だけを保存し、SKKのセッションや各文字面の選択状態を保持します。 */
    internal fun switchLayout(target: ScreenKeyboardLayout): Boolean {
        if (target == keyboardSettings.layout) return true
        cancelTouches()
        if (!TouchKeyboardSettingsStore.saveLayout(context, target)) return false
        keyboardSettings = keyboardSettings.copy(layout = target)
        onLayoutChanged?.invoke(target)
        hideGeometryAdjustment()
        rebuildKeys()
        applyBottomInset()
        requestLayout()
        return true
    }

    private fun hideGeometryAdjustment() {
        adjustmentPanel?.let { removeView(it) }; adjustmentPanel = null
        qwertyGapSlider = null
        qwertyWidthSlider = null
        tenKeyPositionSlider = null
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) resources.displayMetrics.widthPixels else MeasureSpec.getSize(widthMeasureSpec)
        val useWide = availableWidth >= dp(WIDE_QWERTY_MIN_WIDTH_DP)
        if (wide != useWide) {
            wide = useWide
            if (keyboardSettings.layout == ScreenKeyboardLayout.QWERTY) {
                hideGeometryAdjustment()
                rebuildKeys()
            }
        }
        if (charactersVisible) {
            val isTenKey = tenKeyView != null
            val width = if (isTenKey) availableWidth * keyboardSettings.tenKeyWidthPercent / 100 else qwertyWidth(availableWidth)
            val position = if (isTenKey) keyboardSettings.tenKeyPositionPercent else keyboardSettings.qwertyPositionPercent
            val left = (availableWidth - width) * position / 100
            val screenHeight = resources.displayMetrics.heightPixels
            val availableHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) screenHeight else minOf(screenHeight, MeasureSpec.getSize(heightMeasureSpec))
            val candidateHeight = if (candidateStatusView.visibility == View.GONE) 0 else {
                candidateStatusView.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.AT_MOST))
                candidateStatusView.measuredHeight
            }
            adjustmentPanel?.let { panel ->
                qwertyGapSlider?.let { updateQwertyGapSlider(it, width) }
                qwertyWidthSlider?.let { bar ->
                    val range = qwertyWidthRange(availableWidth)
                    bar.min = range.first
                    bar.progress = keyboardSettings.qwertyWidthPercent.coerceIn(range)
                }
                // IME窓自身の縮小後の高さを半分にすると、調整面まで再縮小して下に空きが残ります。
                val maximum = minOf(maxOf(0, availableHeight - candidateHeight - paddingBottom),
                    maxOf(0, (screenHeight - candidateHeight - paddingBottom) / 2))
                panel.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST))
                panel.layoutParams.height = panel.measuredHeight
            }
            val preferredHeight = if (isTenKey) keyboardSettings.tenKeyHeightDp else keyboardSettings.qwertyHeightDp
            val height = minOf(dp(preferredHeight), maxOf(0, availableHeight - candidateHeight - (adjustmentPanel?.measuredHeight ?: 0) - paddingBottom))
            setMeasuredGeometry(characterArea, if (isTenKey) availableWidth else width,
                LayoutParams.WRAP_CONTENT, if (isTenKey) 0 else left)
            val keyboard = tenKeyView ?: wideQwerty
            if (keyboard != null) {
                setMeasuredGeometry(keyboard, width, height, if (isTenKey) left else 0)
                if (isTenKey) refreshTenKeyAlignmentControl(availableWidth, width, height, left)
            } else {
                for (row in 0 until characterArea.childCount) {
                    val rowHeight = (row + 1) * height / 4 - row * height / 4
                    setMeasuredGeometry(characterArea.getChildAt(row), width, rowHeight, 0)
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun setMeasuredGeometry(view: View, width: Int, height: Int, left: Int) {
        val params = view.layoutParams as LayoutParams
        if (params.width == width && params.height == height && params.leftMargin == left) return
        params.width = width
        params.height = height
        params.gravity = Gravity.LEFT
        params.leftMargin = left
        // 入れ子の測定キャッシュも更新し、形状変更直後に文字面と親の高さを揃えます。
        view.forceLayout()
        characterArea.forceLayout()
    }

    private fun refreshTenKeyAlignmentControl(availableWidth: Int, keyboardWidth: Int, keyboardHeight: Int, keyboardLeft: Int) {
        val button = tenKeyAlignmentControl ?: return
        val rightGap = availableWidth - keyboardLeft - keyboardWidth
        val moveRight = keyboardLeft <= rightGap
        val gapLeft = if (moveRight) keyboardLeft + keyboardWidth else 0
        val gapWidth = if (moveRight) rightGap else keyboardLeft
        val size = minOf(dp(48), gapWidth - dp(8), keyboardHeight - dp(8))
        if (size < dp(32)) {
            tenKeyAlignmentBounds.setEmpty()
            button.visibility = View.GONE
            return
        }
        button.visibility = View.VISIBLE
        button.text = if (moveRight) "→|" else "|←"
        button.contentDescription = if (moveRight) "キーボードを右側に移動" else "キーボードを左側に移動"
        val x = gapLeft + (gapWidth - size) / 2
        val y = (keyboardHeight - size) / 2
        tenKeyAlignmentBounds.set(x, y, x + size, y + size)
    }

    fun configure(settings: TouchKeyboardSettings) {
        if (keyboardSettings == settings) return
        keyboardSettings = settings
        applyBottomInset()
        hideGeometryAdjustment()
        rebuildKeys()
    }
    private val characterArea = object : LinearLayout(context) {
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            tenKeyAlignmentControl?.let { button ->
                val bounds = tenKeyAlignmentBounds
                button.measure(MeasureSpec.makeMeasureSpec(bounds.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(bounds.height(), MeasureSpec.EXACTLY))
                button.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
        }
    }.apply {
        orientation = VERTICAL
        isMotionEventSplittingEnabled = true
    }
    private val characterButtons = mutableListOf<Pair<Button, String>>()
    private val cancelGestures = mutableListOf<() -> Unit>()
    private val refreshGuides = mutableListOf<() -> Unit>()
    private val shiftButtons = mutableListOf<Button>()
    private var modeButton: Button? = null
    private var primaryButton: Button? = null
    private var inputMode: InputMode? = null
    private var modeEnabled = true
    private var primaryLabel = "⏎"
    private var spaceConverts = false
    private var readingConversionEnabled = false
    private var touchShiftPending = false
    private var primaryEnabled = true
    private var bottomInset = 0
    private var charactersVisible = true
    private var guide: PopupWindow? = null
    private var guideOwner: View? = null
    internal var visibleFlickGuide: FlickGuide? = null
        private set
    private var modeMenu: PopupWindow? = null
    /** テストと表示更新で共有する、現在選択中のフリック候補です。 */
    internal var activeFlickGuide: TouchKeyboardController.Flick? = null
        private set

    init {
        orientation = VERTICAL
        gravity = Gravity.BOTTOM
        isMotionEventSplittingEnabled = true
        setBackgroundColor(KeyboardColors.resolve(context).panelBackground)
        setOnApplyWindowInsetsListener { _, insets ->
            bottomInset = if (Build.VERSION.SDK_INT >= 30) {
                maxOf(
                    insets.getInsets(WindowInsets.Type.navigationBars() or
                        WindowInsets.Type.systemGestures() or
                        WindowInsets.Type.mandatorySystemGestures() or
                        WindowInsets.Type.tappableElement()).bottom,
                    insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom,
                )
            } else if (Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION")
                maxOf(insets.systemWindowInsetBottom, insets.systemGestureInsets.bottom,
                    insets.mandatorySystemGestureInsets.bottom, insets.tappableElementInsets.bottom)
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            applyBottomInset()
            insets
        }
        addView(candidateStatusView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(characterArea, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rebuildKeys()
        applyBottomInset()
    }

    fun setCharacterAreaVisible(visible: Boolean) {
        if (charactersVisible && !visible) cancelTouches()
        charactersVisible = visible
        candidateStatusView.reserveTouchViewport = visible
        characterArea.visibility = if (visible) View.VISIBLE else View.GONE
        applyBottomInset()
    }

    // IMEの親がInsetsを消費しても、OSの切替・非表示ボタンと文字キーが重ならない領域を確保します。
    private fun applyBottomInset() {
        val extra = if (keyboardSettings.layout == ScreenKeyboardLayout.TEN_KEY) keyboardSettings.tenKeyBottomPaddingDp
            else keyboardSettings.qwertyBottomPaddingDp
        setPadding(0, 0, 0, if (charactersVisible) maxOf(dp(48), bottomInset) + dp(extra) else 0)
    }

    fun configurationChanged() {
        setBackgroundColor(KeyboardColors.resolve(context).panelBackground)
        candidateStatusView.refreshColors()
        hideGeometryAdjustment()
        rebuildKeys(); applyBottomInset()
    }

    fun resetForEditorSession() {
        cancelTouches()
        controller.reset()
        rebuildKeys()
    }

    fun updateMode(mode: InputMode?, enabled: Boolean = true) {
        tenKeyView?.setSkkEnabled(enabled)
        inputMode = mode
        modeEnabled = enabled
        modeButton?.apply {
            text = modeLabel(mode)
            contentDescription = "入力モード ${modeLabel(mode)}"
            isEnabled = enabled
        }
        if (!enabled) { modeMenu?.dismiss(); modeMenu = null }
    }

    fun updateShiftPending(pending: Boolean) {
        touchShiftPending = pending
        tenKeyView?.updateShiftPending(pending)
    }

    fun updateReadingConversion(enabled: Boolean) {
        readingConversionEnabled = enabled
        tenKeyView?.updateReadingConversion(enabled)
    }

    fun updateSpaceAction(convert: Boolean) {
        spaceConverts = convert
        tenKeyView?.updateSpaceAction(convert)
        wideQwerty?.updateSpaceAction(convert)
    }

    fun updatePrimaryAction(label: String, enabled: Boolean = true) {
        tenKeyView?.updatePrimaryAction(label, enabled)
        primaryLabel = label
        primaryEnabled = enabled
        primaryButton?.apply {
            (this as? PrimaryActionButton)?.setActionLabel(label) ?: run { text = label; contentDescription = label }
            isEnabled = enabled
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // 分割配送の前に二本目を検知し、配列切替だけを取り消します。
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.pointerCount > 1) {
            layoutSwitchKey?.cancelInteraction()
            wideQwerty?.cancelGapDrag()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        cancelTouches()
        super.onDetachedFromWindow()
    }

    internal fun cancelInteractions() = cancelTouches()

    private fun cancelTouches() {
        layoutSwitchKey?.cancelInteraction()
        tenKeyView?.cancelTouches()
        wideQwerty?.cancelGapDrag()
        cancelGestures.forEach { it() }
        controller.cancelPointers()
        guide?.dismiss(); guide = null; guideOwner = null; visibleFlickGuide = null
        activeFlickGuide = null
        modeMenu?.dismiss(); modeMenu = null
        refreshShift()
    }

    private fun rebuildKeys() {
        cancelTouches()
        generation++
        layoutSwitchKey?.dispose(); layoutSwitchKey = null
        tenKeyView?.dispose()
        characterArea.removeAllViews()
        characterButtons.clear()
        cancelGestures.clear()
        refreshGuides.clear()
        shiftButtons.clear()
        modeButton = null
        primaryButton = null
        tenKeyView = null
        tenKeyAlignmentControl = null
        tenKeyAlignmentBounds.setEmpty()
        wideQwerty = null
        if (keyboardSettings.layout == ScreenKeyboardLayout.TEN_KEY) {
            tenKeyView = TenKeyKeyboardView(context, keyboardSettings, tenKeyController, onCommand).also {
                characterArea.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, dp(keyboardSettings.tenKeyHeightDp)))
                it.onAdjustGeometry = { showGeometryAdjustment() }
                it.onSwitchLayout = { switchLayout(ScreenKeyboardLayout.QWERTY) }
                it.updatePrimaryAction(primaryLabel, primaryEnabled)
                it.updateSpaceAction(spaceConverts)
                it.setSkkEnabled(modeEnabled)
                it.updateReadingConversion(readingConversionEnabled)
                it.updateShiftPending(touchShiftPending)
            }
            tenKeyAlignmentControl = Button(context).apply {
                visibility = View.GONE
                minWidth = 0
                minHeight = 0
                minimumWidth = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                textSize = 22f
                isAllCaps = false
                setTextColor(KeyboardColors.resolve(context).text)
                background = GradientDrawable().apply {
                    setColor(KeyboardColors.resolve(context).functionBackground)
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener {
                    val position = keyboardSettings.tenKeyPositionPercent
                    val target = if (position == 50) 100 else 100 - position
                    setTenKeyGeometry(keyboardSettings.tenKeyHeightDp, keyboardSettings.tenKeyWidthPercent, target)
                }
                characterArea.addView(this, LayoutParams(0, 0))
            }
            return
        }
        if (wide) {
            wideQwerty = buildWideQwerty().also {
                characterArea.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, dp(keyboardSettings.qwertyHeightDp)))
            }
        } else buildCompactQwerty()
        refreshShift()
        updateMode(inputMode, modeEnabled)
        updatePrimaryAction(primaryLabel, primaryEnabled)
    }

    private fun buildCompactQwerty() {
        if (controller.page == TouchKeyboardController.Page.LETTERS) {
            TouchKeyboardController.LETTER_ROWS.forEachIndexed { index, letters ->
                val row = newRow()
                if (index == 1) row.setPadding(dp(12), 0, dp(12), 0)
                if (index == 2) row.addView(shiftKey(), keyParams())
                letters.forEach { row.addView(textKey(it.toString()), keyParams()) }
                if (index == 2) {
                    row.addView(textKey("-"), keyParams())
                    row.addView(backspaceKey(), keyParams())
                }
                characterArea.addView(row)
            }
        } else {
            val rows = if (controller.page == TouchKeyboardController.Page.SYMBOLS_1)
                TouchKeyboardController.SYMBOL_ROWS_1 else TouchKeyboardController.SYMBOL_ROWS_2
            rows.forEachIndexed { index, values ->
                val row = newRow()
                if (index == 2) row.addView(symbolPageKey(), keyParams(1.3f))
                values.forEach { row.addView(textKey(it), keyParams()) }
                if (index == 2) row.addView(backspaceKey(), keyParams(1.3f))
                characterArea.addView(row)
            }
        }
        val bottom = newRow()
        bottom.addView(makeLayoutSwitchKey(), keyParams())
        bottom.addView(pageKey(), keyParams(1.1f))
        bottom.addView(makeModeKey(), keyParams(1.1f))
        bottom.addView(spaceKey(), keyParams(3f))
        bottom.addView(textKey(","), keyParams())
        bottom.addView(textKey("."), keyParams())
        bottom.addView(makePrimaryKey(), keyParams(1.5f))
        characterArea.addView(bottom)
    }

    private fun makeModeKey() = commandKey(modeLabel(inputMode), alternative = true) {
        val target = if (inputMode == InputMode.DIRECT || inputMode == InputMode.FULLWIDTH)
            InputMode.HIRAGANA else InputMode.DIRECT
        send(BasicSkkAction.SetInputMode(target))
    }.apply { setOnLongClickListener { showModeMenu(this); true } }.also { modeButton = it }

    private fun makeLayoutSwitchKey(): LayoutSwitchKey {
        val currentGeneration = generation
        return LayoutSwitchKey(context, ScreenKeyboardLayout.TEN_KEY,
            onSwitchLayout = { switchLayout(ScreenKeyboardLayout.TEN_KEY) },
            onSwitchIme = { onCommand(TouchKeyboardCommand.SwitchIme) },
            onAdjustGeometry = ::showGeometryAdjustment,
            onOpenSettings = { onCommand(TouchKeyboardCommand.OpenSettings) },
            isCurrent = { currentGeneration == generation },
        ).also { button ->
            val description = button.contentDescription
            button.configureKey("")
            button.contentDescription = description
            layoutSwitchKey = button
        }
    }

    private fun pageKey() = commandKey(if (controller.page == TouchKeyboardController.Page.LETTERS) "123" else "ABC") {
        controller.togglePage(); rebuildKeys()
    }

    private fun symbolPageKey() = commandKey(
        if (controller.page == TouchKeyboardController.Page.SYMBOLS_1) "記号2" else "記号1",
    ) { controller.toggleSymbolPage(); rebuildKeys() }

    private fun spaceKey() = commandKey("␣") { onCommand(TouchKeyboardCommand.SpaceOrConvert()) }
        .apply { contentDescription = "空白" }

    private fun makePrimaryKey(): Button {
        val primaryGeneration = generation
        return PrimaryActionButton(context).also { button ->
            button.configureKey(primaryLabel)
            button.setActionLabel(primaryLabel)
            button.setOnClickListener { if (primaryGeneration == generation) send(BasicSkkAction.Enter) }
            primaryButton = button
        }
    }

    private fun buildWideQwerty() = WideQwertyKeyboard().apply {
        fun letters(values: String) = values.map { textKey(it.toString()) }
        if (controller.page == TouchKeyboardController.Page.LETTERS) {
            addRow(0, 0f, letters("qwert"), letters("yuiop"), backspaceKey(), 1.5f)
            addRow(1, .5f, letters("asdfg"), letters("hjkl-"), makePrimaryKey())
            addLetterKey(shiftKey(), 2, 0f, 0f, 1f, splitUnits = 1.5f)
            addRow(2, 1f, letters("zxcvb"), letters("nm,."), shiftKey(), 1.5f)
        } else {
            val rows = if (controller.page == TouchKeyboardController.Page.SYMBOLS_1)
                TouchKeyboardController.SYMBOL_ROWS_1 else TouchKeyboardController.SYMBOL_ROWS_2
            addRow(0, 0f, rows[0].take(5).map(::textKey), rows[0].drop(5).map(::textKey), backspaceKey(), 1.5f)
            addRow(1, .5f, rows[1].take(5).map(::textKey), rows[1].drop(5).map(::textKey), makePrimaryKey())
            addRow(2, .5f, listOf(symbolPageKey()) + rows[2].take(4).map(::textKey),
                rows[2].drop(4).map(::textKey) + textKey(","), textKey("."))
        }
        addControls()
    }

    private data class WideKey(
        val view: View, val row: Int, val joinedStart: Float, val splitStart: Float,
        val units: Float, val splitUnits: Float, val onRight: Boolean,
    )

    private inner class WideQwertyKeyboard : ViewGroup(context) {
        private val letterKeys = mutableListOf<WideKey>()
        private val leftCommands = listOf(makeLayoutSwitchKey(), pageKey(), makeModeKey())
        private val leftSpace = spaceKey()
        private val toggle = commandKey("", alternative = true) { toggleSplit() }.apply {
            setOnLongClickListener { showGeometryAdjustment(); true }
        }
        private val rightSpace = spaceKey()
        private val rightCommands = listOf(
            arrowKey("←", "カーソルを左へ移動", EditCommand.LEFT),
            arrowKey("↓", "カーソルを下へ移動", EditCommand.DOWN),
            arrowKey("↑", "カーソルを上へ移動", EditCommand.UP),
            arrowKey("→", "カーソルを右へ移動", EditCommand.RIGHT),
        )
        private val leftHandle = handleKey("◀", "左側の境界を調整")
        private val rightHandle = handleKey("▶", "右側の境界を調整")
        private val bounds = mutableMapOf<View, Rect>()
        private var effectiveGap = 0
        private var drag: GapDrag? = null
        private var previewGap: Int? = null
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        init {
            isMotionEventSplittingEnabled = true
            updateSplitPresentation()
            updateSpaceAction(spaceConverts)
        }

        fun addControls() {
            (leftCommands + leftSpace + toggle + rightSpace + rightCommands + leftHandle + rightHandle).forEach { addView(it) }
        }

        fun updateSpaceAction(convert: Boolean) {
            listOf(leftSpace, rightSpace).forEach { space ->
                space.text = if (convert) "変換" else "␣"
                space.contentDescription = if (convert) "変換" else "空白"
            }
        }

        private fun arrowKey(label: String, description: String, action: EditCommand) =
            commandKey(label) { onCommand(TouchKeyboardCommand.MoveCursor(action)) }.apply {
                contentDescription = description
            }

        private fun handleKey(label: String, description: String) = commandKey(label) { showGeometryAdjustment() }.apply {
            background = null
            contentDescription = description
        }

        private fun toggleSplit() {
            val split = !keyboardSettings.qwertySplit
            cancelTouches()
            if (TouchKeyboardSettingsStore.saveQwertySplit(context, split)) {
                keyboardSettings = keyboardSettings.copy(qwertySplit = split)
                onGeometryChanged?.invoke(keyboardSettings)
                updateSplitPresentation()
                requestLayout()
            }
        }

        private fun updateSplitPresentation() {
            val split = keyboardSettings.qwertySplit
            toggle.text = if (split) "→←" else "←→"
            toggle.contentDescription = if (split) "QWERTYの分割を解除" else "QWERTYを分割"
            leftHandle.visibility = if (split) View.VISIBLE else View.GONE
            rightHandle.visibility = leftHandle.visibility
        }

        fun addRow(row: Int, stagger: Float, left: List<View>, right: List<View>, last: View, lastUnits: Float = 1f) {
            left.forEachIndexed { i, key -> addLetterKey(key, row, stagger + i, stagger + .5f + i) }
            (right + last).forEachIndexed { i, key ->
                val units = if (key === last) lastUnits else 1f
                addLetterKey(key, row, stagger + left.size + i, stagger + i, units, onRight = true)
            }
        }

        fun addLetterKey(view: View, row: Int, joinedStart: Float, splitStart: Float,
            units: Float = 1f, splitUnits: Float = units, onRight: Boolean = false) {
            addView(view)
            letterKeys += WideKey(view, row, joinedStart, splitStart, units, splitUnits, onRight)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            if (measuredWidth != width) cancelGapDrag()
            val split = keyboardSettings.qwertySplit
            val savedGap = (width * keyboardSettings.qwertyGapPercent / 100f).roundToInt()
            effectiveGap = if (split) limitGap(previewGap ?: savedGap, width) else 0
            val half = (width - effectiveGap) / 2f
            val unit = if (split) half / SPLIT_HALF_UNITS else width / JOINED_UNITS

            bounds.clear()
            fun place(view: View, row: Int, start: Float, end: Float) {
                val x1 = start.roundToInt()
                val x2 = end.roundToInt()
                val y1 = row * height / 4
                val y2 = (row + 1) * height / 4
                val marginX = minOf(dp(1), (x2 - x1) / 2)
                val marginY = minOf(dp(1), (y2 - y1) / 2)
                bounds[view] = Rect(x1 + marginX, y1 + marginY, x2 - marginX, y2 - marginY)
            }
            letterKeys.forEach { key ->
                val start = if (split) key.splitStart * unit + if (key.onRight) width - half else 0f
                    else key.joinedStart * unit
                val units = if (split) key.splitUnits else key.units
                place(key.view, key.row, start, start + units * unit)
            }

            val center = width / 2f
            val toggleHalf = minOf(dp(56).toFloat(), width / JOINED_UNITS) / 2
            val commandsWidth = width / 5f
            leftCommands.forEachIndexed { i, key ->
                place(key, 3, i * commandsWidth / leftCommands.size, (i + 1) * commandsWidth / leftCommands.size)
            }
            place(leftSpace, 3, commandsWidth, center - toggleHalf)
            place(toggle, 3, center - toggleHalf, center + toggleHalf)
            place(rightSpace, 3, center + toggleHalf, width - commandsWidth)
            rightCommands.forEachIndexed { i, key ->
                val start = width - commandsWidth
                place(key, 3, start + i * commandsWidth / rightCommands.size,
                    start + (i + 1) * commandsWidth / rightCommands.size)
            }
            if (split) {
                place(leftHandle, 1, half, half + dp(HANDLE_WIDTH_DP))
                place(rightHandle, 1, width - half - dp(HANDLE_WIDTH_DP), width - half)
            }
            bounds.forEach { (view, rect) ->
                view.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            bounds.forEach { (view, rect) -> view.layout(rect.left, rect.top, rect.right, rect.bottom) }
        }

        private fun limitGap(gap: Int, width: Int): Int {
            val range = qwertyGapPercentRange(width)
            return gap.coerceIn((width * range.first / 100f).roundToInt(), (width * range.last / 100f).roundToInt())
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_DOWN || !keyboardSettings.qwertySplit) return false
            val handle = listOf(leftHandle, rightHandle).firstOrNull {
                bounds[it]?.contains(event.x.toInt(), event.y.toInt()) == true
            } ?: return false
            cancelTouches()
            drag = GapDrag(handle, event.rawX, effectiveGap)
            handle.isPressed = true
            return true
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val active = drag ?: return true
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val movement = event.rawX - active.originX
                    if (previewGap != null || abs(movement) > touchSlop) {
                        val direction = if (active.handle === leftHandle) -1 else 1
                        val gap = limitGap((active.originalGap + 2 * direction * movement).roundToInt(), width)
                        previewGap = gap
                        requestLayout()
                        if (event.actionMasked == MotionEvent.ACTION_UP && gap != active.originalGap) {
                            val percent = (gap * 100f / width).roundToInt().coerceIn(TouchKeyboardSettings.QWERTY_GAP_PERCENT)
                            setQwertyGeometry(percent, keyboardSettings.qwertyHeightDp)
                        }
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) cancelGapDrag()
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelGapDrag()
            }
            return true
        }

        fun cancelGapDrag() {
            drag?.handle?.isPressed = false
            drag = null
            if (previewGap != null) {
                previewGap = null
                requestLayout()
            }
        }
    }

    private data class GapDrag(val handle: View, val originX: Float, val originalGap: Int)

    private fun refreshShift() {
        shiftButtons.forEach { button ->
            button.isSelected = controller.shifted
            button.text = if (controller.shifted) "⇧●" else "⇧"
            button.contentDescription = if (controller.shifted) "Shift 有効" else "Shift"
        }
        characterButtons.forEach { (button, value) ->
            button.text = if (controller.shifted && controller.page == TouchKeyboardController.Page.LETTERS)
                value.uppercase() else value
            button.contentDescription = button.text
        }
        refreshGuides.forEach { it() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun shiftKey(): Button = commandKey("⇧") { controller.toggleShift(); refreshShift() }.apply {
        shiftButtons += this
        val currentGeneration = generation
        var pointer = -1
        cancelGestures += { pointer = -1; isPressed = false }
        setOnTouchListener { _, event ->
            if (currentGeneration != generation) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointer = event.getPointerId(0)
                    controller.pressShift(pointer = pointer); isPressed = true; refreshShift()
                }
                MotionEvent.ACTION_UP -> {
                    if (pointer == event.getPointerId(event.actionIndex)) controller.releaseShift(pointer = pointer)
                    pointer = -1; isPressed = false; refreshShift()
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (pointer >= 0) controller.releaseShift(cancelled = true, pointer = pointer)
                    pointer = -1; isPressed = false; refreshShift()
                }
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun textKey(value: String): Button {
        val currentGeneration = generation
        var capturedShift: Boolean? = null
        val button = FlickHintButton(context).apply {
            configureFlickHints(value, controller.up(value), controller.down(value))
            configureKey(value, character = true)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                minOf(24f * resources.displayMetrics.scaledDensity, dp(28).toFloat()))
            setOnClickListener {
            if (currentGeneration != generation) return@setOnClickListener
            onCommand(controller.text(value, shiftAtPress = capturedShift ?: controller.captureShift()))
            capturedShift = null
            refreshShift()
            }
        }
        characterButtons += button to value
        val gesture = KeyFlickGesture(FlickMetrics.fromContext(context))
        var pointer = -1
        var guideWasShown = false
        var guideCenter = value
        val showGuide = Runnable {
            if (pointer >= 0 && gesture.isActive && (guideOwner == null || guideOwner === button)) {
                guideWasShown = true
                showGuide(button, value, guideCenter, TouchKeyboardController.Flick.TAP)
            }
        }
        fun clear() {
            button.removeCallbacks(showGuide)
            if (guideOwner === button || (guideOwner == null && guideWasShown)) {
                guide?.dismiss(); guide = null; guideOwner = null; visibleFlickGuide = null
                activeFlickGuide = null
            }
            gesture.cancel(); pointer = -1; capturedShift = null; guideWasShown = false; button.isPressed = false
        }
        cancelGestures += ::clear
        refreshGuides += {
            if (pointer >= 0) {
                guideCenter = controller.previewText(value, TouchKeyboardController.Flick.TAP, capturedShift ?: false)
                if (guideWasShown && guideOwner === button) {
                    showGuide(button, value, guideCenter, activeFlickGuide)
                }
            }
        }
        button.setOnTouchListener { _, event ->
            if (currentGeneration != generation) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointer = event.getPointerId(0)
                    capturedShift = controller.captureShift()
                    guideCenter = controller.previewText(value, TouchKeyboardController.Flick.TAP, capturedShift ?: false)
                    gesture.start(event.x, event.y)
                    button.isPressed = true
                    button.postDelayed(showGuide, GUIDE_DELAY_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(pointer)
                    if (index < 0) clear()
                    else {
                        for (sample in 0 until event.historySize)
                            gesture.direction(event.getHistoricalX(index, sample), event.getHistoricalY(index, sample))
                        val direction = gesture.direction(event.getX(index), event.getY(index))
                        if (direction != TouchKeyboardController.Flick.TAP || guideWasShown) {
                            button.removeCallbacks(showGuide)
                            guideWasShown = true
                            val assigned = when (direction) {
                                TouchKeyboardController.Flick.UP -> direction.takeIf { controller.up(value) != null }
                                TouchKeyboardController.Flick.DOWN -> direction.takeIf { controller.down(value) != null }
                                else -> direction
                            }
                            showGuide(button, value, guideCenter, assigned)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val index = event.findPointerIndex(pointer)
                    if (index >= 0) {
                        for (sample in 0 until event.historySize)
                            gesture.direction(event.getHistoricalX(index, sample), event.getHistoricalY(index, sample))
                        val direction = gesture.finish(event.getX(index), event.getY(index))
                        when {
                            direction == TouchKeyboardController.Flick.TAP -> button.performClick()
                            direction == TouchKeyboardController.Flick.UP && controller.up(value) != null ||
                                direction == TouchKeyboardController.Flick.DOWN && controller.down(value) != null -> {
                                onCommand(controller.text(value, checkNotNull(direction), capturedShift ?: false))
                                refreshShift()
                            }
                        }
                    }
                    clear()
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> clear()
            }
            true
        }
        return button
    }

    private fun showGuide(anchor: View, value: String, center: String, selection: TouchKeyboardController.Flick?) {
        activeFlickGuide = selection
        if (!anchor.isAttachedToWindow) return
        val up = controller.up(value)
        val down = controller.down(value)
        visibleFlickGuide?.takeIf { guideOwner === anchor && it.matches(center, up, down) }?.let {
            it.update(selection)
            return
        }
        guide?.dismiss()
        guideOwner = anchor
        val content = FlickGuide(context, center, up, down, selection)
        val width = dp(104)
        val height = dp(132)
        guide = PopupWindow(content, width, height, false).apply {
            animationStyle = 0
            if (Build.VERSION.SDK_INT >= 23) { enterTransition = null; exitTransition = null }
            isTouchable = false
            isFocusable = false
            isOutsideTouchable = false
            // 古いAndroidでもIME窓の上端でガイドが切れないようにします。
            isClippingEnabled = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(6).toFloat()
        }
        visibleFlickGuide = content
        // IMEの子窓へ画面座標を渡すと原点が二重に加算されるため、キーからの相対位置を使います。
        guide?.showAsDropDown(anchor, (anchor.width - width) / 2,
            -anchor.height - height - dp(4), Gravity.START)
    }

    private fun showModeMenu(anchor: View) {
        if (!modeEnabled || !anchor.isAttachedToWindow) return
        modeMenu?.dismiss()
        val choices = LinearLayout(context).apply { orientation = VERTICAL }
        val popup = PopupWindow(choices, dp(180), LayoutParams.WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(KeyboardColors.resolve(context).panelBackground))
            isOutsideTouchable = true
            elevation = dp(4).toFloat()
        }
        listOf(InputMode.HIRAGANA to "ひらがな", InputMode.KATAKANA to "カタカナ",
            InputMode.HALFWIDTH to "半角カナ", InputMode.DIRECT to "直接英数",
            InputMode.FULLWIDTH to "全角英数").forEach { (mode, label) ->
            choices.addView(commandKey(label) {
                popup.dismiss(); modeMenu = null
                if (modeEnabled) send(BasicSkkAction.SetInputMode(mode))
            }, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        }
        modeMenu = popup
        popup.showAsDropDown(anchor, 0, -anchor.height - dp(220))
    }

    private fun send(action: BasicSkkAction) = onCommand(TouchKeyboardCommand.Skk(action))
    private fun newRow() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isMotionEventSplittingEnabled = true
    }
    private fun backspaceKey(): Button = commandKey("⌫") { send(BasicSkkAction.Backspace) }.also { button ->
        cancelGestures += attachBackspaceRepeat(button, isCurrent = { button.isAttachedToWindow }) {
            send(BasicSkkAction.Backspace)
        }
    }

    private fun commandKey(label: String, alternative: Boolean = false, action: () -> Unit): Button {
        val currentGeneration = generation
        return (if (alternative) AlternativeFunctionButton(context) else Button(context)).apply {
            configureKey(label)
            setOnClickListener { if (currentGeneration == generation) action() }
        }
    }
    private fun Button.configureKey(label: String, character: Boolean = false) {
        val colors = KeyboardColors.resolve(context)
        setTextColor(colors.text)
        fun face(color: Int) = GradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            setColor(color)
            setStroke(dp(1).coerceAtLeast(1), colors.border)
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), face(colors.pressed))
            addState(intArrayOf(), face(if (character) colors.characterBackground else colors.functionBackground))
        }
        text = label
        textSize = if (resources.configuration.fontScale >= 1.5f) 13f else 16f
        gravity = Gravity.CENTER
        minWidth = 0; minHeight = 0
        setPadding(dp(1), 0, dp(1), 0)
        isAllCaps = false
        typeface = Typeface.DEFAULT
        contentDescription = label
    }
    private fun keyParams(weight: Float = 1f) = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
        setMargins(dp(1), dp(1), dp(1), dp(1))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun modeLabel(mode: InputMode?) = when (mode) {
        InputMode.DIRECT -> "A"
        InputMode.FULLWIDTH -> "Ａ"
        InputMode.KATAKANA -> "ア"
        InputMode.HALFWIDTH -> "ｱ"
        else -> "あ"
    }
    /** キー上の補助表示は本文を変えず、上下の候補だけを右隅に描きます。 */
    // IMEはAppCompatのActivityではないため、OSのButtonテーマと描画を維持します。
    @android.annotation.SuppressLint("AppCompatCustomView")
    internal class FlickHintButton(context: Context) : Button(context) {
        private var upHint: String? = null
        private var downHint: String? = null
        internal val displayedUpHint: String? get() = upHint
        internal val displayedDownHint: String? get() = downHint
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = KeyboardColors.resolve(context).text
            textAlign = Paint.Align.LEFT
        }

        internal data class GlyphLayout(
            val text: String,
            val anchorX: Float,
            val baseline: Float,
            val textSize: Float,
            val bounds: RectF,
        )

        fun configureFlickHints(value: String, up: String?, down: String?) {
            upHint = up?.takeUnless { value.length == 1 && value[0] in 'a'..'z' && it == value.uppercase() }
            downHint = down
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val center = centerLabelLayout() ?: return
            val originalSize = paint.textSize
            val originalAlign = paint.textAlign
            paint.color = currentTextColor
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = center.textSize
            canvas.drawText(center.text, center.anchorX, center.baseline, paint)
            paint.textSize = originalSize
            paint.textAlign = originalAlign

            hintLayouts(center.bounds).forEach { hint ->
                hintPaint.textSize = hint.textSize
                canvas.drawText(hint.text, hint.anchorX, hint.baseline, hintPaint)
            }
        }

        /** 背景の見える面で、実際に描く中心文字と重ならない右上下に配置します。 */
        internal fun hintLayouts(): List<GlyphLayout> =
            centerLabelLayout()?.let { hintLayouts(it.bounds) }.orEmpty()

        private fun hintLayouts(center: RectF): List<GlyphLayout> {
            val face = visibleFaceBounds()
            if (face.width() <= 0f || face.height() <= 0f) return emptyList()
            val inset = hintInset(face)
            fun layout(text: String, upper: Boolean): GlyphLayout {
                val maximum = minOf(resources.displayMetrics.scaledDensity * 10f, dp(10))
                var size = maximum
                while (size >= MIN_TEXT_SIZE_PX) {
                    val candidate = hintLayout(text, face, inset, upper, size)
                    if (face.contains(candidate.bounds) && !RectF.intersects(candidate.bounds, center)) {
                        return candidate
                    }
                    size -= .5f
                }
                // 正の寸法を持つキーでは候補を消さず、描画可能な右隅へ最小サイズで残します。
                return hintLayout(text, face, 0f, upper, MIN_TEXT_SIZE_PX)
            }
            return buildList {
                upHint?.let { add(layout(it, true)) }
                downHint?.let { add(layout(it, false)) }
            }
        }

        internal fun centerLabelLayout(): GlyphLayout? {
            val face = visibleFaceBounds()
            val label = text.toString()
            if (face.width() <= 0f || face.height() <= 0f || label.isEmpty()) return null
            val inset = hintInset(face)
            val content = RectF(face).apply { inset(dp(1), dp(1)) }
            val hintSize = minOf(resources.displayMetrics.scaledDensity * 10f, dp(10))
            val hints = buildList {
                upHint?.let { add(hintLayout(it, face, inset, true, hintSize).bounds) }
                downHint?.let { add(hintLayout(it, face, inset, false, hintSize).bounds) }
            }
            var size = paint.textSize
            while (size >= MIN_TEXT_SIZE_PX) {
                val glyph = glyphBounds(paint, label, size)
                // 句読点を中央の点にせず、英字と同じベースラインに置きます。
                val reference = glyphBounds(paint, "Hgj", size)
                val baseline = face.centerY() - (reference.top + reference.bottom) / 2f - dp(2)
                val centeredX = face.centerX() - (glyph.left + glyph.right) / 2f
                val minimumX = content.left - glyph.left
                // 補助文字と縦方向に重なる場合だけ、主文字を少し左へ寄せます。
                var anchorX = centeredX
                val bounds = RectF(anchorX + glyph.left, baseline + glyph.top,
                    anchorX + glyph.right, baseline + glyph.bottom)
                hints.filter { it.top < bounds.bottom && it.bottom > bounds.top }.forEach {
                    anchorX = minOf(anchorX, it.left - dp(1) - glyph.right)
                }
                anchorX = maxOf(minimumX, anchorX)
                bounds.offset(anchorX - centeredX, 0f)
                if (content.contains(bounds) && hints.none { RectF.intersects(it, bounds) }) {
                    return GlyphLayout(label, anchorX, baseline, size, bounds)
                }
                size -= .5f
            }
            return null
        }

        private fun hintInset(face: RectF) = minOf(dp(4), face.width() / 5f, face.height() / 5f)

        internal fun visibleFaceBounds(): RectF {
            if (width <= 0 || height <= 0) return RectF()
            val drawablePadding = Rect()
            background?.getPadding(drawablePadding)
            val maximumInset = minOf(dp(4), minOf(width, height) / 4f)
            fun limited(value: Int) = minOf(value.toFloat(), maximumInset)
            return RectF(
                maxOf(paddingLeft.toFloat(), limited(drawablePadding.left)),
                maxOf(paddingTop.toFloat(), limited(drawablePadding.top)),
                width - maxOf(paddingRight.toFloat(), limited(drawablePadding.right)),
                height - maxOf(paddingBottom.toFloat(), limited(drawablePadding.bottom)),
            )
        }

        private fun hintLayout(value: String, face: RectF, inset: Float, upper: Boolean,
            size: Float): GlyphLayout {
            val glyph = glyphBounds(hintPaint, value, size)
            val anchorX = face.right - inset - glyph.right
            val baseline = if (upper) face.top + inset - glyph.top else face.bottom - inset - glyph.bottom
            return GlyphLayout(value, anchorX, baseline, size,
                RectF(anchorX + glyph.left, baseline + glyph.top,
                    anchorX + glyph.right, baseline + glyph.bottom))
        }

        private fun glyphBounds(target: Paint, value: String, size: Float) = Rect().also {
            val originalSize = target.textSize
            target.textSize = size
            target.getTextBounds(value, 0, value.length, it)
            target.textSize = originalSize
        }

        private fun dp(value: Int) = value * resources.displayMetrics.density

        private companion object { const val MIN_TEXT_SIZE_PX = 1f }
    }

    /** 上下と中心を独立した面として示し、選択中の面だけを強調します。 */
    internal class FlickGuide(
        context: Context,
        value: String,
        up: String?,
        down: String?,
        selection: TouchKeyboardController.Flick?,
    ) : LinearLayout(context) {
        private val outputs = listOf(up, value, down)
        private val marks = listOf("↑", "・", "↓")
        private val options = mutableListOf<TextView>()

        init {
            orientation = VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            outputs.zip(marks).forEach { (output, mark) ->
                option(mark, output).also { options += it; addView(it, guideParams()) }
            }
            update(selection)
        }

        fun matches(value: String, up: String?, down: String?) = outputs == listOf(up, value, down)

        fun update(selection: TouchKeyboardController.Flick?) {
            val selectedIndex = when (selection) {
                TouchKeyboardController.Flick.UP -> 0
                TouchKeyboardController.Flick.TAP -> 1
                TouchKeyboardController.Flick.DOWN -> 2
                null -> -1
            }
            val cancelled = selectedIndex < 0 || outputs.getOrNull(selectedIndex) == null
            options.forEachIndexed { index, option ->
                option.text = if (cancelled && index == 1) "×" else outputs[index]?.let { "${marks[index]}  $it" }.orEmpty()
                style(option, outputs[index], index == selectedIndex)
            }
        }

        private fun option(mark: String, output: String?): TextView = TextView(context).apply {
            text = if (output == null) "" else "$mark  $output"
            textSize = 16f
            gravity = Gravity.CENTER
        }

        private fun style(option: TextView, output: String?, selected: Boolean) = option.apply {
            setTextColor(KeyboardColors.resolve(context).text)
            alpha = if (output == null) .25f else 1f
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (selected && output != null) KeyboardColors.resolve(context).selectedBackground else KeyboardColors.resolve(context).panelBackground)
            }
        }

        private fun guideParams() = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(0, dp(1), 0, dp(1))
        }
        private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val GUIDE_DELAY_MS = 200L
        const val WIDE_QWERTY_MIN_WIDTH_DP = 600
        const val MIN_WIDE_KEY_WIDTH_DP = 38
        const val HANDLE_WIDTH_DP = 24
        const val SPLIT_HALF_UNITS = 6.5f
        const val JOINED_UNITS = 11.5f
    }
}
