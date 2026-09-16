package jp.hayase.skk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.Closeable
import jp.hayase.skk.settings.BasicSaveStatus
import jp.hayase.skk.settings.BasicSetting
import jp.hayase.skk.settings.BasicSettingState
import jp.hayase.skk.settings.BasicSettingsRuntime
import jp.hayase.skk.settings.BasicSettingsStore

class SettingsActivity : Activity() {
    private lateinit var store: BasicSettingsStore
    private var subscription: Closeable? = null
    private val switches = mutableMapOf<BasicSetting, Switch>()
    private val statuses = mutableMapOf<BasicSetting, TextView>()
    private val retries = mutableMapOf<BasicSetting, Button>()
    private var rendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = storeFactoryForTest?.invoke(this) ?: BasicSettingsRuntime.get(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        fun label(id: Int) { layout.addView(TextView(this).apply { setText(id); textSize = 18f }) }
        fun button(id: Int, action: () -> Unit) {
            layout.addView(Button(this).apply { setText(id); setOnClickListener { action() } })
        }
        label(R.string.setup_title)
        label(R.string.setup_description)
        button(R.string.enable_ime) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        button(R.string.select_ime) { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        button(R.string.physical_keyboard_layout_settings) { openPhysicalKeyboardLayoutSettings() }
        button(R.string.dictionary_settings_open) {
            startActivity(Intent(this, DictionarySettingsActivity::class.java))
        }
        layout.addView(Button(this).apply {
            text = "入力規則・句読点・候補を設定する"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, CustomizationSettingsActivity::class.java)) }
        })
        fun setting(key: BasicSetting, title: Int, explanation: Int) {
            switches[key] = Switch(this).apply {
                setText(title)
                setOnCheckedChangeListener { _, checked -> if (!rendering) store.request(key, checked) }
                layout.addView(this)
            }
            label(explanation)
            statuses[key] = TextView(this).apply { layout.addView(this) }
            retries[key] = Button(this).apply {
                text = "保存を再試行する"
                visibility = View.GONE
                setOnClickListener {
                    store.snapshot().getValue(key).failedValue?.let { failed -> store.request(key, failed) }
                }
                layout.addView(this)
            }
        }
        setting(BasicSetting.SAVE_PERSONAL_DATA, R.string.save_personal_data, R.string.save_personal_data_explanation)
        setting(BasicSetting.SHOW_STATUS, R.string.show_status, R.string.show_status_explanation)
        setting(BasicSetting.DYNAMIC_COMPLETION, R.string.dynamic_completion, R.string.dynamic_completion_explanation)
        label(R.string.key_help)
        setContentView(ScrollView(this).apply { addView(layout) })
        applySystemInsets()
        subscription = store.observe(::renderSettings)
    }

    override fun onDestroy() {
        subscription?.close()
        subscription = null
        super.onDestroy()
    }

    private fun renderSettings(values: Map<BasicSetting, BasicSettingState>) {
        rendering = true
        try {
            values.forEach { (key, state) ->
                switches.getValue(key).apply {
                    isChecked = state.visibleValue
                    isEnabled = state.status != BasicSaveStatus.PENDING
                }
                statuses.getValue(key).text = when (state.status) {
                    BasicSaveStatus.IDLE -> ""
                    BasicSaveStatus.PENDING -> "保存しています。"
                    BasicSaveStatus.SAVED -> "保存しました。"
                    BasicSaveStatus.FAILED -> if (key == BasicSetting.SAVE_PERSONAL_DATA) {
                        "保存できませんでした。現在の学習は停止しています。再起動時は前回保存した設定に戻ることがあります。"
                    } else "保存できませんでした。前回保存した設定を表示しています。"
                }
                retries.getValue(key).visibility = if (state.status == BasicSaveStatus.FAILED) View.VISIBLE else View.GONE
            }
        } finally { rendering = false }
    }

    private fun openPhysicalKeyboardLayoutSettings() {
        when (dispatchPhysicalKeyboardLayoutSettings(
            Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS).resolveActivity(packageManager) != null,
        ) { action -> startActivity(Intent(action)) }) {
            PhysicalKeyboardLayoutSettingsDispatch.INPUT_METHOD_FALLBACK -> {
                Toast.makeText(this, R.string.physical_keyboard_layout_settings_fallback, Toast.LENGTH_LONG).show()
            }
            PhysicalKeyboardLayoutSettingsDispatch.UNAVAILABLE -> {
                Toast.makeText(this, R.string.physical_keyboard_layout_settings_unavailable, Toast.LENGTH_LONG).show()
            }
            PhysicalKeyboardLayoutSettingsDispatch.HARD_KEYBOARD -> Unit
        }
    }

    companion object {
        internal var storeFactoryForTest: ((SettingsActivity) -> BasicSettingsStore)? = null
    }
}

internal enum class PhysicalKeyboardLayoutSettingsDispatch {
    HARD_KEYBOARD,
    INPUT_METHOD_FALLBACK,
    UNAVAILABLE,
}

internal fun dispatchPhysicalKeyboardLayoutSettings(
    hardKeyboardSettingsAvailable: Boolean,
    startSettings: (String) -> Unit,
): PhysicalKeyboardLayoutSettingsDispatch {
    if (hardKeyboardSettingsAvailable) {
        try {
            startSettings(Settings.ACTION_HARD_KEYBOARD_SETTINGS)
            return PhysicalKeyboardLayoutSettingsDispatch.HARD_KEYBOARD
        } catch (_: ActivityNotFoundException) {
            // フォールバックの設定画面を試します。
        } catch (_: SecurityException) {
            // フォールバックの設定画面を試します。
        }
    }
    return try {
        startSettings(Settings.ACTION_INPUT_METHOD_SETTINGS)
        PhysicalKeyboardLayoutSettingsDispatch.INPUT_METHOD_FALLBACK
    } catch (_: ActivityNotFoundException) {
        PhysicalKeyboardLayoutSettingsDispatch.UNAVAILABLE
    } catch (_: SecurityException) {
        PhysicalKeyboardLayoutSettingsDispatch.UNAVAILABLE
    }
}
