package se.haya.skk.testeditor

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 設定 E2E で Emacs を有効にした後、実 WebView の入力接続を検証します。 */
object WebEditorAssertions {
    fun run(instrumentation: Instrumentation) {
        ActivityScenario.launch<WebInputTestActivity>(
            Intent(instrumentation.targetContext, WebInputTestActivity::class.java),
        ).use { scenario ->
            val web = WebEditor(instrumentation, scenario)
            web.awaitPage()
            for (field in listOf("textarea", "contenteditable")) {
                web.prepare(field, "abc", 2)
                web.key(KeyEvent.KEYCODE_B)
                web.await(field, "abc", 1)
                web.key(KeyEvent.KEYCODE_F)
                web.await(field, "abc", 2)
                web.key(KeyEvent.KEYCODE_H)
                web.await(field, "ac", 1)
                web.key(KeyEvent.KEYCODE_D)
                web.await(field, "a", 1)
                web.key(KeyEvent.KEYCODE_M)
                if (field == "textarea") {
                    web.await(field, "a\n", 2)
                    web.key(KeyEvent.KEYCODE_B)
                    web.await(field, "a\n", 1)
                    web.key(KeyEvent.KEYCODE_F)
                    web.await(field, "a\n", 2)
                } else {
                    web.awaitChangedHtml(field, "a")
                    web.key(KeyEvent.KEYCODE_H)
                    web.awaitBackspaceAfterNewline(field)
                }
                assertEquals("C-m がフォームを送信しました: $field", 0, web.snapshot(field).getInt("submits"))
            }
        }
    }

    private class WebEditor(
        private val instrumentation: Instrumentation,
        private val scenario: ActivityScenario<WebInputTestActivity>,
    ) {
        private var htmlAfterNewline = ""

        fun awaitPage() = poll("WebView のローカルページを読み込めません") {
            var loaded = false
            scenario.onActivity { loaded = it.pageLoaded }
            loaded
        }

        fun prepare(field: String, text: String, cursor: Int) {
            js("""(function(){const e=document.getElementById('$field');
                ${if (field == "textarea") "e.value='$text';" else "e.textContent='$text';"}
                return true})()""")
            val previousClicks = js("window.skkClicks").toInt()
            val rect = JSONObject(js("""(function(){const r=document.getElementById('$field').getBoundingClientRect();
                return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2})})()"""))
            var x = 0f
            var y = 0f
            scenario.onActivity { activity ->
                val location = IntArray(2)
                activity.webView.getLocationOnScreen(location)
                x = location[0] + rect.getDouble("x").toFloat() * activity.webView.scale
                y = location[1] + rect.getDouble("y").toFloat() * activity.webView.scale
            }
            val time = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(time, time + 30, MotionEvent.ACTION_UP, x, y, 0)
            instrumentation.sendPointerSync(down)
            instrumentation.sendPointerSync(up)
            down.recycle()
            up.recycle()
            poll("WebView の入力欄へのタップが反映されません: $field", {
                js("JSON.stringify({clicks:window.skkClicks,last:window.skkLastClick,active:document.activeElement.id})")
            }) {
                val click = JSONObject(js("""JSON.stringify({clicks:window.skkClicks,
                    last:window.skkLastClick,active:document.activeElement.id})"""))
                click.getInt("clicks") > previousClicks && click.getString("last") == field &&
                    click.getString("active") == field
            }
            poll("WebView の入力欄が IME に接続されません: $field") {
                var active = false
                scenario.onActivity { activity ->
                    active = activity.webView.hasFocus() &&
                        activity.getSystemService(InputMethodManager::class.java).isActive(activity.webView)
                }
                active
            }
            js(if (field == "textarea") {
                "document.getElementById('$field').setSelectionRange($cursor,$cursor);true"
            } else {
                """(function(){const e=document.getElementById('$field'),r=document.createRange();
                    r.setStart(e.firstChild,$cursor);r.collapse(true);const s=window.getSelection();
                    s.removeAllRanges();s.addRange(r);return true})()"""
            })
            await(field, text, cursor)
            var previousConnection = -1
            var restartAttempts = 0
            fun restartForPreparedCaret() {
                scenario.onActivity { activity ->
                    previousConnection = activity.connectionSerial
                    activity.getSystemService(InputMethodManager::class.java).restartInput(activity.webView)
                }
                restartAttempts++
            }
            restartForPreparedCaret()
            poll("WebView の新しい入力接続が準備されません: $field", {
                var detail = ""
                scenario.onActivity { activity ->
                    detail = "serial=${activity.connectionSerial}, " +
                        "monitoredSerial=${activity.monitoredConnectionSerial}, " +
                        "initialSelection=[${activity.connectionSelectionStart}, ${activity.connectionSelectionEnd}]"
                }
                "$detail; restartAttempts=$restartAttempts; dom=${snapshot(field)}"
            }) {
                var ready = false
                var newConnection = false
                var selectionMatches = false
                scenario.onActivity { activity ->
                    val currentSerial = activity.connectionSerial
                    newConnection = currentSerial > previousConnection
                    selectionMatches =
                        (activity.connectionSelectionStart < 0 || activity.connectionSelectionStart == cursor) &&
                        (activity.connectionSelectionEnd < 0 || activity.connectionSelectionEnd == cursor)
                    val imeProcessedConnection = activity.monitoredConnectionSerial == currentSerial
                    ready = newConnection && selectionMatches && imeProcessedConnection &&
                        activity.getSystemService(InputMethodManager::class.java).isActive(activity.webView)
                }
                val current = snapshot(field)
                val domMatches = current.getBoolean("focused") && current.getString("text") == text &&
                    current.getInt("start") == cursor && current.getInt("end") == cursor
                if (newConnection && !selectionMatches && domMatches && restartAttempts < 8) {
                    // WebView が古い EditorInfo を返したときだけ、設定中の接続を作り直します。
                    restartForPreparedCaret()
                }
                ready && domMatches
            }
        }

        fun key(code: Int) {
            val time = SystemClock.uptimeMillis()
            val meta = KeyEvent.META_CTRL_ON
            instrumentation.sendKeySync(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_KEYBOARD))
            instrumentation.sendKeySync(KeyEvent(time, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_KEYBOARD))
        }

        fun await(field: String, text: String, cursor: Int) = poll(
            "WebView 編集結果が一致しません: $field / $text / $cursor",
            { snapshot(field).toString() },
        ) {
            val value = snapshot(field)
            value.getString("text") == text && value.getInt("start") == cursor &&
                value.getInt("end") == cursor && value.getBoolean("focused")
        }

        fun awaitChangedHtml(field: String, previous: String) = poll(
            "contenteditable の C-m が改行を挿入しません",
            { snapshot(field).toString() },
        ) {
            val value = snapshot(field)
            val text = value.getString("text")
            val html = value.getString("html")
            val lineBreak = text.contains('\n') || value.getString("innerText").contains('\n') ||
                Regex("<(br|div|p)(?=[\\s>])", RegexOption.IGNORE_CASE).containsMatchIn(html)
            (lineBreak && html != previous && value.getBoolean("focused") && value.getBoolean("collapsed") &&
                value.getBoolean("within")).also {
                if (it) htmlAfterNewline = value.getString("html")
            }
        }

        fun awaitBackspaceAfterNewline(field: String) = poll(
            "contenteditable の C-m 後に編集を継続できません",
            { snapshot(field).toString() },
        ) {
            val value = snapshot(field)
            value.getString("html") != htmlAfterNewline && value.getString("text") == "a" &&
                value.getString("innerText") == "a" && value.getInt("logicalCursor") == 1 &&
                value.getBoolean("focused") && value.getBoolean("collapsed") && value.getBoolean("within")
        }

        fun snapshot(field: String): JSONObject = JSONObject(js("""(function(){
            const e=document.getElementById('$field'),s=window.getSelection(),r=s.rangeCount?s.getRangeAt(0):null;
            const start='$field'==='textarea'?e.selectionStart:(r&&r.startContainer===e.firstChild?r.startOffset:-1);
            const end='$field'==='textarea'?e.selectionEnd:(r&&r.endContainer===e.firstChild?r.endOffset:-1);
            const before=r&&e.contains(r.startContainer)?r.cloneRange():null;
            if(before){before.selectNodeContents(e);before.setEnd(r.startContainer,r.startOffset)}
            return JSON.stringify({text:'$field'==='textarea'?e.value:e.textContent,
                html:e.innerHTML,innerText:e.innerText,start:start,end:end,
                logicalCursor:before?before.toString().length:-1,
                focused:document.activeElement===e,collapsed:s?s.isCollapsed:false,
                within:s?e.contains(s.anchorNode):false,
                submits:window.submits})})()"""))

        private fun js(script: String): String {
            val latch = CountDownLatch(1)
            var result: String? = null
            scenario.onActivity { activity ->
                activity.webView.evaluateJavascript(script) { value -> result = value; latch.countDown() }
            }
            assertTrue("WebView の JavaScript が応答しません", latch.await(5, TimeUnit.SECONDS))
            return JSONTokener(checkNotNull(result)).nextValue().toString()
        }

        private fun poll(message: String, detail: () -> String = { "" }, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                SystemClock.sleep(50)
            }
            throw AssertionError("$message; actual=${detail()}")
        }
    }
}
