package jp.hayase.skk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import jp.hayase.skk.dictionary.DictionaryRuntime

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val preferences = getSharedPreferences("settings", MODE_PRIVATE)
        layout.addView(Switch(this).apply {
            setText(R.string.save_personal_data)
            isChecked = preferences.getBoolean("save_personal_data", true)
            setOnCheckedChangeListener { _, checked ->
                DictionaryRuntime.get(this@SettingsActivity).personalDataPolicy.setAllowed(checked)
                preferences.edit().putBoolean("save_personal_data", checked).apply()
            }
        })
        label(R.string.save_personal_data_explanation)
        layout.addView(Switch(this).apply {
            setText(R.string.show_status)
            isChecked = preferences.getBoolean("show_status", true)
            setOnCheckedChangeListener { _, checked -> preferences.edit().putBoolean("show_status", checked).apply() }
        })
        label(R.string.show_status_explanation)
        layout.addView(Switch(this).apply {
            setText(R.string.dynamic_completion)
            isChecked = preferences.getBoolean("dynamic_completion", false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean("dynamic_completion", checked).apply()
            }
        })
        label(R.string.dynamic_completion_explanation)
        label(R.string.key_help)
        setContentView(ScrollView(this).apply { addView(layout) })
        applySystemInsets()
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
