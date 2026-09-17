package jp.hayase.skk.testeditor

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import android.webkit.WebViewClient

/** ローカル HTML の入力欄で WebView 自身の InputConnection を試す画面です。 */
class WebInputTestActivity : Activity() {
    lateinit var webView: WebView
        private set
    var pageLoaded = false
        private set
    var connectionSerial = 0
        private set
    var connectionSelectionStart = -1
        private set
    var connectionSelectionEnd = -1
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = object : WebView(this) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
                super.onCreateInputConnection(outAttrs).also {
                    if (it != null) {
                        connectionSerial++
                        connectionSelectionStart = outAttrs.initialSelStart
                        connectionSelectionEnd = outAttrs.initialSelEnd
                    }
                }
        }.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageLoaded = true
                }
            }
            isFocusableInTouchMode = true
        }
        setContentView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        webView.loadDataWithBaseURL(null, HTML, "text/html", "UTF-8", null)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private val HTML = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{margin:20px;font:20px sans-serif}textarea,[contenteditable]{display:block;
            box-sizing:border-box;width:95%;height:100px;margin:12px 0;padding:8px;border:1px solid #555}</style>
            </head><body>
            <form id="form" onsubmit="window.submits++; return false">
              <textarea id="textarea" aria-label="textarea"></textarea>
              <div id="contenteditable" contenteditable="true" aria-label="contenteditable"></div>
            </form>
            <script>
              window.submits=0;
              window.skkClicks=0;
              window.skkLastClick='';
              document.addEventListener('click', function(event) {
                window.skkClicks++;
                window.skkLastClick=event.target.id;
              });
            </script>
            </body></html>
        """.trimIndent()
    }
}
