package se.haya.skk.settings

import android.content.Context
import android.content.SharedPreferences

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
) {
    init {
        require(holdMillis in HOLD_MILLIS && (holdMillis - HOLD_STEP_BASE) % HOLD_STEP == 0)
        require(tenKeyHeightDp in TEN_KEY_HEIGHT_DP)
        require(tenKeyWidthPercent in TEN_KEY_WIDTH_PERCENT)
        require(tenKeyPositionPercent in TEN_KEY_POSITION_PERCENT)
        require(tenKeyBottomPaddingDp in TEN_KEY_BOTTOM_PADDING_DP)
    }

    companion object {
        val HOLD_MILLIS = 100..1000
        const val HOLD_STEP_BASE = 100
        const val HOLD_STEP = 10
        val TEN_KEY_HEIGHT_DP = 160..400
        val TEN_KEY_WIDTH_PERCENT = 60..100
        val TEN_KEY_POSITION_PERCENT = 0..100
        val TEN_KEY_BOTTOM_PADDING_DP = 0..160
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

    fun read(context: Context): TouchKeyboardSettings = readFrom(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    fun save(context: Context, settings: TouchKeyboardSettings): Boolean = saveTo(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE), settings,
    )

    /** 入力中の配列切替は、操作方法や形状などの設定を上書きしません。 */
    fun saveLayout(context: Context, layout: ScreenKeyboardLayout): Boolean = saveLayoutTo(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE), layout,
    )

    internal fun saveLayoutTo(preferences: SharedPreferences, layout: ScreenKeyboardLayout,
        commit: (SharedPreferences.Editor) -> Boolean = { it.commit() }): Boolean {
        val previous = preferences.all[LAYOUT]
        if (runCatching { commit(preferences.edit().putString(LAYOUT, layout.name)) }.getOrDefault(false)) return true
        // commit失敗後に残るメモリー上の変更も、元の値と型へ戻します。
        runCatching {
            val undo = preferences.edit()
            when (previous) {
                null -> undo.remove(LAYOUT)
                is String -> undo.putString(LAYOUT, previous)
                is Int -> undo.putInt(LAYOUT, previous)
                is Boolean -> undo.putBoolean(LAYOUT, previous)
                is Long -> undo.putLong(LAYOUT, previous)
                is Float -> undo.putFloat(LAYOUT, previous)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    undo.putStringSet(LAYOUT, previous as Set<String>)
                }
            }
            commit(undo)
        }
        return false
    }

    /** IMEからの調整を含め、形状設定だけを現在値に保存します。 */
    fun saveGeometry(context: Context, heightDp: Int, widthPercent: Int, positionPercent: Int, bottomPaddingDp: Int? = null): Boolean {
        if (heightDp !in TouchKeyboardSettings.TEN_KEY_HEIGHT_DP ||
            widthPercent !in TouchKeyboardSettings.TEN_KEY_WIDTH_PERCENT ||
            positionPercent !in TouchKeyboardSettings.TEN_KEY_POSITION_PERCENT ||
            (bottomPaddingDp != null && bottomPaddingDp !in TouchKeyboardSettings.TEN_KEY_BOTTOM_PADDING_DP)) return false
        return saveGeometryTo(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            heightDp, widthPercent, positionPercent, bottomPaddingDp,
        )
    }

    internal fun readFrom(preferences: SharedPreferences): TouchKeyboardSettings = TouchKeyboardSettings(
        layout = enumOrDefault(runCatching { preferences.getString(LAYOUT, null) }.getOrNull(), ScreenKeyboardLayout.QWERTY),
        operation = enumOrDefault(runCatching { preferences.getString(OPERATION, null) }.getOrNull(), TenKeyOperation.HOLD_AND_RETURN),
        holdMillis = runCatching { preferences.getInt(HOLD_MILLIS, 350) }.getOrDefault(350)
            .takeIf { it in TouchKeyboardSettings.HOLD_MILLIS &&
                (it - TouchKeyboardSettings.HOLD_STEP_BASE) % TouchKeyboardSettings.HOLD_STEP == 0 } ?: 350,
        vibrateOnBoundary = runCatching { preferences.getBoolean(VIBRATE, true) }.getOrDefault(true),
        tenKeyHeightDp = runCatching { preferences.getInt(HEIGHT, 256) }.getOrDefault(256)
            .takeIf { it in TouchKeyboardSettings.TEN_KEY_HEIGHT_DP } ?: 256,
        tenKeyWidthPercent = runCatching { preferences.getInt(WIDTH, 100) }.getOrDefault(100)
            .takeIf { it in TouchKeyboardSettings.TEN_KEY_WIDTH_PERCENT } ?: 100,
        tenKeyPositionPercent = runCatching { preferences.getInt(POSITION, 50) }.getOrDefault(50)
            .takeIf { it in TouchKeyboardSettings.TEN_KEY_POSITION_PERCENT } ?: 50,
        tenKeyBottomPaddingDp = runCatching { preferences.getInt(BOTTOM, 0) }.getOrDefault(0)
            .takeIf { it in TouchKeyboardSettings.TEN_KEY_BOTTOM_PADDING_DP } ?: 0,
    )

    internal fun saveTo(
        preferences: SharedPreferences,
        settings: TouchKeyboardSettings,
        commit: (SharedPreferences.Editor) -> Boolean = { it.commit() },
    ): Boolean {
        if (!settings.isValid()) return false
        val keys = listOf(LAYOUT, OPERATION, HOLD_MILLIS, VIBRATE, HEIGHT, WIDTH, POSITION, BOTTOM)
        val previous = keys.associateWith { key ->
            preferences.all[key]
        }
        val success = runCatching {
            commit(preferences.edit()
                .putString(LAYOUT, settings.layout.name)
                .putString(OPERATION, settings.operation.name)
                .putInt(HOLD_MILLIS, settings.holdMillis)
                .putBoolean(VIBRATE, settings.vibrateOnBoundary)
                .putInt(HEIGHT, settings.tenKeyHeightDp)
                .putInt(WIDTH, settings.tenKeyWidthPercent)
                .putInt(POSITION, settings.tenKeyPositionPercent)
                .putInt(BOTTOM, settings.tenKeyBottomPaddingDp))
        }.getOrDefault(false)
        if (success) return true
        runCatching {
            val undo = preferences.edit()
            previous.forEach { (key, value) ->
                when (value) {
                    null -> undo.remove(key)
                    is String -> undo.putString(key, value)
                    is Int -> undo.putInt(key, value)
                    is Boolean -> undo.putBoolean(key, value)
                    else -> error("Unexpected preference type")
                }
            }
            commit(undo)
        }
        return false
    }

    private fun saveGeometryTo(preferences: SharedPreferences, heightDp: Int, widthPercent: Int,
        positionPercent: Int, bottomPaddingDp: Int?): Boolean {
        val keys = listOfNotNull(HEIGHT, WIDTH, POSITION, BOTTOM.takeIf { bottomPaddingDp != null })
        val previous = keys.associateWith { preferences.all[it] }
        val success = runCatching {
            preferences.edit().putInt(HEIGHT, heightDp).putInt(WIDTH, widthPercent)
                .putInt(POSITION, positionPercent).apply {
                    bottomPaddingDp?.let { putInt(BOTTOM, it) }
                }.commit()
        }.getOrDefault(false)
        if (success) return true
        runCatching {
            val undo = preferences.edit()
            previous.forEach { (key, value) ->
                when (value) {
                    null -> undo.remove(key)
                    is Int -> undo.putInt(key, value)
                    is String -> undo.putString(key, value)
                    is Boolean -> undo.putBoolean(key, value)
                    else -> error("Unexpected preference type")
                }
            }
            undo.commit()
        }
        return false
    }

    private fun TouchKeyboardSettings.isValid() = holdMillis in TouchKeyboardSettings.HOLD_MILLIS &&
        (holdMillis - TouchKeyboardSettings.HOLD_STEP_BASE) % TouchKeyboardSettings.HOLD_STEP == 0 &&
        tenKeyHeightDp in TouchKeyboardSettings.TEN_KEY_HEIGHT_DP &&
        tenKeyWidthPercent in TouchKeyboardSettings.TEN_KEY_WIDTH_PERCENT &&
        tenKeyPositionPercent in TouchKeyboardSettings.TEN_KEY_POSITION_PERCENT &&
        tenKeyBottomPaddingDp in TouchKeyboardSettings.TEN_KEY_BOTTOM_PADDING_DP

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, default: T): T =
        value?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } } ?: default
}
