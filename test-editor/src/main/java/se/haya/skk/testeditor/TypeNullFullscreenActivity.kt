package se.haya.skk.testeditor

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/** TYPE_NULL の入力接続を返す、全画面の非テキスト入力先です。 */
class TypeNullFullscreenActivity : Activity() {
    lateinit var input: View
        private set
    var inputConnectionCreated = false
        private set
    var createdInputType = Int.MIN_VALUE
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        input = object : View(this) {
            init {
                isFocusable = true
                isFocusableInTouchMode = true
            }

            override fun onCheckIsTextEditor(): Boolean = true

            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
                inputConnectionCreated = true
                outAttrs.inputType = InputType.TYPE_NULL
                createdInputType = outAttrs.inputType
                outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
                return BaseInputConnection(this, false)
            }
        }
        setContentView(input)
        input.requestFocus()
    }
}
