package se.haya.skk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Vibrator
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.ListPreference
import se.haya.skk.settings.TenKeyOperation
import se.haya.skk.settings.TouchKeyboardSettings
import se.haya.skk.settings.TouchKeyboardSettingsStore

class TouchKeyboardSettingsActivity : SettingsPageActivity() {
    private lateinit var page: TouchKeyboardSettingsFragment
    private val editor: String get() = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_HUB
    private var saved = TouchKeyboardSettings()
    private var draft = TouchKeyboardSettings()
    private var geometryEdited = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saved = TouchKeyboardSettingsStore.read(this)
        saved = savedInstanceState?.let { fromBundle(it, "saved_") } ?: saved
        draft = savedInstanceState?.let { fromBundle(it, "draft_") } ?: saved
        geometryEdited = savedInstanceState?.getBoolean(GEOMETRY_EDITED) ?: false
        if (editor == PAGE_HUB) {
            installSettingsPage("画面キーボード", TouchKeyboardSettingsHubFragment())
            renderPageState(false, false)
            return
        }
        page = TouchKeyboardSettingsFragment().apply { arguments = Bundle().apply { putString(EXTRA_PAGE, editor) } }
        installSettingsPage(if (editor == PAGE_OPERATION) "10キーの操作" else "10キーの大きさと位置", page)
        page = supportFragmentManager.findFragmentById(R.id.settings_content) as TouchKeyboardSettingsFragment
        page.onDraftChanged = { value ->
            if (editor == PAGE_GEOMETRY && value.hasDifferentGeometry(draft)) geometryEdited = true
            draft = value
            render()
        }
        setPageActions(::saveDraft, ::resetDraftToDefaults)
        page.refresh(draft, hasVibrator(), editor)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saved.toBundle(outState, "saved_")
        draft.toBundle(outState, "draft_")
        outState.putBoolean(GEOMETRY_EDITED, geometryEdited)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        if (!::page.isInitialized) return
        page.refresh(draft, hasVibrator(), editor)
        val dirty = if (editor == PAGE_GEOMETRY) geometryEdited else
            draft.operation != saved.operation || draft.holdMillis != saved.holdMillis || draft.vibrateOnBoundary != saved.vibrateOnBoundary
        renderPageState(dirty, false, if (dirty) "変更は未保存です。" else null)
    }

    private fun saveDraft() {
        val current = TouchKeyboardSettingsStore.read(this)
        val valueToSave = when {
            editor == PAGE_GEOMETRY && geometryEdited -> current.copy(
                tenKeyHeightDp = draft.tenKeyHeightDp,
                tenKeyWidthPercent = draft.tenKeyWidthPercent,
                tenKeyPositionPercent = draft.tenKeyPositionPercent,
                tenKeyBottomPaddingDp = draft.tenKeyBottomPaddingDp,
            )
            editor == PAGE_GEOMETRY -> current
            else -> current.copy(
                operation = draft.operation,
                holdMillis = draft.holdMillis,
                vibrateOnBoundary = draft.vibrateOnBoundary,
            )
        }
        if (TouchKeyboardSettingsStore.save(this, valueToSave)) {
            saved = valueToSave
            draft = valueToSave
            geometryEdited = false
            renderPageState(false, false, "保存しました。次の入力欄から反映します。")
            finish()
        } else {
            render()
            renderPageState(true, false, "保存できませんでした。変更内容は残しています。")
        }
    }

    override fun resetDraftToDefaults() {
        draft = if (editor == PAGE_GEOMETRY) draft.copy(
            tenKeyHeightDp = DEFAULTS.tenKeyHeightDp, tenKeyWidthPercent = DEFAULTS.tenKeyWidthPercent,
            tenKeyPositionPercent = DEFAULTS.tenKeyPositionPercent, tenKeyBottomPaddingDp = DEFAULTS.tenKeyBottomPaddingDp,
        ) else draft.copy(operation = DEFAULTS.operation, holdMillis = DEFAULTS.holdMillis,
            vibrateOnBoundary = DEFAULTS.vibrateOnBoundary)
        if (editor == PAGE_GEOMETRY) geometryEdited = true
        render()
    }

    override fun discardAndClose() = finish()

    private fun hasVibrator(): Boolean =
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.hasVibrator() == true

    companion object {
        const val EXTRA_PAGE = "touch_page"
        const val PAGE_HUB = "hub"
        const val PAGE_OPERATION = "operation"
        const val PAGE_GEOMETRY = "geometry"
        private const val OPERATION = "touch_operation"
        private const val HOLD = "touch_hold"
        private const val VIBRATE = "touch_vibrate"
        private const val HEIGHT = "touch_height"
        private const val WIDTH = "touch_width"
        private const val POSITION = "touch_position"
        private const val BOTTOM = "touch_bottom"
        private const val GEOMETRY_EDITED = "touch_geometry_edited"
        private val DEFAULTS = TouchKeyboardSettings()

        private fun TouchKeyboardSettings.hasDifferentGeometry(other: TouchKeyboardSettings) =
            tenKeyHeightDp != other.tenKeyHeightDp || tenKeyWidthPercent != other.tenKeyWidthPercent ||
                tenKeyPositionPercent != other.tenKeyPositionPercent || tenKeyBottomPaddingDp != other.tenKeyBottomPaddingDp

        private fun TouchKeyboardSettings.toBundle(bundle: Bundle, prefix: String) {
            bundle.putString(prefix + OPERATION, operation.name)
            bundle.putInt(prefix + HOLD, holdMillis); bundle.putBoolean(prefix + VIBRATE, vibrateOnBoundary)
            bundle.putInt(prefix + HEIGHT, tenKeyHeightDp); bundle.putInt(prefix + WIDTH, tenKeyWidthPercent)
            bundle.putInt(prefix + POSITION, tenKeyPositionPercent); bundle.putInt(prefix + BOTTOM, tenKeyBottomPaddingDp)
        }
        private fun fromBundle(bundle: Bundle, prefix: String) = TouchKeyboardSettings(
            operation = enumValueOf<TenKeyOperation>(bundle.getString(prefix + OPERATION, TenKeyOperation.HOLD_AND_RETURN.name)),
            holdMillis = bundle.getInt(prefix + HOLD, 350), vibrateOnBoundary = bundle.getBoolean(prefix + VIBRATE, true),
            tenKeyHeightDp = bundle.getInt(prefix + HEIGHT, 256), tenKeyWidthPercent = bundle.getInt(prefix + WIDTH, 100),
            tenKeyPositionPercent = bundle.getInt(prefix + POSITION, 50), tenKeyBottomPaddingDp = bundle.getInt(prefix + BOTTOM, 0),
        )
    }
}

class TouchKeyboardSettingsHubFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(context)
        listOf(
            TouchKeyboardSettingsActivity.PAGE_OPERATION to ("10キーの操作" to "タップ・フリック、ホールド、振動を設定します。"),
            TouchKeyboardSettingsActivity.PAGE_GEOMETRY to ("10キーの大きさと位置" to "高さ、幅、左右位置、下の余白を設定します。"),
        ).forEach { (target, text) ->
            preferenceScreen.addPreference(Preference(context).apply {
                title = text.first
                summary = text.second
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    startActivity(Intent(context, TouchKeyboardSettingsActivity::class.java)
                        .putExtra(TouchKeyboardSettingsActivity.EXTRA_PAGE, target))
                    true
                }
            })
        }
    }
}

class TouchKeyboardSettingsFragment : PreferenceFragmentCompat() {
    var onDraftChanged: ((TouchKeyboardSettings) -> Unit)? = null
    private var draft = TouchKeyboardSettings()
    private var refreshing = false
    private var editor = TouchKeyboardSettingsActivity.PAGE_OPERATION
    private lateinit var operation: ListPreference
    private lateinit var hold: SeekBarPreference
    private lateinit var vibrate: SwitchPreferenceCompat
    private lateinit var height: SeekBarPreference
    private lateinit var width: SeekBarPreference
    private lateinit var position: SeekBarPreference
    private lateinit var bottom: SeekBarPreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        editor = arguments?.getString(TouchKeyboardSettingsActivity.EXTRA_PAGE) ?: editor
        preferenceScreen = preferenceManager.createPreferenceScreen(context)
        val category = PreferenceCategory(context).apply { title = if (editor == TouchKeyboardSettingsActivity.PAGE_OPERATION) "操作" else "大きさと位置"; isIconSpaceReserved = false }
        preferenceScreen.addPreference(category)
        category.addPreference(Preference(context).apply {
            title = "反映時期"; summary = "保存後、次の入力欄から反映します。"
            isIconSpaceReserved = false; isSelectable = false
        })
        operation = ListPreference(context).apply {
            key = "operation"; title = "操作方式"
            entries = arrayOf("通常タップ・フリック", "ホールドで境界", "ホールド＋往復")
            entryValues = arrayOf("FLICK", "HOLD", "HOLD_AND_RETURN")
            isIconSpaceReserved = false; isPersistent = false
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, raw -> if (!refreshing) update(draft.copy(operation = TenKeyOperation.valueOf(raw as String))); true }
        }
        hold = SeekBarPreference(context).apply {
            key = "hold"; title = "ホールド判定"; min = 100; max = 1000; value = 350
            seekBarIncrement = 10; showSeekBarValue = true; isIconSpaceReserved = false; isPersistent = false
            setOnPreferenceChangeListener { preference, raw ->
                if (refreshing) return@setOnPreferenceChangeListener true
                val value = raw as Int
                val snapped = 100 + ((value - 100 + 5) / 10) * 10
                update(draft.copy(holdMillis = snapped))
                if (snapped != value) { (preference as SeekBarPreference).value = snapped; false } else true
            }
        }
        vibrate = SwitchPreferenceCompat(context).apply {
            key = "vibrate"; title = "ホールド境界の指定・取消時に振動"; isIconSpaceReserved = false; isPersistent = false
            setOnPreferenceChangeListener { _, value -> update(draft.copy(vibrateOnBoundary = value as Boolean)); true }
        }
        height = seek(context, "height", "高さ", 160, 400, 256, "dp") { draft.copy(tenKeyHeightDp = it) }
        width = seek(context, "width", "幅（画面に対する割合）", 60, 100, 100, "%") { draft.copy(tenKeyWidthPercent = it) }
        position = seek(context, "position", "左右位置", 0, 100, 50, "%") { draft.copy(tenKeyPositionPercent = it) }
        bottom = seek(context, "bottom", "下の余白", 0, 160, 0, "dp") { draft.copy(tenKeyBottomPaddingDp = it) }
        val items = if (editor == TouchKeyboardSettingsActivity.PAGE_OPERATION)
            listOf(operation, hold, vibrate) else listOf(height, width, position, bottom)
        items.forEach(category::addPreference)
    }

    fun refresh(value: TouchKeyboardSettings, hasVibrator: Boolean, target: String) {
        if (!::operation.isInitialized) return
        editor = target
        draft = value
        refreshing = true
        operation.value = value.operation.name
        hold.value = value.holdMillis; hold.summary = "${value.holdMillis} ms"
        vibrate.isChecked = value.vibrateOnBoundary
        height.value = value.tenKeyHeightDp; width.value = value.tenKeyWidthPercent
        position.value = value.tenKeyPositionPercent; bottom.value = value.tenKeyBottomPaddingDp
        bottom.summary = "${value.tenKeyBottomPaddingDp} dp（OSの操作領域に追加）"
        height.summary = "${value.tenKeyHeightDp} dp"; width.summary = "${value.tenKeyWidthPercent} %"
        position.summary = when (value.tenKeyPositionPercent) {
            0 -> "左端"; 50 -> "中央"; 100 -> "右端"
            else -> "中央から${if (value.tenKeyPositionPercent < 50) "左" else "右"}へ ${kotlin.math.abs(value.tenKeyPositionPercent - 50)} %"
        }
        val usesHold = value.operation != TenKeyOperation.FLICK
        hold.isEnabled = usesHold
        hold.summary = if (usesHold) "${value.holdMillis} ms" else "通常タップ・フリックでは使用しません。"
        vibrate.isEnabled = hasVibrator && usesHold
        vibrate.summary = when {
            !hasVibrator -> "この端末には振動子がありません。振動しません。"
            !usesHold -> "通常タップ・フリックでは使用しません。"
            else -> "境界指定時は一回、取消時は短く二回振動します。"
        }
        refreshing = false
    }

    private fun seek(context: Context, keyValue: String, titleValue: String, minimum: Int, maximum: Int,
        default: Int, unit: String, transform: (Int) -> TouchKeyboardSettings): SeekBarPreference =
        SeekBarPreference(context).apply {
            key = keyValue; title = titleValue; min = minimum; max = maximum; value = default
            showSeekBarValue = true; isIconSpaceReserved = false; isPersistent = false; summary = "$default $unit"
            setOnPreferenceChangeListener { _, raw -> if (!refreshing) update(transform(raw as Int)); true }
        }

    private fun update(value: TouchKeyboardSettings) { draft = value; onDraftChanged?.invoke(value) }
}
