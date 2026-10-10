package se.haya.skk.core.dictionary

import java.nio.charset.MalformedInputException

/** 実行環境の Charset に依存せず、JIS X 0213:2004 の割当位置だけを復号します。 */
internal object EucJis2004Decoder {
    private const val ROW_WIDTH = 94
    private const val PLANE_SIZE = ROW_WIDTH * ROW_WIDTH
    private const val TABLE_PATH = "/se/haya/skk/core/dictionary/euc-jis-2004-std.txt"

    private class Tables(val plane1: Array<String?>, val plane2: Array<String?>)

    private val tables: Tables by lazy { loadTables() }

    fun decode(bytes: ByteArray): String {
        if (bytes.size > SkkDictionaryCodec.MAX_FILE_BYTES) malformed(1)
        val output = StringBuilder(minOf(bytes.size, 8192))
        var index = 0
        while (index < bytes.size) {
            val lead = unsigned(bytes[index])
            if (lead <= 0x7f) {
                if (output.length >= SkkDictionaryCodec.MAX_TEXT_CHARS) malformed(1)
                output.append(lead.toChar())
                index++
                continue
            }
            val width: Int
            val value: String
            when (lead) {
                0x8e -> {
                    width = 2
                    requireAvailable(bytes, index, width)
                    val trail = unsigned(bytes[index + 1])
                    if (trail !in 0xa1..0xdf) malformed(width)
                    value = (0xff61 + trail - 0xa1).toChar().toString()
                }
                in 0xa1..0xfe -> {
                    width = 2
                    requireAvailable(bytes, index, width)
                    val trail = unsigned(bytes[index + 1])
                    if (trail !in 0xa1..0xfe) malformed(width)
                    value = tables.plane1[cellIndex(lead, trail)] ?: malformed(width)
                }
                0x8f -> {
                    width = 3
                    requireAvailable(bytes, index, width)
                    val row = unsigned(bytes[index + 1])
                    val trail = unsigned(bytes[index + 2])
                    if (row !in 0xa1..0xfe || trail !in 0xa1..0xfe) malformed(width)
                    value = tables.plane2[cellIndex(row, trail)] ?: malformed(width)
                }
                else -> malformed(1)
            }
            if (output.length > SkkDictionaryCodec.MAX_TEXT_CHARS - value.length) malformed(width)
            output.append(value)
            index += width
        }
        return output.toString()
    }

    private fun requireAvailable(bytes: ByteArray, index: Int, width: Int) {
        if (bytes.size - index < width) malformed(bytes.size - index)
    }

    private fun unsigned(byte: Byte): Int = byte.toInt() and 0xff

    private fun cellIndex(row: Int, column: Int): Int = (row - 0xa1) * ROW_WIDTH + column - 0xa1

    private fun malformed(length: Int): Nothing = throw MalformedInputException(length)

    private fun loadTables(): Tables {
        val plane1 = arrayOfNulls<String>(PLANE_SIZE)
        val plane2 = arrayOfNulls<String>(PLANE_SIZE)
        val stream = checkNotNull(EucJis2004Decoder::class.java.getResourceAsStream(TABLE_PATH)) {
            "EUC-JIS-2004 の対応表が同梱されていません。"
        }
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.startsWith("0x") }.forEach { line ->
                val fields = line.split('\t', limit = 3)
                check(fields.size >= 2) { "EUC-JIS-2004 の対応表の形式が不正です。" }
                if (!fields[1].startsWith("U+")) return@forEach
                val code = fields[0].substring(2).toInt(16)
                val target = when (code) {
                    in 0xa1a1..0xfefe -> plane1
                    in 0x8fa1a1..0x8ffefe -> plane2
                    else -> return@forEach
                }
                val row = (code shr 8) and 0xff
                val column = code and 0xff
                check(row in 0xa1..0xfe && column in 0xa1..0xfe) { "EUC-JIS-2004 の対応位置が不正です。" }
                val value = buildString {
                    for (point in fields[1].substring(2).split('+')) {
                        val codePoint = point.toInt(16)
                        check(Character.isValidCodePoint(codePoint) && codePoint !in 0xd800..0xdfff) {
                            "EUC-JIS-2004 の Unicode 対応位置が不正です。"
                        }
                        appendCodePoint(codePoint)
                    }
                }
                // この原表では UTF-16 長が入力バイト数を超えず、辞書のサイズ上限を維持できます。
                check(value.isNotEmpty() && value.length <= if (target === plane1) 2 else 3) {
                    "EUC-JIS-2004 の対応文字列の長さが不正です。"
                }
                val cell = cellIndex(row, column)
                check(target[cell] == null) { "EUC-JIS-2004 の対応位置が重複しています。" }
                target[cell] = value
            }
        }
        check(plane1.count { it != null } == 8797 && plane2.count { it != null } == 2436) {
            "EUC-JIS-2004 の対応表が欠けています。"
        }
        return Tables(plane1, plane2)
    }
}
