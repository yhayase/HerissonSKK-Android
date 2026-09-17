package jp.hayase.skk.testeditor

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.SurroundingText
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
            layout.addView(object : EditText(this) {
                override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                    val base = super.onCreateInputConnection(outAttrs) ?: return null
                    val mode = if (initial) intent.getStringExtra(EXTRA_CONNECTION_MODE) else null
                    if (mode == null) return base
                    return object : InputConnectionWrapper(base, false) {
                        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
                            if (mode == "no_snapshot" || mode == "unknown_offset") null else super.getExtractedText(request, flags)

                        @android.annotation.TargetApi(31)
                        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
                            if (mode == "no_snapshot" || mode == "extracted_only") return null
                            val value = super.getSurroundingText(beforeLength, afterLength, flags) ?: return null
                            return if (mode == "unknown_offset") SurroundingText(value.text,
                                value.selectionStart, value.selectionEnd, -1) else value
                        }

                        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                            super.commitText(if (mode == "filtered_newline" && text?.toString() == "\n") " " else text,
                                newCursorPosition)
                    }
                }
            }.apply {
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
        const val EXTRA_CONNECTION_MODE = "jp.hayase.skk.testeditor.CONNECTION_MODE"
        const val EXTRA_INITIAL_TEXT = "jp.hayase.skk.testeditor.INITIAL_TEXT"
        const val EXTRA_INITIAL_SELECTION = "jp.hayase.skk.testeditor.INITIAL_SELECTION"
    }
}
