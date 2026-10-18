package se.haya.skk

import android.content.Context
import android.os.Bundle
import android.os.Vibrator
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceCategory
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import se.haya.skk.settings.ScreenKeyboardLayout
import se.haya.skk.settings.TenKeyOperation
import se.haya.skk.settings.TouchKeyboardSettings
import se.haya.skk.settings.TouchKeyboardSettingsStore

class TouchKeyboardSettingsActivity : SettingsPageActivity() {
    private lateinit var page: TouchKeyboardSettingsFragment
    private var saved = TouchKeyboardSettings()
    private var draft = TouchKeyboardSettings()
    private var geometryEdited = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saved = TouchKeyboardSettingsStore.read(this)
        saved = savedInstanceState?.let { fromBundle(it, "saved_") } ?: saved
        draft = savedInstanceState?.let { fromBundle(it, "draft_") } ?: saved
        geometryEdited = savedInstanceState?.getBoolean(GEOMETRY_EDITED) ?: false
        installSettingsPage("画面キーボード", TouchKeyboardSettingsFragment())
        page = supportFragmentManager.findFragmentById(R.id.settings_content) as TouchKeyboardSettingsFragment
        page.onDraftChanged = {
            if (it.hasDifferentGeometry(draft)) geometryEdited = true
            draft = it
            render()
        }
        setPageActions(::saveDraft, ::resetDraftToDefaults)
        page.refresh(draft, hasVibrator())
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
        page.refresh(draft, hasVibrator())
        val dirty = draft != saved || geometryEdited
        renderPageState(dirty, false, if (dirty) "変更は未保存です。" else null)
    }

    private fun saveDraft() {
        val current = TouchKeyboardSettingsStore.read(this)
        val valueToSave = if (geometryEdited) draft else draft.copy(
            tenKeyHeightDp = current.tenKeyHeightDp,
            tenKeyWidthPercent = current.tenKeyWidthPercent,
            tenKeyPositionPercent = current.tenKeyPositionPercent,
            tenKeyBottomPaddingDp = current.tenKeyBottomPaddingDp,
        )
        if (TouchKeyboardSettingsStore.save(this, valueToSave)) {
            saved = valueToSave
            draft = valueToSave
            geometryEdited = false
            renderPageState(false, false, "保存しました。次の入力欄から反映します。")
            finish()
        } else {
            renderPageState(draft != saved || geometryEdited, false, "保存できませんでした。変更内容は残しています。")
        }
    }

    override fun resetDraftToDefaults() {
        draft = TouchKeyboardSettings()
        geometryEdited = true
        render()
    }

    override fun discardAndClose() = finish()

    private fun hasVibrator(): Boolean =
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.hasVibrator() == true

    companion object {
        private const val LAYOUT = "touch_layout"
        private const val OPERATION = "touch_operation"
        private const val HOLD = "touch_hold"
        private const val VIBRATE = "touch_vibrate"
        private const val HEIGHT = "touch_height"
        private const val WIDTH = "touch_width"
        private const val POSITION = "touch_position"
        private const val BOTTOM = "touch_bottom"
        private const val GEOMETRY_EDITED = "touch_geometry_edited"

        private fun TouchKeyboardSettings.hasDifferentGeometry(other: TouchKeyboardSettings) =
            tenKeyHeightDp != other.tenKeyHeightDp || tenKeyWidthPercent != other.tenKeyWidthPercent ||
                tenKeyPositionPercent != other.tenKeyPositionPercent || tenKeyBottomPaddingDp != other.tenKeyBottomPaddingDp

        private fun TouchKeyboardSettings.toBundle(bundle: Bundle, prefix: String) {
            bundle.putString(prefix + LAYOUT, layout.name); bundle.putString(prefix + OPERATION, operation.name)
            bundle.putInt(prefix + HOLD, holdMillis); bundle.putBoolean(prefix + VIBRATE, vibrateOnBoundary)
            bundle.putInt(prefix + HEIGHT, tenKeyHeightDp); bundle.putInt(prefix + WIDTH, tenKeyWidthPercent)
            bundle.putInt(prefix + POSITION, tenKeyPositionPercent)
            bundle.putInt(prefix + BOTTOM, tenKeyBottomPaddingDp)
        }
        private fun fromBundle(bundle: Bundle, prefix: String) = TouchKeyboardSettings(
            layout = enumValueOf<ScreenKeyboardLayout>(bundle.getString(prefix + LAYOUT, ScreenKeyboardLayout.QWERTY.name)),
            operation = enumValueOf<TenKeyOperation>(bundle.getString(prefix + OPERATION, TenKeyOperation.HOLD_AND_RETURN.name)),
            holdMillis = bundle.getInt(prefix + HOLD, 350), vibrateOnBoundary = bundle.getBoolean(prefix + VIBRATE, true),
            tenKeyHeightDp = bundle.getInt(prefix + HEIGHT, 256), tenKeyWidthPercent = bundle.getInt(prefix + WIDTH, 100),
            tenKeyPositionPercent = bundle.getInt(prefix + POSITION, 50),
            tenKeyBottomPaddingDp = bundle.getInt(prefix + BOTTOM, 0),
        )
    }
}

class TouchKeyboardSettingsFragment : PreferenceFragmentCompat() {
    var onDraftChanged: ((TouchKeyboardSettings) -> Unit)? = null
    private var draft = TouchKeyboardSettings()
    private var refreshing = false
    private lateinit var layout: ListPreference
    private lateinit var operation: ListPreference
    private lateinit var hold: ListPreference
    private lateinit var vibrate: SwitchPreferenceCompat
    private lateinit var height: SeekBarPreference
    private lateinit var width: SeekBarPreference
    private lateinit var position: SeekBarPreference
    private lateinit var bottom: SeekBarPreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(context)
        val category = PreferenceCategory(context).apply { title = "入力方法"; isIconSpaceReserved = false }
        preferenceScreen.addPreference(category)
        category.addPreference(Preference(context).apply {
            title = "反映時期"
            summary = "保存後、次の入力欄から反映します。"
            isIconSpaceReserved = false
            isSelectable = false
        })
        category.addPreference(Preference(context).apply {
            title = "10キーの形状"
            summary = "入力中に「IME」を長押しし、「サイズ・位置を調整」からも変更できます。"
            isIconSpaceReserved = false; isSelectable = false
        })
        layout = list(context, "layout", "配列", arrayOf("QWERTY", "TEN_KEY"), arrayOf("QWERTY", "10キー")) { value ->
            draft.copy(layout = ScreenKeyboardLayout.valueOf(value))
        }
        operation = list(context, "operation", "10キーの操作", arrayOf("FLICK", "HOLD", "HOLD_AND_RETURN"),
            arrayOf("通常タップ・フリック", "ホールドで境界", "ホールド＋往復")) { value ->
            draft.copy(operation = TenKeyOperation.valueOf(value))
        }
        hold = list(context, "hold", "ホールド判定", arrayOf("250", "350", "500"),
            arrayOf("短め（250 ms）", "標準（350 ms）", "長め（500 ms）")) { value ->
            draft.copy(holdMillis = value.toInt())
        }
        vibrate = SwitchPreferenceCompat(context).apply {
            key = "vibrate"; title = "ホールドで境界を指定したときに振動"; isIconSpaceReserved = false
            isPersistent = false
            setOnPreferenceChangeListener { _, value -> update(draft.copy(vibrateOnBoundary = value as Boolean)); true }
        }
        height = seek(context, "height", "高さ", 160, 400, 256, "dp") { draft.copy(tenKeyHeightDp = it) }
        width = seek(context, "width", "幅（画面に対する割合）", 60, 100, 100, "%") {
            draft.copy(tenKeyWidthPercent = it)
        }
        position = seek(context, "position", "左右位置", 0, 100, 50, "%") {
            draft.copy(tenKeyPositionPercent = it)
        }
        bottom = seek(context, "bottom", "下の余白", 0, 160, 0, "dp") {
            draft.copy(tenKeyBottomPaddingDp = it)
        }
        listOf(layout, operation, hold, vibrate, height, width, position, bottom).forEach(category::addPreference)
        vibrate.summary = "振動子のない端末では利用できません。"
    }

    fun refresh(value: TouchKeyboardSettings, hasVibrator: Boolean) {
        if (!::layout.isInitialized) return
        draft = value
        refreshing = true
        layout.value = value.layout.name; operation.value = value.operation.name
        hold.value = value.holdMillis.toString(); vibrate.isChecked = value.vibrateOnBoundary
        height.value = value.tenKeyHeightDp; width.value = value.tenKeyWidthPercent
        position.value = value.tenKeyPositionPercent
        bottom.value = value.tenKeyBottomPaddingDp
        bottom.summary = "${value.tenKeyBottomPaddingDp} dp（OSの操作領域に追加）"
        height.summary = "${value.tenKeyHeightDp} dp"
        width.summary = "${value.tenKeyWidthPercent} %"
        position.summary = when (value.tenKeyPositionPercent) {
            0 -> "左端"
            50 -> "中央"
            100 -> "右端"
            else -> "中央から${if (value.tenKeyPositionPercent < 50) "左" else "右"}へ ${kotlin.math.abs(value.tenKeyPositionPercent - 50)} %"
        }
        vibrate.isEnabled = hasVibrator
        vibrate.summary = if (hasVibrator) "ホールドで境界指定が有効になったときに振動します。"
            else "この端末には振動子がありません。振動しません。"
        val tenKey = value.layout == ScreenKeyboardLayout.TEN_KEY
        val usesHold = value.operation != TenKeyOperation.FLICK
        layout.isVisible = true
        operation.isVisible = tenKey
        hold.isVisible = tenKey
        hold.isEnabled = usesHold
        vibrate.isVisible = tenKey
        height.isVisible = tenKey; width.isVisible = tenKey; position.isVisible = tenKey; bottom.isVisible = tenKey
        vibrate.isEnabled = hasVibrator && usesHold
        refreshing = false
    }

    private fun list(context: Context, keyValue: String, titleValue: String, values: Array<String>, labels: Array<String>,
        transform: (String) -> TouchKeyboardSettings): ListPreference = ListPreference(context).apply {
        key = keyValue; title = titleValue; entries = labels; entryValues = values; isIconSpaceReserved = false
        isPersistent = false
        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        setOnPreferenceChangeListener { _, raw -> if (!refreshing) update(transform(raw as String)); true }
    }

    private fun seek(context: Context, keyValue: String, titleValue: String, minimum: Int, maximum: Int,
        default: Int, unit: String, transform: (Int) -> TouchKeyboardSettings): SeekBarPreference =
        SeekBarPreference(context).apply {
            key = keyValue; title = titleValue; min = minimum; max = maximum; value = default
            showSeekBarValue = true; isIconSpaceReserved = false; isPersistent = false
            summary = "$default $unit"
            setOnPreferenceChangeListener { _, raw ->
                if (!refreshing) update(transform(raw as Int))
                true
            }
        }

    private fun update(value: TouchKeyboardSettings) { draft = value; onDraftChanged?.invoke(value) }
}
