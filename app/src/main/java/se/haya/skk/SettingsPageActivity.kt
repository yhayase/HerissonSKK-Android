package se.haya.skk

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 設定本文だけをスクロールさせ、画面名・戻る・保存操作を常に表示します。 */
abstract class SettingsPageActivity : AppCompatActivity() {
    private var dirty = false
    private var busy = false
    private lateinit var body: FrameLayout
    private lateinit var footer: LinearLayout
    private lateinit var statusView: TextView
    private var saveAction: () -> Unit = { saveAndClose() }
    private var defaultsAction: () -> Unit = { resetDraftToDefaults() }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Skk_Settings)
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestPageClose()
        })
    }

    protected fun installSettingsPage(title: CharSequence, content: Fragment) {
        installShell(title)
        if (supportFragmentManager.findFragmentById(R.id.settings_content) == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings_content, content).commitNow()
        }
    }

    protected fun installSettingsPage(title: CharSequence, content: View) {
        installShell(title)
        body.addView(content, FrameLayout.LayoutParams(-1, -1))
    }

    private fun installShell(pageTitle: CharSequence) {
        setContentView(R.layout.settings_page)
        findViewById<MaterialToolbar>(R.id.settings_top_bar).apply {
            title = pageTitle
            setNavigationOnClickListener { requestPageClose() }
        }
        body = findViewById(R.id.settings_content)
        statusView = findViewById(R.id.settings_status)
        footer = findViewById(R.id.settings_actions)
        findViewById<Button>(R.id.settings_defaults).setOnClickListener {
            if (!busy) MaterialAlertDialogBuilder(this)
                .setTitle("標準に戻しますか？")
                .setMessage("この画面の設定を標準値に戻します。保存するまでは反映されません。")
                .setPositiveButton("標準に戻す") { _, _ -> defaultsAction() }
                .setNegativeButton("キャンセル", null).show()
        }
        findViewById<Button>(R.id.settings_save).setOnClickListener { if (!busy) saveAction() }
        applySystemInsets()
    }

    protected fun setPageActions(onSave: () -> Unit, onDefaults: (() -> Unit)? = null) {
        saveAction = onSave
        defaultsAction = onDefaults ?: {}
        findViewById<Button>(R.id.settings_defaults).visibility = if (onDefaults == null) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.settings_save).layoutParams = if (onDefaults == null)
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        footer.visibility = View.VISIBLE
    }

    protected fun renderPageState(dirty: Boolean, busy: Boolean, status: CharSequence? = null) {
        this.dirty = dirty
        this.busy = busy
        if (!::footer.isInitialized) return
        for (i in 0 until footer.childCount) footer.getChildAt(i).isEnabled = !busy
        statusView.text = status
        statusView.visibility = if (status.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    fun requestPageClose() {
        if (busy) return
        if (!dirty) { discardAndClose(); return }
        MaterialAlertDialogBuilder(this).setTitle("変更を保存しますか？")
            .setPositiveButton("保存して閉じる") { _, _ -> saveAction() }
            .setNegativeButton("破棄") { _, _ -> discardAndClose() }
            .setNeutralButton("キャンセル", null).show()
    }

    protected open fun saveAndClose() = Unit
    protected open fun resetDraftToDefaults() = Unit
    protected open fun discardAndClose() = finish()
}
