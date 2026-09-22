package se.haya.skk.settings

import se.haya.skk.core.keys.KeyGesture
import se.haya.skk.core.keys.SpecialKey

/** 設定画面の入力表記です。文字の大小・空白自体をキーとして保持します。 */
object KeyGestureText {
    fun format(key: KeyGesture): String = buildString {
        if (key.ctrl) append("C-")
        if (key.alt) append("M-")
        if (key.shift) append("S-")
        if (key.ignoreShift) append("U-")
        append(key.special?.let { "<${it.name}>" } ?: if (key.text == " ") "<SPACE>" else key.text)
    }

    fun parse(value: String): KeyGesture {
        require(value.length <= 40) { "キー表記は40文字以内にします" }
        var rest = value
        val modifiers = mutableSetOf<Char>()
        while (rest.length >= 2 && rest[1] == '-' && rest[0] in "CMSU") {
            require(modifiers.add(rest[0])) { "同じ修飾キーは一度だけ指定します" }
            rest = rest.substring(2)
        }
        val special = if (rest.startsWith('<') && rest.endsWith('>') && rest != "<SPACE>") {
            SpecialKey.entries.firstOrNull { "<${it.name}>" == rest }
                ?: throw IllegalArgumentException("特殊キーの名前が不明です")
        } else null
        return KeyGesture(if (special != null) null else if (rest == "<SPACE>") " " else rest,
            special, 'C' in modifiers, 'M' in modifiers, 'S' in modifiers, 'U' in modifiers)
    }
}
