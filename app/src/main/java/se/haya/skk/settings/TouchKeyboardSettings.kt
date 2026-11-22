package se.haya.skk.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

enum class ScreenKeyboardLayout { QWERTY, TEN_KEY }
enum class TenKeyOperation { FLICK, HOLD, HOLD_AND_RETURN }

data class TouchKeyboardSettings(
    val layout: ScreenKeyboardLayout = ScreenKeyboardLayout.QWERTY,
    val operation: TenKeyOperation = TenKeyOperation.HOLD_AND_RETURN,
    val holdMillis: Int = 350,
    val vibrateOnBoundary: Boolean = true,
    val tenKeyHeightDp: Int = 256,
    val tenKeyWidthPercent: Int = 100,
    val tenKeyPositionPercent: Int = 50,
    val tenKeyBottomPaddingDp: Int = 0,
    val qwertySplit: Boolean = false,
    val qwertyGapPercent: Int = DEFAULT_QWERTY_GAP_PERCENT,
    val qwertyHeightDp: Int = DEFAULT_QWERTY_HEIGHT_DP,
    val qwertyWidthPercent: Int = 100,
    val qwertyPositionPercent: Int = 50,
    val qwertyBottomPaddingDp: Int = 0,
) {
    init {
        require(holdMillis in HOLD_MILLIS && (holdMillis - HOLD_STEP_BASE) % HOLD_STEP == 0)
        require(tenKeyHeightDp in TEN_KEY_HEIGHT_DP)
        require(tenKeyWidthPercent in TEN_KEY_WIDTH_PERCENT)
        require(tenKeyPositionPercent in TEN_KEY_POSITION_PERCENT)
        require(tenKeyBottomPaddingDp in TEN_KEY_BOTTOM_PADDING_DP)
        require(qwertyGapPercent in QWERTY_GAP_PERCENT)
        require(qwertyHeightDp in QWERTY_HEIGHT_DP)
        require(qwertyWidthPercent in QWERTY_WIDTH_PERCENT)
        require(qwertyPositionPercent in QWERTY_POSITION_PERCENT)
        require(qwertyBottomPaddingDp in QWERTY_BOTTOM_PADDING_DP)
    }

    companion object {
        val HOLD_MILLIS = 100..1000
        const val HOLD_STEP_BASE = 100
        const val HOLD_STEP = 10
        val TEN_KEY_HEIGHT_DP = 100..400
        val TEN_KEY_WIDTH_PERCENT = 10..100
        val TEN_KEY_POSITION_PERCENT = 0..100
        val TEN_KEY_BOTTOM_PADDING_DP = 0..160
        const val DEFAULT_QWERTY_GAP_PERCENT = 28
        const val DEFAULT_QWERTY_HEIGHT_DP = 200
        val QWERTY_GAP_PERCENT = 0..48
        val QWERTY_HEIGHT_DP = 100..320
        val QWERTY_WIDTH_PERCENT = 60..100
        val QWERTY_POSITION_PERCENT = 0..100
        val QWERTY_BOTTOM_PADDING_DP = 0..160
    }
}

/** 画面キーボード設定を既存の設定用 SharedPreferences に保存します。 */
object TouchKeyboardSettingsStore {
    private const val PREFS = "settings"
    private const val LAYOUT = "touch_keyboard_layout"
    private const val OPERATION = "ten_key_operation"
    private const val HOLD_MILLIS = "ten_key_hold_millis"
    private const val VIBRATE = "ten_key_vibrate_on_boundary"
    private const val HEIGHT = "ten_key_height_dp"
    private const val WIDTH = "ten_key_width_percent"
    private const val POSITION = "ten_key_position_percent"
    private const val BOTTOM = "ten_key_bottom_padding_dp"
    private const val QWERTY_SPLIT = "qwerty_split"
    private const val QWERTY_GAP = "qwerty_gap_percent"
    private const val QWERTY_HEIGHT = "qwerty_height_dp"
    private const val QWERTY_WIDTH = "qwerty_width_percent"
    private const val QWERTY_POSITION = "qwerty_position_percent"
    private const val QWERTY_BOTTOM = "qwerty_bottom_padding_dp"

    fun read(
        context: Context,
        orientation: Int = context.resources.configuration.orientation,
    ): TouchKeyboardSettings = readFrom(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        orientation,
    )

    fun save(
        context: Context,
        settings: TouchKeyboardSettings,
        orientation: Int = context.resources.configuration.orientation,
    ): Boolean = saveTo(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        settings, orientation,
    )

    /** 入力中の配列切替は、操作方法や形状などの設定を上書きしません。 */
    fun saveLayout(context: Context, layout: ScreenKeyboardLayout): Boolean = saveLayoutTo(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE), layout,
    )

    internal fun saveLayoutTo(preferences: SharedPreferences, layout: ScreenKeyboardLayout,
        commit: (SharedPreferences.Editor) -> Boolean = { it.commit() }): Boolean {
        return commitValues(preferences, mapOf(LAYOUT to layout.name), commit)
    }

    /** 10キーの操作設定を画面の向きに関係なく保存します。 */
    fun saveOperation(context: Context, settings: TouchKeyboardSettings): Boolean {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return commitValues(preferences, settings.operationValues())
    }

    fun saveQwertySplit(
        context: Context,
        split: Boolean,
        orientation: Int = context.resources.configuration.orientation,
    ): Boolean {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return commitValues(preferences, mapOf(geometryKey(QWERTY_SPLIT, orientation) to split))
    }

    fun saveQwertyGeometry(
        context: Context,
        gapPercent: Int? = null,
        heightDp: Int,
        widthPercent: Int? = null,
        positionPercent: Int? = null,
        bottomPaddingDp: Int? = null,
        orientation: Int = context.resources.configuration.orientation,
    ): Boolean {
        if ((gapPercent != null && gapPercent !in TouchKeyboardSettings.QWERTY_GAP_PERCENT) ||
            heightDp !in TouchKeyboardSettings.QWERTY_HEIGHT_DP ||
            (widthPercent != null && widthPercent !in TouchKeyboardSettings.QWERTY_WIDTH_PERCENT) ||
            (positionPercent != null && positionPercent !in TouchKeyboardSettings.QWERTY_POSITION_PERCENT) ||
            (bottomPaddingDp != null && bottomPaddingDp !in TouchKeyboardSettings.QWERTY_BOTTOM_PADDING_DP)) return false
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val values = mutableMapOf(QWERTY_HEIGHT to heightDp)
        gapPercent?.let { values[QWERTY_GAP] = it }
        widthPercent?.let { values[QWERTY_WIDTH] = it }
        positionPercent?.let { values[QWERTY_POSITION] = it }
        bottomPaddingDp?.let { values[QWERTY_BOTTOM] = it }
        return commitValues(preferences, values.forOrientation(orientation))
    }

    /** 指定した画面の向きの10キー形状を保存します。 */
    fun saveGeometry(
        context: Context,
        heightDp: Int,
        widthPercent: Int,
        positionPercent: Int,
        bottomPaddingDp: Int? = null,
        orientation: Int = context.resources.configuration.orientation,
    ): Boolean {
        if (heightDp !in TouchKeyboardSettings.TEN_KEY_HEIGHT_DP ||
            widthPercent !in TouchKeyboardSettings.TEN_KEY_WIDTH_PERCENT ||
            positionPercent !in TouchKeyboardSettings.TEN_KEY_POSITION_PERCENT ||
            (bottomPaddingDp != null && bottomPaddingDp !in TouchKeyboardSettings.TEN_KEY_BOTTOM_PADDING_DP)) return false
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val values = mutableMapOf(HEIGHT to heightDp, WIDTH to widthPercent, POSITION to positionPercent)
        bottomPaddingDp?.let { values[BOTTOM] = it }
        return commitValues(preferences, values.forOrientation(orientation))
    }

    internal fun readFrom(
        preferences: SharedPreferences,
        orientation: Int = Configuration.ORIENTATION_PORTRAIT,
    ): TouchKeyboardSettings {
        val stored = preferences.all
        // 向き別の値を保存するまでは、旧設定を両方の向きで使います。
        fun geometryValue(key: String) = stored.getOrElse(geometryKey(key, orientation)) { stored[key] }
        fun geometryInt(key: String, default: Int, range: IntRange): Int =
            (geometryValue(key) as? Int)?.takeIf { it in range } ?: default

        return TouchKeyboardSettings(
            layout = enumOrDefault(stored[LAYOUT] as? String, ScreenKeyboardLayout.QWERTY),
            operation = enumOrDefault(stored[OPERATION] as? String, TenKeyOperation.HOLD_AND_RETURN),
            holdMillis = (stored[HOLD_MILLIS] as? Int)?.takeIf {
                it in TouchKeyboardSettings.HOLD_MILLIS &&
                    (it - TouchKeyboardSettings.HOLD_STEP_BASE) % TouchKeyboardSettings.HOLD_STEP == 0
            } ?: 350,
            vibrateOnBoundary = stored[VIBRATE] as? Boolean ?: true,
            tenKeyHeightDp = geometryInt(HEIGHT, 256, TouchKeyboardSettings.TEN_KEY_HEIGHT_DP),
            tenKeyWidthPercent = geometryInt(WIDTH, 100, TouchKeyboardSettings.TEN_KEY_WIDTH_PERCENT),
            tenKeyPositionPercent = geometryInt(POSITION, 50, TouchKeyboardSettings.TEN_KEY_POSITION_PERCENT),
            tenKeyBottomPaddingDp = geometryInt(BOTTOM, 0, TouchKeyboardSettings.TEN_KEY_BOTTOM_PADDING_DP),
            qwertySplit = geometryValue(QWERTY_SPLIT) as? Boolean ?: false,
            qwertyGapPercent = geometryInt(QWERTY_GAP, TouchKeyboardSettings.DEFAULT_QWERTY_GAP_PERCENT,
                TouchKeyboardSettings.QWERTY_GAP_PERCENT),
            qwertyHeightDp = geometryInt(QWERTY_HEIGHT, TouchKeyboardSettings.DEFAULT_QWERTY_HEIGHT_DP,
                TouchKeyboardSettings.QWERTY_HEIGHT_DP),
            qwertyWidthPercent = geometryInt(QWERTY_WIDTH, 100, TouchKeyboardSettings.QWERTY_WIDTH_PERCENT),
            qwertyPositionPercent = geometryInt(QWERTY_POSITION, 50, TouchKeyboardSettings.QWERTY_POSITION_PERCENT),
            qwertyBottomPaddingDp = geometryInt(QWERTY_BOTTOM, 0, TouchKeyboardSettings.QWERTY_BOTTOM_PADDING_DP),
        )
    }

    internal fun saveTo(
        preferences: SharedPreferences,
        settings: TouchKeyboardSettings,
        orientation: Int = Configuration.ORIENTATION_PORTRAIT,
        commit: (SharedPreferences.Editor) -> Boolean = { it.commit() },
    ): Boolean {
        if (!settings.isValid()) return false
        val globalValues = settings.operationValues() + (LAYOUT to settings.layout.name)
        val geometryValues = mapOf(
            HEIGHT to settings.tenKeyHeightDp, WIDTH to settings.tenKeyWidthPercent,
            POSITION to settings.tenKeyPositionPercent, BOTTOM to settings.tenKeyBottomPaddingDp,
            QWERTY_SPLIT to settings.qwertySplit, QWERTY_GAP to settings.qwertyGapPercent,
            QWERTY_HEIGHT to settings.qwertyHeightDp, QWERTY_WIDTH to settings.qwertyWidthPercent,
            QWERTY_POSITION to settings.qwertyPositionPercent, QWERTY_BOTTOM to settings.qwertyBottomPaddingDp,
        ).forOrientation(orientation)
        return commitValues(preferences, globalValues + geometryValues, commit)
    }

    private fun TouchKeyboardSettings.operationValues() = mapOf(
        OPERATION to operation.name, HOLD_MILLIS to holdMillis, VIBRATE to vibrateOnBoundary,
    )

    private fun geometryKey(key: String, orientation: Int): String =
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) "${key}_landscape" else "${key}_portrait"

    private fun <T> Map<String, T>.forOrientation(orientation: Int): Map<String, T> =
        mapKeys { geometryKey(it.key, orientation) }

    private fun commitValues(preferences: SharedPreferences, values: Map<String, Any>,
        commit: (SharedPreferences.Editor) -> Boolean = { it.commit() }): Boolean {
        val stored = preferences.all
        val previous = values.keys.associateWith { stored[it] }
        if (runCatching { commit(preferences.edit().writeValues(values)) }.getOrDefault(false)) return true
        // commit失敗後に残るメモリー上の変更も、元の値と型へ戻します。
        runCatching { commit(preferences.edit().writeValues(previous)) }
        return false
    }

    private fun SharedPreferences.Editor.writeValues(values: Map<String, Any?>) = apply {
        values.forEach { (key, value) ->
            when (value) {
                null -> remove(key)
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    putStringSet(key, value as Set<String>)
                }
                else -> error("Unexpected preference type")
            }
        }
    }

    private fun TouchKeyboardSettings.isValid() = holdMillis in TouchKeyboardSettings.HOLD_MILLIS &&
        (holdMillis - TouchKeyboardSettings.HOLD_STEP_BASE) % TouchKeyboardSettings.HOLD_STEP == 0 &&
        tenKeyHeightDp in TouchKeyboardSettings.TEN_KEY_HEIGHT_DP &&
        tenKeyWidthPercent in TouchKeyboardSettings.TEN_KEY_WIDTH_PERCENT &&
        tenKeyPositionPercent in TouchKeyboardSettings.TEN_KEY_POSITION_PERCENT &&
        tenKeyBottomPaddingDp in TouchKeyboardSettings.TEN_KEY_BOTTOM_PADDING_DP &&
        qwertyGapPercent in TouchKeyboardSettings.QWERTY_GAP_PERCENT &&
        qwertyHeightDp in TouchKeyboardSettings.QWERTY_HEIGHT_DP &&
        qwertyWidthPercent in TouchKeyboardSettings.QWERTY_WIDTH_PERCENT &&
        qwertyPositionPercent in TouchKeyboardSettings.QWERTY_POSITION_PERCENT &&
        qwertyBottomPaddingDp in TouchKeyboardSettings.QWERTY_BOTTOM_PADDING_DP

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, default: T): T =
        value?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } } ?: default
}
