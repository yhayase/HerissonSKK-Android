package se.haya.skk.testeditor

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText

/** 構成変更時も同じ入力欄を保持し、IME のビュー再生成だけを検証する入力先です。 */
class StableEditorActivity : Activity() {
    lateinit var editor: EditText
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            hint = "構成変更をまたぐ入力欄"
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        setContentView(editor)
        applySystemInsets()
        editor.requestFocus()
    }
}
