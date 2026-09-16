package jp.hayase.skk.testeditor

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
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
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val fallback = InstrumentationRegistry.getArguments().getString("fallback_ime") ?: error("待避用 IME が未指定です")
        require(fallback.matches(Regex("[A-Za-z0-9_.$/]+")) && fallback.substringBefore('/') != target)
        val cold = mutableListOf<Double>()
        val warm = mutableListOf<Double>()
        for ((name, samples) in listOf("cold" to cold, "warm" to warm)) {
            repeat(30) {
                if (name == "cold") {
                    // 選択中の IME を停止すると OS の自動切替と再選択が競合するため、先に待避します。
                    shell("ime set $fallback")
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
                    focus(editor)
                    awaitStatus()
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
            focus(editor)
            awaitStatus()
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON)
            val samples = mutableListOf<Double>()
            repeat(1020) { index ->
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
        val activity = instrumentation.startActivitySync(intent) as InputTestActivity
        try {
            block(activity)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun focus(editor: EditText) {
        val position = IntArray(2)
        instrumentation.runOnMainSync {
            editor.getLocationOnScreen(position)
            position[0] += editor.width / 2
            position[1] += editor.height / 2
        }
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                position[0].toFloat(), position[1].toFloat(), 0)
            try {
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun awaitStatus() {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (automation.windows.any { window ->
                    val root = window.root
                    root?.packageName?.toString() == target &&
                        root.findAccessibilityNodeInfosByText("かな").isNotEmpty()
                }) return
            SystemClock.sleep(10)
        }
        val windows = automation.windows.joinToString { window ->
            "type=${window.type}, package=${window.root?.packageName}, kana=${window.root?.findAccessibilityNodeInfosByText("かな")?.size}"
        }
        val state = shell("dumpsys input_method").lineSequence().filter { line ->
            listOf("mCurMethodId=", "mInputStarted=", "mShowInputRequested=", "hintText=",
                "mDecorViewVisible=", "mCandidatesVisibility=", "mSelectedMethodId=").any { it in line }
        }.joinToString("\n")
        fail("測定用 IME の状態表示が現れませんでした。$windows\n$state")
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
