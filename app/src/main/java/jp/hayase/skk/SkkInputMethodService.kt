package jp.hayase.skk

import android.content.Context
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import jp.hayase.skk.input.EditorSession
import jp.hayase.skk.input.HardwareKeyMapper
import jp.hayase.skk.input.KeyPressLedger
import jp.hayase.skk.core.InputMode

class SkkInputMethodService : InputMethodService(), InputManager.InputDeviceListener {
    private var generation = 0L
    private var session: EditorSession? = null
    private val mapper = HardwareKeyMapper()
    private val presses = KeyPressLedger()
    private var statusView: TextView? = null
    private var lastDevice: Int? = null
    private var requestedVisible = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(InputManager::class.java).registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        session?.close()
        session = null
        generation++
        mapper.reset()
        lastDevice = null
        requestedVisible = false
        super.onStartInput(attribute, restarting)
        val connection = currentInputConnection ?: return
        val info = attribute ?: return
        val protected = isPassword(info.inputType) || info.inputType == InputType.TYPE_NULL
        session = EditorSession(generation, connection, protected,
            !protected && info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0,
            info.initialSelStart, info.initialSelEnd)
        render()
    }

    override fun onFinishInput() {
        session?.close()
        session = null
        generation++
        mapper.reset()
        setCandidatesViewShown(false)
        super.onFinishInput()
    }

    override fun onUnbindInput() {
        session?.close()
        session = null
        generation++
        mapper.reset()
        super.onUnbindInput()
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return false
    }
    override fun onEvaluateFullscreenMode() = false

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean =
        session?.let { !it.protectedInput && it.active } == true

    override fun onWindowHidden() {
        requestedVisible = false
        super.onWindowHidden()
    }

    override fun onCreateCandidatesView(): View = TextView(this).apply {
        id = R.id.input_status
        textSize = 18f
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setTextColor(0xff202124.toInt())
        setBackgroundColor(0xfff1f3f4.toInt())
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        statusView = this
        updateStatus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handled = presses.down(event.deviceId, keyCode, event.downTime, generation,
            event.repeatCount, keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER &&
                keyCode != KeyEvent.KEYCODE_ESCAPE && !event.isCtrlPressed) {
            val current = session
            if (current == null || current.protectedInput || current.failed) false
            else {
                if (lastDevice != null && lastDevice != event.deviceId) {
                    current.preserveText()
                    mapper.reset()
                }
                lastDevice = event.deviceId
                when (val decoded = mapper.decode(event, current.engine.state.mode == InputMode.DIRECT,
                    current.hasComposition)) {
                    HardwareKeyMapper.Decoded.Pass -> {
                        if (!KeyEvent.isModifierKey(keyCode)) {
                            current.preserveText()
                            mapper.reset()
                        }
                        false
                    }
                    HardwareKeyMapper.Decoded.Wait -> true
                    is HardwareKeyMapper.Decoded.Action -> current.handle(decoded.action)
                }
            }
        }
        render()
        return handled || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        presses.up(event.deviceId, keyCode, event.downTime) || super.onKeyUp(keyCode, event)

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (session?.onSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd) == true) {
            mapper.reset()
            generation++
        }
        render()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        generation++
        session?.preserveText()
        mapper.reset()
        super.onConfigurationChanged(newConfig)
    }

    override fun onInputDeviceAdded(deviceId: Int) = Unit
    override fun onInputDeviceRemoved(deviceId: Int) {
        presses.removeDevice(deviceId)
        resetDevice(deviceId)
    }
    override fun onInputDeviceChanged(deviceId: Int) = resetDevice(deviceId)

    private fun resetDevice(deviceId: Int) {
        if (lastDevice == deviceId) {
            generation++
            session?.preserveText()
            mapper.reset()
            lastDevice = null
            render()
        }
    }

    override fun onDestroy() {
        session?.close()
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
        super.onDestroy()
    }

    private fun render() {
        updateStatus()
        val current = session
        val enabled = getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("show_status", true)
        val show = current != null && !current.protectedInput &&
            (enabled || current.hasComposition || current.failed)
        setCandidatesViewShown(show)
        if (show && !requestedVisible) {
            requestedVisible = true
            // Android のウィンドウ管理にも表示を要求します。文字キーの画面は作りません。
            if (Build.VERSION.SDK_INT >= 28) requestShowSelf(0)
        } else if (!show && requestedVisible) {
            requestedVisible = false
            requestHideSelf(0)
        }
    }

    private fun updateStatus() {
        val current = session
        statusView?.text = when {
            current == null || current.protectedInput -> ""
            current.failed -> getString(R.string.input_failed)
            else -> {
                val mode = getString(when (current.engine.state.mode) {
                    InputMode.HIRAGANA -> R.string.status_hiragana
                    InputMode.KATAKANA -> R.string.status_katakana
                    InputMode.HALFWIDTH -> R.string.status_halfwidth
                    InputMode.DIRECT -> R.string.status_ascii
                    InputMode.FULLWIDTH -> R.string.status_fullwidth
                })
                val candidate = current.view.candidate
                val details = buildList {
                    add(getString(R.string.limited_dictionary))
                    candidate?.let {
                        add("${it.index + 1}/${it.total} ${it.selected.text}")
                        it.selected.annotation?.let(::add)
                        if (it.menu.isNotEmpty()) add(it.menu.joinToString("  ") { item ->
                            val annotation = item.candidate.annotation?.let { note -> "（$note）" }.orEmpty()
                            "${item.label}: ${item.candidate.text}$annotation"
                        })
                    }
                    current.notice?.let(::add)
                }
                "$mode  ${details.joinToString("\n")}"
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        internal fun isPassword(type: Int): Boolean {
            val variation = type and InputType.TYPE_MASK_VARIATION
            return when (type and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                InputType.TYPE_CLASS_TEXT -> variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                else -> false
            }
        }
    }
}
