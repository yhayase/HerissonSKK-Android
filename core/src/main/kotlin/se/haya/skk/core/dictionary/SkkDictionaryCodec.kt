package se.haya.skk.core.dictionary

import se.haya.skk.core.date.DateExpression
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
    const val MAX_WARNING_SAMPLES: Int = 100

    fun parse(
        bytes: ByteArray,
        encoding: SkkDictionaryEncoding = SkkDictionaryEncoding.AUTO,
        policy: SkkDictionaryImportPolicy = SkkDictionaryImportPolicy.STRICT,
    ): SkkDictionaryDocument {
        if (bytes.size > MAX_FILE_BYTES) fail(1, SkkDictionaryError.FILE_TOO_LARGE)
        val decoded = decode(bytes, encoding)
        return parseText(decoded.text, decoded.encoding, policy)
    }

    fun parseText(
        text: String,
        encoding: SkkDictionaryEncoding = SkkDictionaryEncoding.UTF8,
        policy: SkkDictionaryImportPolicy = SkkDictionaryImportPolicy.STRICT,
    ): SkkDictionaryDocument {
        require(encoding != SkkDictionaryEncoding.AUTO)
        if (text.length > MAX_TEXT_CHARS) fail(1, SkkDictionaryError.FILE_TOO_LARGE)
        validateTextUnicode(text)
        val merged = LinkedHashMap<String, LinkedHashMap<CandidateIdentity, SkkDictionaryCandidate>>()
        val sections = LinkedHashMap<String, SkkDictionarySection>()
        var currentSection: SkkDictionarySection? = null
        var duplicates = 0
        var annotationConflicts = 0
        val warnings = WarningCollector()
        forEachLine(text) { lineNumber, line ->
            if (line.length > MAX_LINE_CHARS) fail(lineNumber, SkkDictionaryError.LINE_TOO_LONG)
            val trimmed = line.trim()
            when (trimmed) {
                ";; okuri-ari entries." -> currentSection = SkkDictionarySection.OKURI_ARI
                ";; okuri-nasi entries." -> currentSection = SkkDictionarySection.OKURI_NASI
            }
            if (trimmed.isNotEmpty() && !trimmed.startsWith(";;")) {
                val separator = trimmed.indexOfFirst { it.isWhitespace() }
                val hasHeadword = separator > 0 && trimmed.substring(separator).trimStart().startsWith('/')
                val emptyHeadword = line.firstOrNull()?.isWhitespace() == true && trimmed.startsWith('/') && !hasHeadword
                if (emptyHeadword && policy == SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED) {
                    // 空見出しでも候補列全体を検証し、破損した行を隠しません。
                    val entry = parseLine("__empty_headword__ $trimmed", lineNumber, policy, warnings)
                    warnings.add(lineNumber, SkkDictionaryWarningReason.EMPTY_HEADWORD, entry.candidates.size)
                    warnings.skippedEntryCount++
                    return@forEachLine
                }
                val entry = parseLine(trimmed, lineNumber, policy, warnings)
                if (entry.candidates.isEmpty()) {
                    warnings.skippedEntryCount++
                    return@forEachLine
                }
                sections.putIfAbsent(entry.key, currentSection ?: entry.section)
                val candidates = merged.getOrPut(entry.key) { LinkedHashMap() }
                for (candidate in entry.candidates) {
                    val identity = CandidateIdentity(candidate.text, candidate.okuriCondition, candidate.dateExpression)
                    val existing = candidates[identity]
                    if (existing == null) {
                        candidates[identity] = candidate
                        continue
                    }
                    duplicates++
                    if (existing.annotation == null && candidate.annotation != null) {
                        candidates[identity] = existing.copy(annotation = candidate.annotation, annotationIsPersonal = candidate.annotationIsPersonal)
                    } else if (
                        existing.annotation != null &&
                        candidate.annotation != null &&
                        (existing.annotation != candidate.annotation || existing.annotationIsPersonal != candidate.annotationIsPersonal)
                    ) {
                        annotationConflicts++
                    }
                }
            }
        }
        if (merged.isEmpty() && warnings.count > 0) fail(1, SkkDictionaryError.NO_SUPPORTED_ENTRIES)
        return SkkDictionaryDocument(
            entries = merged.map { (key, candidates) -> SkkDictionaryEntry(key, candidates.values.toList(), sections.getValue(key)) },
            encoding = encoding,
            diagnostics = SkkDictionaryDiagnostics(duplicates, annotationConflicts, warnings.snapshot()),
        )
    }

    /** 書き出し用の文字列を作らず、公開モデルの辞書値を検証します。 */
    fun validate(document: SkkDictionaryDocument) = validateEntries(document.entries)

    private fun validateEntries(entries: List<SkkDictionaryEntry>) {
        entries.forEachIndexed { index, entry -> validateEntry(entry, index + 1) }
    }

    fun format(document: SkkDictionaryDocument): String = format(document.entries)

    fun format(entries: List<SkkDictionaryEntry>): String {
        validateEntries(entries)
        val okuriAri = entries.filter { it.section == SkkDictionarySection.OKURI_ARI }
        val okuriNasi = entries.filter { it.section == SkkDictionarySection.OKURI_NASI }
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

    /** 完全バックアップでも通常の SKK 読み書きと同じ候補意味検証を使うための入口です。 */
    internal fun validateCandidateFields(
        entryKey: String,
        text: String,
        annotation: String?,
        okuriCondition: String?,
        annotationIsPersonal: Boolean = false,
        dateExpression: Boolean = false,
    ) {
        validateKey(entryKey, 1)
        validateValue(text, 1, allowLineBreaks = true)
        if (dateExpression && (text.any { it == '\n' || it == '\r' } || DateExpression.parse(text) == null)) fail(1, SkkDictionaryError.UNSUPPORTED_EXPRESSION)
        annotation?.let { validateValue(it, 1, allowLineBreaks = true) }
        if (annotationIsPersonal && annotation.isNullOrEmpty()) fail(1, SkkDictionaryError.INVALID_VALUE)
        okuriCondition?.let { validateOkuriCondition(it, 1) }
    }

    private fun parseLine(
        line: String,
        lineNumber: Int,
        policy: SkkDictionaryImportPolicy,
        warnings: WarningCollector,
    ): SkkDictionaryEntry {
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
                val condition = token.substring(1)
                validateOkuriCondition(condition, lineNumber)
                tokenIndex++
                val block = mutableListOf<SkkDictionaryCandidate>()
                var blockTokenCount = 0
                while (tokenIndex < tokens.size && tokens[tokenIndex] != "]") {
                    val value = tokens[tokenIndex]
                    if (value.isEmpty() || value.startsWith("[")) {
                        fail(lineNumber, SkkDictionaryError.INVALID_OKURI_BLOCK)
                    }
                    parseImportCandidate(value, condition, lineNumber, policy, warnings)?.let(block::add)
                    blockTokenCount++
                    tokenIndex++
                }
                if (blockTokenCount == 0 || tokenIndex >= tokens.size) {
                    fail(lineNumber, SkkDictionaryError.INVALID_OKURI_BLOCK)
                }
                candidates += block
                tokenIndex++
            } else {
                if (token == "]") fail(lineNumber, SkkDictionaryError.INVALID_OKURI_BLOCK)
                parseImportCandidate(token, null, lineNumber, policy, warnings)?.let(candidates::add)
                tokenIndex++
            }
        }
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

    private fun parseImportCandidate(
        token: String,
        okuriCondition: String?,
        lineNumber: Int,
        policy: SkkDictionaryImportPolicy,
        warnings: WarningCollector,
    ): SkkDictionaryCandidate? {
        val semicolon = findTopLevelSemicolon(token, lineNumber)
        val textAtom = if (semicolon < 0) token else token.substring(0, semicolon)
        var annotationAtom = if (semicolon < 0) null else token.substring(semicolon + 1)
        if (textAtom.isEmpty()) fail(lineNumber, SkkDictionaryError.INVALID_VALUE)
        val annotationIsPersonal = annotationAtom?.startsWith("*") == true
        if (annotationIsPersonal) annotationAtom = annotationAtom!!.substring(1)
        var reason: SkkDictionaryWarningReason? = null
        fun atomValue(atom: String, annotation: Boolean): String? {
            validateValue(atom, lineNumber)
            val known = if (policy == SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED) knownExpression(atom) else null
            if (known != null) {
                if (reason == null) reason = known
                return null
            }
            if (policy == SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED && hasKnownOperatorPrefix(atom)) {
                fail(lineNumber, SkkDictionaryError.UNSUPPORTED_EXPRESSION)
            }
            return decodeAtom(atom, lineNumber, annotation).also { if (!annotation || it.isNotEmpty()) validateValue(it, lineNumber, allowLineBreaks = true) }
        }
        validateValue(textAtom, lineNumber)
        val dateExpression = DateExpression.parse(textAtom) != null
        val value = if (dateExpression) textAtom else atomValue(textAtom, false)
        val annotation = annotationAtom?.takeIf { it.isNotEmpty() }?.let { atomValue(it, true) }
        reason?.let {
            warnings.add(lineNumber, it, 1)
            return null
        }
        val nonemptyAnnotation = annotation?.takeIf { it.isNotEmpty() }
        return SkkDictionaryCandidate(requireNotNull(value), nonemptyAnnotation, okuriCondition, annotationIsPersonal && nonemptyAnnotation != null, dateExpression)
    }

    // DDSKK の既知の形式だけを識別します。式は評価せず、未知の式や壊れた concat は従来どおり拒否します。
    private fun hasKnownOperatorPrefix(atom: String): Boolean =
        listOf("skk-current-date", "skk-relative-date", "skk-ignore-dic-word").any { operator ->
            val prefix = "($operator"
            atom.startsWith(prefix) && (atom.length == prefix.length || atom.getOrNull(prefix.length) in listOf(' ', ')'))
        }

    private fun knownExpression(atom: String): SkkDictionaryWarningReason? {
        if (!hasKnownOperatorPrefix(atom)) return null
        val relative = atom.startsWith("(skk-relative-date")
        val date = relative || atom.startsWith("(skk-current-date")
        val operator = if (relative) "skk-relative-date" else if (date) "skk-current-date" else "skk-ignore-dic-word"
        val reader = KnownExpressionReader(atom, operator)
        if (!reader.readArguments(date, relative)) return null
        return if (date) SkkDictionaryWarningReason.UNSUPPORTED_DATE_EXPRESSION
        else SkkDictionaryWarningReason.UNSUPPORTED_SUPPRESSION_EXPRESSION
    }

    /** 既知の式の構造だけを確認します。値の評価や元の引数の保持は行いません。 */
    private class KnownExpressionReader(private val text: String, operator: String) {
        private var index = operator.length + 1
        private var nodes = 0

        fun readArguments(date: Boolean, relative: Boolean): Boolean {
            var arguments = 0
            var relativeKey: String? = null
            while (index < text.length && text[index] != ')') {
                if (text[index] != ' ') return false
                skipSpaces()
                if (text.getOrNull(index) == ')') break
                if (date) {
                    if (!relative && arguments >= 3) return false
                    val valid = when (arguments) {
                        0 -> readFunctionArgument()
                        1 -> if (text.getOrNull(index) == '"') readString() >= 0 else readSymbol() == "nil"
                        2 -> readSymbol() in listOf("nil", "t")
                        else -> {
                            if (++nodes > 4096) return false
                            if (arguments % 2 == 1) {
                                relativeKey = readSymbol()
                                relativeKey in listOf(":yy", ":mm", ":dd")
                            } else readRelativeOffset(relativeKey)
                        }
                    }
                    if (!valid) return false
                } else {
                    if (readString() <= 0) return false
                }
                arguments++
            }
            return index == text.lastIndex && text.getOrNull(index) == ')' && (if (relative) arguments >= 3 && arguments % 2 == 1 else date || arguments > 0)
        }

        private fun readRelativeOffset(key: String?): Boolean {
            val value = readSymbol() ?: return false
            if (!value.matches(Regex("[+-]?[0-9]+"))) return false
            val offset = value.toIntOrNull() ?: return false
            return key != ":dd" || offset in -DateExpression.MAX_DAY_OFFSET..DateExpression.MAX_DAY_OFFSET
        }

        private fun readFunctionArgument(): Boolean = when {
            text.startsWith("#'", index) -> { index += 2; readFunctionDatum() }
            text.getOrNull(index) == '\'' -> { index++; readDatum(0) }
            text.getOrNull(index) == '(' -> readLambda()
            else -> readSymbol() == "nil"
        }

        private fun readFunctionDatum(): Boolean =
            if (text.getOrNull(index) == '(') readLambda() else readSymbol() != null

        private fun readLambda(): Boolean {
            if (!text.startsWith("(lambda ", index)) return false
            index += "(lambda".length
            skipSpaces()
            if (text.getOrNull(index) == '(') {
                if (!readDatum(0)) return false
            } else if (readSymbol() != "nil") return false
            var bodies = 0
            while (index < text.length && text[index] != ')') {
                skipSpaces()
                if (text.getOrNull(index) == ')') break
                if (!readDatum(0)) return false
                bodies++
            }
            if (bodies == 0 || text.getOrNull(index) != ')') return false
            index++
            return true
        }

        private fun readDatum(depth: Int): Boolean {
            if (depth > 64 || ++nodes > 4096) return false
            return when (text.getOrNull(index)) {
                '(' -> {
                    index++
                    skipSpaces()
                    while (index < text.length && text[index] != ')') {
                        if (!readDatum(depth + 1)) return false
                        skipSpaces()
                    }
                    if (text.getOrNull(index) != ')') return false
                    index++
                    true
                }
                '\'' -> { index++; readDatum(depth + 1) }
                '"' -> readString() >= 0
                '#' -> {
                    if (!text.startsWith("#'", index)) return false
                    index += 2
                    readDatum(depth + 1)
                }
                else -> readSymbol() != null
            }
        }

        private fun readSymbol(): String? {
            val start = index
            while (index < text.length && text[index] !in " ()\"'\\#`,;[]") index++
            if (index == start) return null
            return text.substring(start, index).takeIf { it != "." }
        }

        private fun readString(): Int {
            if (text.getOrNull(index) != '"') return -1
            index++
            var characters = 0
            while (index < text.length) {
                val char = text[index++]
                if (char == '"') return characters
                if (char == '\\') {
                    when (text.getOrNull(index)) {
                        '"', '\\', 'n', 'r' -> index++
                        '0' -> {
                            if (index + 3 > text.length || text.substring(index, index + 3) !in listOf("057", "073")) return -1
                            index += 3
                        }
                        else -> return -1
                    }
                }
                characters++
            }
            return -1
        }

        private fun skipSpaces() {
            while (text.getOrNull(index) == ' ') index++
        }
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

    private fun decodeAtom(atom: String, lineNumber: Int, annotation: Boolean): String {
        if (!atom.startsWith("(")) {
            if (!annotation && (atom.contains('"') || atom.startsWith("[") || atom == "]")) {
                fail(lineNumber, SkkDictionaryError.INVALID_VALUE)
            }
            return atom
        }
        if (atom.startsWith("(concat") && !atom.endsWith(")")) {
            fail(lineNumber, SkkDictionaryError.UNSUPPORTED_EXPRESSION)
        }
        if (!atom.endsWith(")") || atom.length < 3 || atom[1].code > 0x7f ||
            !atom.startsWith("concat", 1)
        ) {
            if (atom.contains('"')) fail(lineNumber, SkkDictionaryError.UNSUPPORTED_EXPRESSION)
            if (!annotation && atom.endsWith(")") && atom.length >= 3 && atom[1].code <= 0x7f) {
                fail(lineNumber, SkkDictionaryError.UNSUPPORTED_EXPRESSION)
            }
            return atom
        }
        var index = 1
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
                'n' -> {
                    result.append('\n')
                    index++
                }
                'r' -> {
                    result.append('\r')
                    index++
                }
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
                        "012" -> result.append('\n')
                        "015" -> result.append('\r')
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
            validateValue(candidate.text, lineNumber, allowLineBreaks = true)
            if (candidate.dateExpression && (candidate.text.any { it == '\n' || it == '\r' } || DateExpression.parse(candidate.text) == null)) fail(lineNumber, SkkDictionaryError.UNSUPPORTED_EXPRESSION)
            candidate.annotation?.takeIf { it.isNotEmpty() }?.let { validateValue(it, lineNumber, allowLineBreaks = true) }
            if (candidate.annotationIsPersonal && candidate.annotation.isNullOrEmpty()) fail(lineNumber, SkkDictionaryError.INVALID_VALUE)
            candidate.okuriCondition?.let { validateOkuriCondition(it, lineNumber) }
        }
    }

    private fun validateKey(key: String, lineNumber: Int) {
        if (key.isEmpty() || key.any { it.isWhitespace() }) {
            fail(lineNumber, SkkDictionaryError.INVALID_KEY)
        }
        validateValue(key, lineNumber)
    }

    private fun validateValue(value: String, lineNumber: Int, allowLineBreaks: Boolean = false) {
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
            if (char.isLowSurrogate() || (char.code in 0x00..0x1f && !(allowLineBreaks && char in "\n\r")) || char.code == 0x7f) {
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

    private fun validateOkuriCondition(condition: String, lineNumber: Int) {
        // 送り条件は DDSKK のブロック照合と同様に文字列として保持します。
        // 区切りや引用と衝突する文字は、このブロック構文では往復できません。
        if (condition.isEmpty() || condition.any { it == '/' || it == '[' || it == ']' || it == '"' }) {
            fail(lineNumber, SkkDictionaryError.INVALID_OKURI_BLOCK)
        }
        validateValue(condition, lineNumber)
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
        val text = if (candidate.dateExpression) candidate.text else encodeAtom(candidate.text, annotation = false)
        val annotation = candidate.annotation ?: return text
        if (annotation.isEmpty()) return text
        return "$text;${if (candidate.annotationIsPersonal) "*" else ""}${encodeAtom(annotation, annotation = true)}"
    }

    private fun encodeAtom(value: String, annotation: Boolean): String {
        val requiresExpression = value.any { it == '/' || it == '\\' || it == '"' || it == '\n' || it == '\r' } ||
            (!annotation && (value.contains(';') || value.startsWith("[") || value == "]")) ||
            (annotation && value.startsWith("*")) ||
            value.startsWith("(concat") || hasKnownOperatorPrefix(value) ||
            (value.startsWith("(") && value.endsWith(")") && value.length > 1 && value[1].code <= 0x7f)
        if (!requiresExpression) return value
        return buildString {
            append("(concat \"")
            for (char in value) {
                when (char) {
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
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

    private fun decode(bytes: ByteArray, requested: SkkDictionaryEncoding): Decoded {
        return when (requested) {
            SkkDictionaryEncoding.UTF8 -> Decoded(decodeStrict(stripUtf8Bom(bytes), StandardCharsets.UTF_8), requested)
            SkkDictionaryEncoding.EUC_JP -> Decoded(decodeStrict(bytes, Charset.forName("EUC-JP")), requested)
            SkkDictionaryEncoding.EUC_JIS_2004 -> Decoded(decodeEucJis2004(bytes), requested)
            SkkDictionaryEncoding.AUTO -> {
                try {
                    Decoded(decodeStrict(stripUtf8Bom(bytes), StandardCharsets.UTF_8), SkkDictionaryEncoding.UTF8)
                } catch (_: SkkDictionaryFormatException) {
                    try {
                        Decoded(decodeStrict(bytes, Charset.forName("EUC-JP")), SkkDictionaryEncoding.EUC_JP)
                    } catch (_: SkkDictionaryFormatException) {
                        Decoded(decodeEucJis2004(bytes), SkkDictionaryEncoding.EUC_JIS_2004)
                    }
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

    private fun decodeEucJis2004(bytes: ByteArray): String = try {
        EucJis2004Decoder.decode(bytes)
    } catch (_: CharacterCodingException) {
        fail(1, SkkDictionaryError.DECODING_FAILED)
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

    private class WarningCollector {
        var skippedCandidateCount = 0
        var skippedEntryCount = 0
        val counts = java.util.EnumMap<SkkDictionaryWarningReason, Int>(SkkDictionaryWarningReason::class.java)
        val samples = ArrayList<SkkDictionaryWarning>()
        val count: Int get() = counts.values.sum()

        fun add(lineNumber: Int, reason: SkkDictionaryWarningReason, candidateCount: Int) {
            skippedCandidateCount += candidateCount
            counts[reason] = (counts[reason] ?: 0) + 1
            if (samples.size < MAX_WARNING_SAMPLES) samples += SkkDictionaryWarning(lineNumber, reason)
        }

        fun snapshot() = SkkDictionaryWarnings(skippedCandidateCount, skippedEntryCount, counts, samples)
    }

    private data class Decoded(val text: String, val encoding: SkkDictionaryEncoding)
    private data class CandidateIdentity(val text: String, val okuriCondition: String?, val dateExpression: Boolean)
}
