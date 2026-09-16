package jp.hayase.skk.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import jp.hayase.skk.dictionary.DictionaryRuntime

internal enum class BasicSetting(val preferenceKey: String, val defaultValue: Boolean) {
    SAVE_PERSONAL_DATA("save_personal_data", true),
    SHOW_STATUS("show_status", true),
    DYNAMIC_COMPLETION("dynamic_completion", false),
}

internal enum class BasicSaveStatus { IDLE, PENDING, SAVED, FAILED }

internal data class BasicSettingState(
    val savedValue: Boolean,
    val visibleValue: Boolean,
    val status: BasicSaveStatus = BasicSaveStatus.IDLE,
    val failedValue: Boolean? = null,
)

/** 一般設定の保存結果を Activity の再作成後にも保持します。 */
internal class BasicSettingsStore(
    private val preferences: SharedPreferences,
    private val worker: Executor,
    private val callback: Executor,
    private val setPersonalAllowed: (Boolean) -> Unit,
    private val commitEditor: (SharedPreferences.Editor) -> Boolean = { it.commit() },
) {
    private val states = BasicSetting.entries.associateWith { key ->
        val value = preferences.getBoolean(key.preferenceKey, key.defaultValue)
        BasicSettingState(value, value)
    }.toMutableMap()
    private val observers = mutableSetOf<(Map<BasicSetting, BasicSettingState>) -> Unit>()
    private val requestVersions = BasicSetting.entries.associateWith { 0L }.toMutableMap()
    private val completedVersions = BasicSetting.entries.associateWith { 0L }.toMutableMap()

    @Synchronized fun snapshot(): Map<BasicSetting, BasicSettingState> = states.toMap()

    fun observe(observer: (Map<BasicSetting, BasicSettingState>) -> Unit): Closeable {
        val initial = synchronized(this) {
            observers += observer
            states.toMap()
        }
        observer(initial)
        return Closeable { synchronized(this) { observers -= observer } }
    }

    fun request(key: BasicSetting, value: Boolean) {
        val version: Long? = synchronized(this) {
            val old = states.getValue(key)
            if (old.status == BasicSaveStatus.PENDING &&
                !(key == BasicSetting.SAVE_PERSONAL_DATA && !value && old.visibleValue)) null
            else if (old.visibleValue == value && old.status != BasicSaveStatus.FAILED) null
            else {
                // 初期化を commit 前に済ませ、許可中の待機要求を直ちに失効させます。
                // enable の commit はメモリー値を先に変えるため、成功まで禁止を維持します。
                if (key == BasicSetting.SAVE_PERSONAL_DATA) setPersonalAllowed(false)
                val nextVersion = requestVersions.getValue(key) + 1
                requestVersions[key] = nextVersion
                states[key] = old.copy(visibleValue = value, status = BasicSaveStatus.PENDING, failedValue = null)
                nextVersion
            }
        }
        if (version == null) return
        notifyObservers()
        worker.execute {
            val hadValue = preferences.contains(key.preferenceKey)
            val previous = preferences.getBoolean(key.preferenceKey, key.defaultValue)
            val saved = runCatching { commitEditor(preferences.edit().putBoolean(key.preferenceKey, value)) }
                .getOrDefault(false)
            if (!saved) {
                // commit() が false でも SharedPreferences のメモリー値は変更され得ます。
                runCatching {
                    val undo = preferences.edit()
                    if (hadValue) undo.putBoolean(key.preferenceKey, previous)
                    else undo.remove(key.preferenceKey)
                    commitEditor(undo)
                }
            }
            callback.execute {
                synchronized(this) {
                    if (version > completedVersions.getValue(key)) {
                        completedVersions[key] = version
                        val old = states.getValue(key)
                        val durableValue = if (saved) value else previous
                        states[key] = if (version != requestVersions.getValue(key)) {
                            old.copy(savedValue = durableValue)
                        } else if (saved) {
                            if (key == BasicSetting.SAVE_PERSONAL_DATA && value) setPersonalAllowed(true)
                            old.copy(savedValue = value, visibleValue = value, status = BasicSaveStatus.SAVED)
                        } else {
                            // 保存失敗によって学習を自動再許可しません。
                            old.copy(savedValue = durableValue,
                                visibleValue = if (key == BasicSetting.SAVE_PERSONAL_DATA) false else durableValue,
                                status = BasicSaveStatus.FAILED, failedValue = value)
                        }
                    }
                }
                notifyObservers()
            }
        }
    }

    private fun notifyObservers() {
        val (current, listeners) = synchronized(this) { states.toMap() to observers.toList() }
        listeners.forEach { it(current) }
    }
}

/** アプリプロセス中は保留・失敗状態を保持し、Activity を所有しません。 */
internal object BasicSettingsRuntime {
    @Volatile private var instance: BasicSettingsStore? = null

    @Synchronized fun get(context: Context): BasicSettingsStore = instance ?: run {
        val application = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        BasicSettingsStore(
            application.getSharedPreferences("settings", Context.MODE_PRIVATE),
            Executors.newSingleThreadExecutor(),
            Executor { check(main.post(it)) },
            { allowed -> DictionaryRuntime.get(application).personalDataPolicy.setAllowed(allowed) },
        ).also { instance = it }
    }
}
