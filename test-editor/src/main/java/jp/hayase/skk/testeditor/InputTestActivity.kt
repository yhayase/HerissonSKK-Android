package jp.hayase.skk.testeditor

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 外部へ送信せず、Enter が入力先まで届いた回数を表示します。 */
class InputTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val result = TextView(this).apply { text = getString(R.string.test_action_count, 0) }
        layout.addView(result)
        var actions = 0
        fun editor(label: Int, type: Int, options: Int = EditorInfo.IME_ACTION_NONE, initial: Boolean = false) {
            layout.addView(EditText(this).apply {
                setHint(label)
                inputType = type
                imeOptions = options or if (intent.getBooleanExtra(EXTRA_SUPPRESS_LEARNING, false)) {
                    EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                } else 0
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                if (initial) {
                    intent.getStringExtra(EXTRA_INITIAL_TEXT)?.let { value ->
                        setText(value)
                        val selection = intent.getIntExtra(EXTRA_INITIAL_SELECTION, value.length)
                        require(selection in 0..value.length) { "初期選択位置が本文外です" }
                        setSelection(selection)
                    }
                }
                setOnEditorActionListener { _, actionId, event ->
                    if (event?.action == KeyEvent.ACTION_DOWN || (event == null && actionId != EditorInfo.IME_NULL)) {
                        result.text = getString(R.string.test_action_count, ++actions)
                    }
                    options == EditorInfo.IME_ACTION_SEND || options == EditorInfo.IME_ACTION_SEARCH
                }
            })
        }
        val text = InputType.TYPE_CLASS_TEXT
        editor(R.string.test_multiline_a, text or InputType.TYPE_TEXT_FLAG_MULTI_LINE, initial = true)
        editor(R.string.test_multiline_b, text or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        editor(R.string.test_search, text, EditorInfo.IME_ACTION_SEARCH)
        editor(R.string.test_send, text, EditorInfo.IME_ACTION_SEND)
        editor(R.string.test_password, text or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        editor(R.string.test_no_learning, text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        setContentView(ScrollView(this).apply { addView(layout) })
        applySystemInsets()
    }

    companion object {
        const val EXTRA_SUPPRESS_LEARNING = "jp.hayase.skk.testeditor.SUPPRESS_LEARNING"
        const val EXTRA_INITIAL_TEXT = "jp.hayase.skk.testeditor.INITIAL_TEXT"
        const val EXTRA_INITIAL_SELECTION = "jp.hayase.skk.testeditor.INITIAL_SELECTION"
    }
}
