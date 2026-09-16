package jp.hayase.skk.core.dictionary

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset
import java.util.LinkedHashMap

object SkkDictionaryCodec {
    const val MAX_FILE_BYTES: Int = 64 * 1024 * 1024
    const val MAX_TEXT_CHARS: Int = 64 * 1024 * 1024
    const val MAX_LINE_CHARS: Int = 1024 * 1024

    fun parse(
        bytes: ByteArray,
        encoding: SkkDictionaryEncoding = SkkDictionaryEncoding.AUTO,
    ): SkkDictionaryDocument {
        if (bytes.size > MAX_FILE_BYTES) fail(1, SkkDictionaryError.FILE_TOO_LARGE)
        val decoded = decode(bytes, encoding)
        return parseText(decoded.text, decoded.encoding)
    }

    fun parseText(
        text: String,
        encoding: SkkDictionaryEncoding = SkkDictionaryEncoding.UTF8,
    ): SkkDictionaryDocument {
        require(encoding != SkkDictionaryEncoding.AUTO)
        if (text.length > MAX_TEXT_CHARS) fail(1, SkkDictionaryError.FILE_TOO_LARGE)
        validateTextUnicode(text)
        val merged = LinkedHashMap<String, LinkedHashMap<CandidateIdentity, SkkDictionaryCandidate>>()
        var duplicates = 0
        var annotationConflicts = 0
        forEachLine(text) { lineNumber, line ->
            if (line.length > MAX_LINE_CHARS) fail(lineNumber, SkkDictionaryError.LINE_TOO_LONG)
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith(";;")) {
                val entry = parseLine(trimmed, lineNumber)
                val candidates = merged.getOrPut(entry.key) { LinkedHashMap() }
                for (candidate in entry.candidates) {
                    val identity = CandidateIdentity(candidate.text, candidate.okuriCondition)
                    val existing = candidates[identity]
                    if (existing == null) {
                        candidates[identity] = candidate
                        continue
                    }
                    duplicates++
                    if (existing.annotation == null && candidate.annotation != null) {
                        candidates[identity] = existing.copy(annotation = candidate.annotation)
                    } else if (
                        existing.annotation != null &&
                        candidate.annotation != null &&
                        existing.annotation != candidate.annotation
                    ) {
                        annotationConflicts++
                    }
                }
            }
        }
        return SkkDictionaryDocument(
            entries = merged.map { (key, candidates) -> SkkDictionaryEntry(key, candidates.values.toList()) },
            encoding = encoding,
            diagnostics = SkkDictionaryDiagnostics(duplicates, annotationConflicts),
        )
    }

    fun format(document: SkkDictionaryDocument): String = format(document.entries)

    fun format(entries: List<SkkDictionaryEntry>): String {
        val validated = entries.mapIndexed { index, entry ->
            validateEntry(entry, index + 1)
            entry
        }
        val comparator = Comparator<SkkDictionaryEntry> { left, right -> compareCodePoints(left.key, right.key) }
        val okuriAri = validated.filter { isOkuriAriKey(it.key) }.sortedWith(comparator)
        val okuriNasi = validated.filterNot { isOkuriAriKey(it.key) }.sortedWith(comparator)
        return buildString {
            append(";; -*- coding: utf-8 -*-\n")
            append(";; okuri-ari entries.\n")
            okuriAri.forEach { appendEntry(it) }
            append(";; okuri-nasi entries.\n")
            okuriNasi.forEach { appendEntry(it) }
        }
    }

    fun encodeUtf8(document: SkkDictionaryDocument): ByteArray =
        format(document).toByteArray(StandardCharsets.UTF_8)

    private fun parseLine(line: String, lineNumber: Int): SkkDictionaryEntry {
        val separator = line.indexOfFirst { it.isWhitespace() }
        if (separator <= 0) fail(lineNumber, SkkDictionaryError.INVALID_ENTRY)
        val key = line.substring(0, separator)
        validateKey(key, lineNumber)
        val candidateText = line.substring(separator).trim()
        if (!candidateText.startsWith('/') || !candidateText.endsWith('/')) {
            fail(lineNumber, SkkDictionaryError.INVALID_CANDIDATE_LIST)
        }
        val tokens = tokenizeCandidates(candidateText, lineNumber)
        if (tokens.isEmpty()) fail(lineNumber, SkkDictionaryError.INVALID_CANDIDATE_LIST)
        val candidates = mutableListOf<SkkDictionaryCandidate>()
        var tokenIndex = 0
        while (tokenIndex < tokens.size) {
            val token = tokens[tokenIndex]
            if (token.isEmpty()) fail(lineNumber, SkkDictionaryError.INVALID_CANDIDATE_LIST)
            if (token.startsWith("[")) {
                if (!isOkuriAriKey(key)) fail(lineNumber, SkkDictionaryError.INVALID_OKURI_BLOCK)
                val condition = token.substring(1)
                validateOkuriCondition(key, condition, lineNumber)
                tokenIndex++
                val block = mutableListOf<SkkDictionaryCandidate>()
                while (tokenIndex < tokens.size && tokens[tokenIndex] != "]") {
                    val value = tokens[tokenIndex]
                    if (value.isEmpty() || value.startsWith("[")) {
                        fail(lineNumber, SkkDictionaryError.INVALID_OKURI_BLOCK)
                    }
                    block += parseCandidate(value, condition, lineNumber)
                    tokenIndex++
                }
                if (block.isEmpty() || tokenIndex >= tokens.size) {
                    fail(lineNumber, SkkDictionaryError.INVALID_OKURI_BLOCK)
                }
                candidates += block
                tokenIndex++
            } else {
                if (token == "]") fail(lineNumber, SkkDictionaryError.INVALID_OKURI_BLOCK)
                candidates += parseCandidate(token, null, lineNumber)
                tokenIndex++
            }
        }
        if (candidates.isEmpty()) fail(lineNumber, SkkDictionaryError.INVALID_CANDIDATE_LIST)
        return SkkDictionaryEntry(key, candidates)
    }

    private fun tokenizeCandidates(text: String, lineNumber: Int): List<String> {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var inString = false
        var escaped = false
        var lastWasDelimiter = false
        for (index in 1 until text.length) {
            val char = text[index]
            if (inString) {
                token.append(char)
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                lastWasDelimiter = false
            } else {
                when (char) {
                    '"' -> {
                        inString = true
                        token.append(char)
                        lastWasDelimiter = false
                    }
                    '/' -> {
                        tokens += token.toString()
                        token.setLength(0)
                        lastWasDelimiter = true
                    }
                    else -> {
                        token.append(char)
                        lastWasDelimiter = false
                    }
                }
            }
        }
        if (inString || escaped || !lastWasDelimiter || token.isNotEmpty()) {
            fail(lineNumber, SkkDictionaryError.INVALID_CANDIDATE_LIST)
        }
        return tokens
    }

    private fun parseCandidate(
        token: String,
        okuriCondition: String?,
        lineNumber: Int,
    ): SkkDictionaryCandidate {
        val semicolon = findTopLevelSemicolon(token, lineNumber)
        val textAtom = if (semicolon < 0) token else token.substring(0, semicolon)
        var annotationAtom = if (semicolon < 0) null else token.substring(semicolon + 1)
        if (textAtom.isEmpty()) fail(lineNumber, SkkDictionaryError.INVALID_VALUE)
        val value = decodeAtom(textAtom, lineNumber)
        if (annotationAtom?.startsWith("*") == true) annotationAtom = annotationAtom.substring(1)
        val annotation = annotationAtom
            ?.takeIf { it.isNotEmpty() }
            ?.let { decodeAtom(it, lineNumber) }
            ?.takeIf { it.isNotEmpty() }
        validateValue(value, lineNumber)
        annotation?.let { validateValue(it, lineNumber) }
        return SkkDictionaryCandidate(value, annotation, okuriCondition)
    }

    private fun findTopLevelSemicolon(token: String, lineNumber: Int): Int {
        var inString = false
        var escaped = false
        for (index in token.indices) {
            val char = token[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    ';' -> return index
                }
            }
        }
        if (inString || escaped) fail(lineNumber, SkkDictionaryError.INVALID_VALUE)
        return -1
    }

    private fun decodeAtom(atom: String, lineNumber: Int): String {
        if (!atom.startsWith("(")) {
            if (atom.contains('"') || atom.startsWith("[") || atom == "]") {
                fail(lineNumber, SkkDictionaryError.INVALID_VALUE)
            }
            return atom
        }
        if (atom.startsWith("(concat") && !atom.endsWith(")")) {
            fail(lineNumber, SkkDictionaryError.UNSUPPORTED_EXPRESSION)
        }
        if (!atom.endsWith(")") || atom.length < 3 || atom[1].code > 0x7f) {
            if (atom.contains('"')) fail(lineNumber, SkkDictionaryError.INVALID_VALUE)
            return atom
        }
        var index = 1
        if (!atom.startsWith("concat", index)) fail(lineNumber, SkkDictionaryError.UNSUPPORTED_EXPRESSION)
        index += "concat".length
        if (index >= atom.length || atom[index] != ' ') {
            fail(lineNumber, SkkDictionaryError.UNSUPPORTED_EXPRESSION)
        }
        while (index < atom.length && atom[index] == ' ') index++
        if (index >= atom.length || atom[index] != '"') {
            fail(lineNumber, SkkDictionaryError.UNSUPPORTED_EXPRESSION)
        }
        index++
        val result = StringBuilder()
        while (index < atom.length) {
            val char = atom[index++]
            if (char == '"') {
                if (index != atom.length - 1 || atom[index] != ')') {
                    fail(lineNumber, SkkDictionaryError.UNSUPPORTED_EXPRESSION)
                }
                return result.toString()
            }
            if (char != '\\') {
                result.append(char)
                continue
            }
            if (index >= atom.length) fail(lineNumber, SkkDictionaryError.UNSUPPORTED_EXPRESSION)
            when (atom[index]) {
                '\\' -> {
                    result.append('\\')
                    index++
                }
                '"' -> {
                    result.append('"')
                    index++
                }
                '0' -> {
                    if (index + 2 >= atom.length) fail(lineNumber, SkkDictionaryError.UNSUPPORTED_EXPRESSION)
                    val escape = atom.substring(index, index + 3)
                    when (escape) {
                        "057" -> result.append('/')
                        "073" -> result.append(';')
                        else -> fail(lineNumber, SkkDictionaryError.UNSUPPORTED_EXPRESSION)
                    }
                    index += 3
                }
                else -> fail(lineNumber, SkkDictionaryError.UNSUPPORTED_EXPRESSION)
            }
        }
        fail(lineNumber, SkkDictionaryError.UNSUPPORTED_EXPRESSION)
    }

    private fun validateEntry(entry: SkkDictionaryEntry, lineNumber: Int) {
        validateKey(entry.key, lineNumber)
        if (entry.candidates.isEmpty()) fail(lineNumber, SkkDictionaryError.INVALID_CANDIDATE_LIST)
        entry.candidates.forEach { candidate ->
            validateValue(candidate.text, lineNumber)
            candidate.annotation?.let { validateValue(it, lineNumber) }
            candidate.okuriCondition?.let { validateOkuriCondition(entry.key, it, lineNumber) }
        }
    }

    private fun validateKey(key: String, lineNumber: Int) {
        if (key.isEmpty() || key.contains('/') || key.any { it.isWhitespace() }) {
            fail(lineNumber, SkkDictionaryError.INVALID_KEY)
        }
        validateValue(key, lineNumber)
        if (isOkuriAriKey(key) && key.length < 2) fail(lineNumber, SkkDictionaryError.INVALID_KEY)
    }

    private fun validateValue(value: String, lineNumber: Int) {
        if (value.isEmpty()) fail(lineNumber, SkkDictionaryError.INVALID_VALUE)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char.isHighSurrogate()) {
                if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                    fail(lineNumber, SkkDictionaryError.INVALID_VALUE)
                }
                index += 2
                continue
            }
            if (char.isLowSurrogate() || char.code in 0x00..0x1f || char.code == 0x7f) {
                fail(lineNumber, SkkDictionaryError.INVALID_VALUE)
            }
            index++
        }
    }

    private fun validateTextUnicode(text: String) {
        var index = 0
        var lineNumber = 1
        while (index < text.length) {
            val char = text[index]
            if (char.isHighSurrogate()) {
                if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) {
                    fail(lineNumber, SkkDictionaryError.INVALID_VALUE)
                }
                index += 2
                continue
            }
            if (char.isLowSurrogate()) fail(lineNumber, SkkDictionaryError.INVALID_VALUE)
            if (char.code in 0x00..0x1f && char != '\t' && char != '\r' && char != '\n') {
                fail(lineNumber, SkkDictionaryError.INVALID_VALUE)
            }
            if (char.code == 0x7f) fail(lineNumber, SkkDictionaryError.INVALID_VALUE)
            if (char == '\r') {
                lineNumber++
                if (index + 1 < text.length && text[index + 1] == '\n') index++
            } else if (char == '\n') {
                lineNumber++
            }
            index++
        }
    }

    private fun validateOkuriCondition(key: String, condition: String, lineNumber: Int) {
        if (condition.isEmpty() || !isOkuriAriKey(key)) {
            fail(lineNumber, SkkDictionaryError.INVALID_OKURI_BLOCK)
        }
        var index = 0
        while (index < condition.length) {
            val codePoint = condition.codePointAt(index)
            if (codePoint !in 0x3041..0x3096 && codePoint != 0x30fc) {
                fail(lineNumber, SkkDictionaryError.INVALID_OKURI_BLOCK)
            }
            index += Character.charCount(codePoint)
        }
        if (okuriAlphabet(condition) != key.last()) {
            fail(lineNumber, SkkDictionaryError.INVALID_OKURI_BLOCK)
        }
    }

    private fun okuriAlphabet(condition: String): Char? {
        var index = 0
        while (index < condition.length && condition.codePointAt(index) == 'っ'.code) {
            index += Character.charCount(condition.codePointAt(index))
        }
        if (index >= condition.length) return null
        return when (condition.codePointAt(index).toChar()) {
        'あ' -> 'a'
        'い' -> 'i'
        'う' -> 'u'
        'え' -> 'e'
        'お' -> 'o'
        in "かきくけこ" -> 'k'
        in "がぎぐげご" -> 'g'
        in "さしすせそ" -> 's'
        in "ざじずぜぞ" -> 'z'
        in "たちつてと" -> 't'
        in "だぢづでど" -> 'd'
        in "なにぬねのん" -> 'n'
        in "はひふへほ" -> 'h'
        in "ばびぶべぼ" -> 'b'
        in "ぱぴぷぺぽ" -> 'p'
        in "まみむめも" -> 'm'
        in "やゆよ" -> 'y'
        in "らりるれろ" -> 'r'
        in "わゐゑを" -> 'w'
        'ゔ' -> 'v'
        else -> null
        }
    }

    private fun StringBuilder.appendEntry(entry: SkkDictionaryEntry) {
        append(entry.key).append(" /")
        var index = 0
        while (index < entry.candidates.size) {
            val candidate = entry.candidates[index]
            val condition = candidate.okuriCondition
            if (condition == null) {
                append(formatCandidate(candidate)).append('/')
                index++
                continue
            }
            append('[').append(condition).append('/')
            while (
                index < entry.candidates.size &&
                entry.candidates[index].okuriCondition == condition
            ) {
                append(formatCandidate(entry.candidates[index])).append('/')
                index++
            }
            append(']').append('/')
        }
        append('\n')
    }

    private fun formatCandidate(candidate: SkkDictionaryCandidate): String {
        val text = encodeAtom(candidate.text, annotation = false)
        val annotation = candidate.annotation ?: return text
        if (annotation.isEmpty()) return text
        return "$text;${encodeAtom(annotation, annotation = true)}"
    }

    private fun encodeAtom(value: String, annotation: Boolean): String {
        val requiresExpression = value.any { it == '/' || it == '\\' || it == '"' } ||
            (!annotation && (value.contains(';') || value.startsWith("[") || value == "]")) ||
            (annotation && value.startsWith("*")) ||
            value.startsWith("(concat") ||
            (value.startsWith("(") && value.endsWith(")") && value.length > 1 && value[1].code <= 0x7f)
        if (!requiresExpression) return value
        return buildString {
            append("(concat \"")
            for (char in value) {
                when (char) {
                    '/' -> append("\\057")
                    ';' -> append("\\073")
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    else -> append(char)
                }
            }
            append("\")")
        }
    }

    private fun isOkuriAriKey(key: String): Boolean = key.lastOrNull() in 'a'..'z'

    private fun compareCodePoints(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftCodePoint = left.codePointAt(leftIndex)
            val rightCodePoint = right.codePointAt(rightIndex)
            if (leftCodePoint != rightCodePoint) return leftCodePoint.compareTo(rightCodePoint)
            leftIndex += Character.charCount(leftCodePoint)
            rightIndex += Character.charCount(rightCodePoint)
        }
        return (left.length - leftIndex).compareTo(right.length - rightIndex)
    }

    private fun decode(bytes: ByteArray, requested: SkkDictionaryEncoding): Decoded {
        return when (requested) {
            SkkDictionaryEncoding.UTF8 -> Decoded(decodeStrict(stripUtf8Bom(bytes), StandardCharsets.UTF_8), requested)
            SkkDictionaryEncoding.EUC_JP -> Decoded(decodeStrict(bytes, Charset.forName("EUC-JP")), requested)
            SkkDictionaryEncoding.AUTO -> {
                try {
                    Decoded(decodeStrict(stripUtf8Bom(bytes), StandardCharsets.UTF_8), SkkDictionaryEncoding.UTF8)
                } catch (_: SkkDictionaryFormatException) {
                    Decoded(decodeStrict(bytes, Charset.forName("EUC-JP")), SkkDictionaryEncoding.EUC_JP)
                }
            }
        }
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            fail(1, SkkDictionaryError.DECODING_FAILED)
        }
    }

    private fun stripUtf8Bom(bytes: ByteArray): ByteArray =
        if (
            bytes.size >= 3 &&
            bytes[0] == 0xef.toByte() &&
            bytes[1] == 0xbb.toByte() &&
            bytes[2] == 0xbf.toByte()
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }

    private inline fun forEachLine(text: String, action: (Int, String) -> Unit) {
        var start = 0
        var index = 0
        var lineNumber = 1
        while (index < text.length) {
            if (text[index] == '\n' || text[index] == '\r') {
                action(lineNumber++, text.substring(start, index))
                if (text[index] == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                start = index + 1
            }
            index++
        }
        action(lineNumber, text.substring(start))
    }

    private fun fail(lineNumber: Int, error: SkkDictionaryError): Nothing =
        throw SkkDictionaryFormatException(lineNumber, error)

    private data class Decoded(val text: String, val encoding: SkkDictionaryEncoding)
    private data class CandidateIdentity(val text: String, val okuriCondition: String?)
}
