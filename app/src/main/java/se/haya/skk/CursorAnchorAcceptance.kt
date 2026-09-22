package se.haya.skk

import android.view.inputmethod.CursorAnchorInfo

/** 接続の世代確認後も、編集内容が一致しない遅延座標通知を配置へ使いません。 */
internal object CursorAnchorAcceptance {
    fun matches(info: CursorAnchorInfo?, selection: Pair<Int, Int>, composition: Pair<Int, String>?): Boolean {
        if (info == null || selection.first < 0 || selection.second < 0 ||
            info.selectionStart != selection.first || info.selectionEnd != selection.second) return false
        return if (composition == null) info.composingText.isNullOrEmpty()
        else info.composingTextStart == composition.first && info.composingText?.toString() == composition.second
    }
}
