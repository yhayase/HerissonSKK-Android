package jp.hayase.skk

import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import java.io.File
import java.util.concurrent.Executor
import jp.hayase.skk.dictionary.DictionaryManager
import jp.hayase.skk.dictionary.SQLiteDictionaryRepository
import jp.hayase.skk.settings.CustomizationSettings
import jp.hayase.skk.settings.CustomizationStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class QuoteNextServiceTest {
    @Test fun `C-qの次のC-nは押下反復解放まで入力先へ渡し焦点変更後の解放は止める`() {
        val context = RuntimeEnvironment.getApplication()
        val settings = context.getSharedPreferences("settings", 0)
        val oldStatus = settings.getBoolean("show_status", true)
        val hadStatus = settings.contains("show_status")
        settings.edit().putBoolean("show_status", false).commit()
        val databaseName = "quote-${System.nanoTime()}.db"
        val repository = SQLiteDictionaryRepository(context, databaseName)
        val direct = Executor { it.run() }
        val manager = DictionaryManager(repository, direct, direct, deferReads = false).also { it.loadAsync() }
        val path = File(context.cacheDir, "quote-${System.nanoTime()}.json")
        val store = CustomizationStore(path, direct, direct).also {
            it.loadAsync()
            it.save(CustomizationSettings(0, emacsEnabled = true), 0) {}
        }
        val controller = Robolectric.buildService(SkkInputMethodService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "dictionaries", manager)
        ReflectionHelpers.setField(service, "customization", store)
        val connection = BaseInputConnection(View(context), true)
        ReflectionHelpers.setField(service, "mInputConnection", connection)
        ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
        try {
            val info = EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
                initialSelStart = 0
                initialSelEnd = 0
            }
            service.onStartInput(info, false)
            val prefix = key(100, 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q)
            assertTrue(service.onKeyDown(prefix.keyCode, prefix))
            assertTrue(service.onKeyDown(prefix.keyCode,
                key(100, 105, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q, 1)))
            assertTrue(service.onKeyUp(prefix.keyCode, KeyEvent.changeAction(prefix, KeyEvent.ACTION_UP)))
            val target = key(110, 110, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N)
            assertFalse(service.onKeyDown(target.keyCode, target))
            assertFalse(service.onKeyDown(target.keyCode,
                key(110, 120, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N, 1)))
            assertFalse(service.onKeyUp(target.keyCode, KeyEvent.changeAction(target, KeyEvent.ACTION_UP)))

            val secondPrefix = key(200, 200, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q)
            assertTrue(service.onKeyDown(secondPrefix.keyCode, secondPrefix))
            val oldTarget = key(210, 210, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N)
            assertFalse(service.onKeyDown(oldTarget.keyCode, oldTarget))
            service.onFinishInput()
            assertTrue(service.onKeyUp(oldTarget.keyCode, KeyEvent.changeAction(oldTarget, KeyEvent.ACTION_UP)))
        } finally {
            controller.destroy()
            store.close()
            manager.close()
            context.deleteDatabase(databaseName)
            listOf(path, File(path.path + ".bak"), File(path.path + ".new")).forEach { it.delete() }
            settings.edit().also {
                if (hadStatus) it.putBoolean("show_status", oldStatus) else it.remove("show_status")
            }.commit()
        }
    }

    private fun key(down: Long, eventTime: Long, action: Int, code: Int, repeat: Int = 0) =
        KeyEvent(down, eventTime, action, code, repeat, KeyEvent.META_CTRL_ON,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
}
