package se.haya.skk.settings

import android.content.Context
import android.content.SharedPreferences

/** 常時読み入力は設定画面の下書きとは別に、各入力面の現在状態として保存します。 */
data class TenKeyReadingModes(
    val kana: Boolean = false,
    val alpha: Boolean = false,
    val number: Boolean = false,
)

object TenKeyReadingModeStore {
    private const val PREFS = "settings"
    private const val LEGACY = "ten_key_auto_start_reading"
    private const val KANA = "ten_key_always_reading_kana"
    private const val ALPHA = "ten_key_always_reading_alpha"
    private const val NUMBER = "ten_key_always_reading_number"
    private val keys = listOf(KANA, ALPHA, NUMBER)

    fun read(context: Context): TenKeyReadingModes = readFrom(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    fun save(context: Context, modes: TenKeyReadingModes): Boolean = saveTo(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE), modes,
    )

    internal fun readFrom(preferences: SharedPreferences): TenKeyReadingModes {
        // 旧設定は新しい面別キーがない面だけで参照し、読み込み時には書き換えません。
        val legacy = runCatching { preferences.getBoolean(LEGACY, false) }.getOrDefault(false)
        fun readMode(key: String): Boolean = if (preferences.contains(key)) {
            runCatching { preferences.getBoolean(key, false) }.getOrDefault(false)
        } else legacy
        return TenKeyReadingModes(readMode(KANA), readMode(ALPHA), readMode(NUMBER))
    }

    internal fun saveTo(
        preferences: SharedPreferences,
        modes: TenKeyReadingModes,
        commit: (SharedPreferences.Editor) -> Boolean = { it.commit() },
    ): Boolean {
        val previous = keys.associateWith { preferences.all[it] }
        val success = runCatching {
            commit(preferences.edit().putBoolean(KANA, modes.kana)
                .putBoolean(ALPHA, modes.alpha).putBoolean(NUMBER, modes.number))
        }.getOrDefault(false)
        if (success) return true
        // commit失敗時もメモリー上の変更が残るため、保存前の値と型を復元します。
        runCatching {
            val undo = preferences.edit()
            previous.forEach { (key, value) ->
                when (value) {
                    null -> undo.remove(key)
                    is Boolean -> undo.putBoolean(key, value)
                    is String -> undo.putString(key, value)
                    is Int -> undo.putInt(key, value)
                    is Long -> undo.putLong(key, value)
                    is Float -> undo.putFloat(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        undo.putStringSet(key, value as Set<String>)
                    }
                    else -> error("Unexpected preference type")
                }
            }
            commit(undo)
        }
        return false
    }
}
