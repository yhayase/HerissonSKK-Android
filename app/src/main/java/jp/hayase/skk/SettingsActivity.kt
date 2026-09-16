package jp.hayase.skk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

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
        button(R.string.dictionary_settings_open) {
            startActivity(Intent(this, DictionarySettingsActivity::class.java))
        }
        val preferences = getSharedPreferences("settings", MODE_PRIVATE)
        layout.addView(Switch(this).apply {
            setText(R.string.show_status)
            isChecked = preferences.getBoolean("show_status", true)
            setOnCheckedChangeListener { _, checked -> preferences.edit().putBoolean("show_status", checked).apply() }
        })
        label(R.string.key_help)
        setContentView(ScrollView(this).apply { addView(layout) })
        applySystemInsets()
    }
}
