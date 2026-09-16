package jp.hayase.skk.testeditor

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 合成入力だけを使用します。通常の結合試験では実行せず、専用スクリプトから起動します。 */
@RunWith(AndroidJUnit4::class)
class PerformanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val target = "jp.hayase.skk.benchmark"

    @Test fun measure() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("performance") == "true")
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val fallback = InstrumentationRegistry.getArguments().getString("fallback_ime") ?: error("待避用 IME が未指定です")
        require(fallback.matches(Regex("[A-Za-z0-9_.$/]+")) && fallback.substringBefore('/') != target)
        val startupSamples = sampleCount("startup_samples", 30, 30)
        val inputSamples = sampleCount("input_samples", 1000, 1000)
        val cold = mutableListOf<Double>()
        val warm = mutableListOf<Double>()
        for ((name, samples) in listOf("cold" to cold, "warm" to warm)) {
            repeat(startupSamples) {
                if (name == "cold") {
                    // 選択変更の返却だけでは旧 IME の unbind は完了していないため、待避先の接続確立まで待ちます。
                    shell("ime set $fallback")
                    awaitBoundIme(fallback)
                    shell("am force-stop $target")
                    assertTrue("初回起動の前に IME が起動しています", shell("pidof $target").isBlank())
                } else {
                    assertTrue("起動済みの IME が終了しています", shell("pidof $target").isNotBlank())
                }
                val started = SystemClock.elapsedRealtimeNanos()
                if (name == "cold") {
                    shell("ime set $target/jp.hayase.skk.SkkInputMethodService")
                }
                withEditor { activity ->
                    lateinit var editor: EditText
                    instrumentation.runOnMainSync {
                        editor = descendants(activity.window.decorView).filterIsInstance<EditText>().first()
                    }
                    focus(activity, editor)
                    awaitStatus(activity, editor)
                    samples += (SystemClock.elapsedRealtimeNanos() - started) / 1e6
                }
            }
            report("startup_${name}_ms", samples)
        }
        withEditor { activity ->
            lateinit var editor: EditText
            instrumentation.runOnMainSync {
                editor = descendants(activity.window.decorView).filterIsInstance<EditText>()
                    .first { it.hint.toString().startsWith("検索") }
            }
            focus(activity, editor)
            awaitStatus(activity, editor)
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            val samples = mutableListOf<Double>()
            repeat(20 + inputSamples) { index ->
                val drawn = CountDownLatch(1)
                var elapsed = 0.0
                var started = 0L
                lateinit var listener: ViewTreeObserver.OnDrawListener
                instrumentation.runOnMainSync {
                    val length = editor.length() + 1
                    listener = ViewTreeObserver.OnDrawListener {
                        if (editor.length() == length && drawn.count > 0) {
                            elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
                            drawn.countDown()
                        }
                    }
                    editor.viewTreeObserver.addOnDrawListener(listener)
                }
                started = SystemClock.elapsedRealtimeNanos()
                try {
                    key(KeyEvent.KEYCODE_A)
                    assertTrue("合成入力が描画されませんでした", drawn.await(5, TimeUnit.SECONDS))
                    instrumentation.runOnMainSync { assertEquals("あ".repeat(index + 1), editor.text.toString()) }
                    if (index >= 20) samples += elapsed
                } finally {
                    instrumentation.runOnMainSync { editor.viewTreeObserver.removeOnDrawListener(listener) }
                }
            }
            report("injection_to_draw_ms", samples)
            memory("after_input")
        }
        instrumentation.waitForIdleSync()
        memory("editor_closed")
    }

    private fun withEditor(block: (InputTestActivity) -> Unit) {
        val intent = Intent(instrumentation.targetContext, InputTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<InputTestActivity>(intent).use { scenario ->
            lateinit var activity: InputTestActivity
            scenario.onActivity { activity = it }
            block(activity)
        }
        instrumentation.waitForIdleSync()
    }

    private fun focus(activity: InputTestActivity, editor: EditText) {
        instrumentation.runOnMainSync { editor.requestFocus() }
        val deadline = SystemClock.uptimeMillis() + 10000
        var state = editorState(activity, editor)
        while (SystemClock.uptimeMillis() < deadline && !state.ready) {
            SystemClock.sleep(10)
            state = editorState(activity, editor)
        }
        assertTrue("入力欄のウィンドウとフォーカスが準備できませんでした。$state", state.ready)
    }

    private data class EditorState(
        val windowFocus: Boolean,
        val attached: Boolean,
        val shown: Boolean,
        val editorFocus: Boolean,
        val width: Int,
        val height: Int,
        val finishing: Boolean,
        val destroyed: Boolean,
    ) {
        val ready: Boolean
            get() = windowFocus && attached && shown && editorFocus && width > 0 && height > 0 &&
                !finishing && !destroyed
    }

    private fun editorState(activity: InputTestActivity, editor: EditText): EditorState {
        lateinit var state: EditorState
        instrumentation.runOnMainSync {
            state = EditorState(
                activity.hasWindowFocus(), editor.isAttachedToWindow, editor.isShown, editor.hasFocus(),
                editor.width, editor.height, activity.isFinishing, activity.isDestroyed,
            )
        }
        return state
    }

    private fun sampleCount(name: String, default: Int, maximum: Int): Int {
        val value = InstrumentationRegistry.getArguments().getString(name) ?: return default
        return requireNotNull(value.toIntOrNull()) {
            "$name は 1 以上 $maximum 以下の整数で指定します"
        }.also {
            require(it in 1..maximum) { "$name は 1 以上 $maximum 以下で指定します" }
        }
    }

    private fun diagnostic(command: String, keys: List<String>): String =
        shell(command).lineSequence().filter { line -> keys.any { it in line } }.joinToString("\n")

    private fun awaitBoundIme(expected: String) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var state = ""
        while (SystemClock.uptimeMillis() < deadline) {
            state = diagnostic("dumpsys input_method", listOf("mCurMethodId=", "mCurId=", "mCurMethod="))
            val lines = state.lineSequence().map(String::trim).toList()
            val selected = lines.any { it == "mCurMethodId=$expected" }
            val connected = lines.any {
                it.startsWith("mCurId=$expected ") &&
                    "mHaveConnection=true" in it && "mBoundToMethod=true" in it
            }
            val methodCreated = lines.any { it.startsWith("mCurMethod=") && !it.endsWith("=null") }
            if (selected && connected && methodCreated) return
            SystemClock.sleep(10)
        }
        fail("待避用 IME の接続が確立しませんでした。\n$state")
    }

    private fun awaitStatus(activity: InputTestActivity, editor: EditText) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (automation.windows.any { window ->
                    val root = window.root
                    root?.packageName?.toString() == target &&
                        root.findAccessibilityNodeInfosByViewId("$target:id/input_status").isNotEmpty()
                }) return
            SystemClock.sleep(10)
        }
        val windows = automation.windows.joinToString { window ->
            "type=${window.type}, package=${window.root?.packageName}, status=${window.root?.findAccessibilityNodeInfosByViewId("$target:id/input_status")?.size}"
        }
        val inputMethod = diagnostic("dumpsys input_method", listOf(
            "mCurMethodId=", "mInputStarted=", "mShowInputRequested=", "mDecorViewVisible=",
            "mCandidatesVisibility=", "mSelectedMethodId=", "mCurFocusedWindow=",
        ))
        val activities = diagnostic("dumpsys activity activities", listOf(
            "topResumedActivity=", "mResumedActivity:", "ResumedActivity:", "jp.hayase.skk.testeditor",
        ))
        val window = diagnostic("dumpsys window", listOf(
            "mCurrentFocus=", "mFocusedApp=", "mDreamingLockscreen=", "isStatusBarKeyguard=",
        ))
        fail("測定用 IME の状態表示が現れませんでした。${editorState(activity, editor)}\n" +
            "$windows\n$inputMethod\n$activities\n$window")
    }

    private fun report(name: String, values: List<Double>) {
        val sorted = values.sorted()
        fun percentile(p: Double) = sorted[(kotlin.math.ceil(sorted.size * p).toInt() - 1).coerceAtLeast(0)]
        instrumentation.sendStatus(0, Bundle().apply {
            putString("metric", name)
            putInt("samples", values.size)
            putDouble("p50", percentile(0.50))
            putDouble("p95", percentile(0.95))
            putDouble("max", sorted.last())
            putString("values", values.joinToString(","))
        })
    }

    private fun memory(stage: String) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("memory_stage", stage)
            putString("memory", shell("dumpsys meminfo $target"))
        })
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun key(code: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
