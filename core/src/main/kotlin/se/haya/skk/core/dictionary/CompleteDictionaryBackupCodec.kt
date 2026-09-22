package se.haya.skk.core.dictionary

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

/**
 * 完全辞書バックアップの構文・意味・並び順をストリームで検証します。
 *
 * [recordConsumer] は検証済みレコードを順番に受け取り、候補同一性の重複を一意制約などで
 * 全件検証します。このコーデック単体の成功は構文上の妥当性を表し、復元可能な検証済み
 * ハンドルの作成には consumer 側の全件検証成功も必要です。
 */
class CompleteDictionaryBackupCodec(
    private val limits: CompleteDictionaryBackupLimits = CompleteDictionaryBackupLimits(),
) {
    fun write(records: Sequence<BackupRecord>, output: OutputStream): BackupSummary {
        val validator = RecordValidator(limits)
        var totalBytes = 0L
        var version: Int? = null
        records.forEach { record ->
            val recordNumber = validator.nextRecordNumber
            validator.accept(record)
            if (record is BackupRecord.Header) version = record.version
            val bytes = (encode(record, version) + "\n").toByteArray(StandardCharsets.UTF_8)
            if (bytes.size - 1 > limits.maxLineBytes) fail(recordNumber, CompleteDictionaryBackupError.LIMIT_EXCEEDED)
            if (totalBytes > limits.maxFileBytes - bytes.size) {
                fail(recordNumber, CompleteDictionaryBackupError.LIMIT_EXCEEDED)
            }
            output.write(bytes)
            totalBytes += bytes.size
        }
        return validator.finish()
    }

    fun validate(
        input: InputStream,
        recordConsumer: (BackupRecord) -> Unit,
    ): BackupSummary {
        val validator = RecordValidator(limits)
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        val line = ByteArrayOutputStream(minOf(limits.maxLineBytes, 8192))
        var totalBytes = 0L
        var version: Int? = null
        while (true) {
            val next = buffered.read()
            if (next < 0) {
                if (line.size() != 0) fail(validator.nextRecordNumber, CompleteDictionaryBackupError.INCOMPLETE)
                break
            }
            totalBytes++
            if (totalBytes > limits.maxFileBytes) {
                fail(validator.nextRecordNumber, CompleteDictionaryBackupError.LIMIT_EXCEEDED)
            }
            if (next == '\r'.code) fail(validator.nextRecordNumber, CompleteDictionaryBackupError.INVALID_JSON)
            if (next == '\n'.code) {
                val record = decodeRecord(
                    decodeUtf8(line.toByteArray(), validator.nextRecordNumber),
                    validator.nextRecordNumber,
                    version,
                )
                if (record is BackupRecord.Header) version = record.version
                validator.accept(record)
                recordConsumer(record)
                line.reset()
            } else {
                if (line.size() >= limits.maxLineBytes) {
                    fail(validator.nextRecordNumber, CompleteDictionaryBackupError.LIMIT_EXCEEDED)
                }
                line.write(next)
            }
        }
        return validator.finish()
    }

    private fun decodeUtf8(bytes: ByteArray, recordNumber: Long): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        fail(recordNumber, CompleteDictionaryBackupError.INVALID_UTF8)
    }

    private fun decodeRecord(line: String, recordNumber: Long, version: Int?): BackupRecord {
        val fields = JsonObjectParser(line, recordNumber, limits.maxStringChars).parse()
        val type = fields.string("type", recordNumber)
        return when (type) {
            "header" -> {
                fields.requireExactly(recordNumber, "type", "format", "version", "producerVersion")
                BackupRecord.Header(
                    fields.string("format", recordNumber),
                    fields.int("version", recordNumber),
                    fields.string("producerVersion", recordNumber),
                )
            }
            "source" -> {
                if (version == 1) {
                    fields.requireExactly(recordNumber, "type", "id", "name", "kind", "generation", "enabled", "order")
                } else {
                    fields.requireExactly(recordNumber, "type", "id", "name", "kind", "generation", "enabled", "order", "originUrl")
                }
                val kind = when (fields.string("kind", recordNumber)) {
                    "personal" -> BackupSourceKind.PERSONAL
                    "system" -> BackupSourceKind.SYSTEM
                    else -> fail(recordNumber, CompleteDictionaryBackupError.INVALID_VALUE)
                }
                BackupRecord.Source(
                    fields.string("id", recordNumber),
                    fields.string("name", recordNumber),
                    kind,
                    fields.long("generation", recordNumber),
                    fields.boolean("enabled", recordNumber),
                    fields.int("order", recordNumber),
                    if (version == 1) null else fields.nullableString("originUrl", recordNumber),
                )
            }
            "candidate" -> {
                fields.requireExactly(recordNumber, "type", "sourceId", "entryKey", "ordinal", "text", "annotation", "okuriCondition")
                BackupRecord.Candidate(
                    fields.string("sourceId", recordNumber),
                    fields.string("entryKey", recordNumber),
                    fields.int("ordinal", recordNumber),
                    fields.string("text", recordNumber),
                    fields.nullableString("annotation", recordNumber),
                    fields.nullableString("okuriCondition", recordNumber),
                )
            }
            "suppression" -> {
                fields.requireExactly(recordNumber, "type", "sourceId", "entryKey", "templateText", "okuriCondition")
                BackupRecord.Suppression(
                    fields.string("sourceId", recordNumber),
                    fields.string("entryKey", recordNumber),
                    fields.string("templateText", recordNumber),
                    fields.nullableString("okuriCondition", recordNumber),
                )
            }
            "sourceVersion" -> {
                fields.requireExactly(recordNumber, "type", "sourceId", "lastGeneration")
                BackupRecord.SourceVersion(
                    fields.string("sourceId", recordNumber),
                    fields.long("lastGeneration", recordNumber),
                )
            }
            "usage" -> {
                if (version != 3) fail(recordNumber, CompleteDictionaryBackupError.UNKNOWN_RECORD_TYPE)
                fields.requireExactly(recordNumber, "type", "readingKey", "templateText", "okuriCondition", "committedText", "lastUsedSequence")
                BackupRecord.Usage(
                    fields.string("readingKey", recordNumber),
                    fields.string("templateText", recordNumber),
                    fields.nullableString("okuriCondition", recordNumber),
                    fields.string("committedText", recordNumber),
                    fields.long("lastUsedSequence", recordNumber),
                )
            }
            "end" -> {
                if (version == 3) fields.requireExactly(recordNumber, "type", "sourceCount", "candidateCount", "suppressionCount", "sourceVersionCount", "usageCount")
                else fields.requireExactly(recordNumber, "type", "sourceCount", "candidateCount", "suppressionCount", "sourceVersionCount")
                BackupRecord.End(
                    fields.int("sourceCount", recordNumber),
                    fields.int("candidateCount", recordNumber),
                    fields.int("suppressionCount", recordNumber),
                    fields.int("sourceVersionCount", recordNumber),
                    if (version == 3) fields.int("usageCount", recordNumber) else 0,
                )
            }
            else -> fail(recordNumber, CompleteDictionaryBackupError.UNKNOWN_RECORD_TYPE)
        }
    }

    private fun encode(record: BackupRecord, version: Int?): String = buildString {
        append('{')
        when (record) {
            is BackupRecord.Header -> {
                field("type", "header"); field("format", record.format); number("version", record.version); field("producerVersion", record.producerVersion)
            }
            is BackupRecord.Source -> {
                field("type", "source"); field("id", record.id); field("name", record.name); field("kind", record.kind.serializedName)
                number("generation", record.generation); bool("enabled", record.enabled); number("order", record.order)
                if (version != 1) nullableField("originUrl", record.originUrl)
            }
            is BackupRecord.Candidate -> {
                field("type", "candidate"); field("sourceId", record.sourceId); field("entryKey", record.entryKey); number("ordinal", record.ordinal)
                field("text", record.text); nullableField("annotation", record.annotation); nullableField("okuriCondition", record.okuriCondition)
            }
            is BackupRecord.Suppression -> {
                field("type", "suppression"); field("sourceId", record.sourceId); field("entryKey", record.entryKey)
                field("templateText", record.templateText); nullableField("okuriCondition", record.okuriCondition)
            }
            is BackupRecord.SourceVersion -> {
                field("type", "sourceVersion"); field("sourceId", record.sourceId); number("lastGeneration", record.lastGeneration)
            }
            is BackupRecord.Usage -> {
                field("type", "usage"); field("readingKey", record.readingKey); field("templateText", record.templateText)
                nullableField("okuriCondition", record.okuriCondition); field("committedText", record.committedText)
                number("lastUsedSequence", record.lastUsedSequence)
            }
            is BackupRecord.End -> {
                field("type", "end"); number("sourceCount", record.sourceCount); number("candidateCount", record.candidateCount)
                number("suppressionCount", record.suppressionCount); number("sourceVersionCount", record.sourceVersionCount)
                if (version == 3) number("usageCount", record.usageCount)
            }
        }
        append('}')
    }

    private fun StringBuilder.prefix(name: String) {
        if (length > 1) append(',')
        appendJsonString(name)
        append(':')
    }

    private fun StringBuilder.field(name: String, value: String) { prefix(name); appendJsonString(value) }
    private fun StringBuilder.nullableField(name: String, value: String?) { prefix(name); if (value == null) append("null") else appendJsonString(value) }
    private fun StringBuilder.number(name: String, value: Number) { prefix(name); append(value) }
    private fun StringBuilder.bool(name: String, value: Boolean) { prefix(name); append(value) }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}

private class RecordValidator(private val limits: CompleteDictionaryBackupLimits) {
    private enum class Section { START, SOURCES, CANDIDATES, SUPPRESSIONS, VERSIONS, USAGES, END }
    private data class SourceState(val index: Int, val generation: Long, var versionSeen: Boolean = false)

    private var section = Section.START
    private var recordNumber = 0L
    private var producerVersion: String? = null
    private var formatVersion: Int? = null
    private val sources = LinkedHashMap<String, SourceState>()
    private var systemOrder = 0
    private var candidateCount = 0
    private var suppressionCount = 0
    private var sourceVersionCount = 0
    private var usageCount = 0
    private var previousCandidateSource = -1
    private var previousCandidateKey: String? = null
    private var nextOrdinal = 0
    private var previousSuppression: List<String?>? = null
    private var previousVersionId: String? = null
    private var previousUsage: List<String?>? = null
    private var summary: BackupSummary? = null

    val nextRecordNumber: Long get() = recordNumber + 1

    fun accept(record: BackupRecord) {
        recordNumber++
        when (record) {
            is BackupRecord.Header -> acceptHeader(record)
            is BackupRecord.Source -> acceptSource(record)
            is BackupRecord.Candidate -> acceptCandidate(record)
            is BackupRecord.Suppression -> acceptSuppression(record)
            is BackupRecord.SourceVersion -> acceptVersion(record)
            is BackupRecord.Usage -> acceptUsage(record)
            is BackupRecord.End -> acceptEnd(record)
        }
    }

    fun finish(): BackupSummary = summary ?: fail(nextRecordNumber, CompleteDictionaryBackupError.INCOMPLETE)

    private fun acceptHeader(record: BackupRecord.Header) {
        requireSection(Section.START)
        if (record.format != COMPLETE_DICTIONARY_BACKUP_FORMAT) invalid()
        if (record.version !in 1..COMPLETE_DICTIONARY_BACKUP_VERSION) fail(recordNumber, CompleteDictionaryBackupError.UNSUPPORTED_VERSION)
        validateIdentifier(record.producerVersion)
        producerVersion = record.producerVersion
        formatVersion = record.version
        section = Section.SOURCES
    }

    private fun acceptSource(record: BackupRecord.Source) {
        requireSection(Section.SOURCES)
        validateIdentifier(record.id)
        validateIdentifier(record.name)
        record.originUrl?.let {
            if (formatVersion == 1 || it.length > limits.maxStringChars || !isValidStoredOrigin(it)) invalid()
        }
        if (record.generation < 0) invalid()
        if (sources.size >= limits.maxSourceCount) limit()
        if (sources.containsKey(record.id)) fail(recordNumber, CompleteDictionaryBackupError.DUPLICATE_IDENTITY)
        if (sources.isEmpty()) {
            if (record.id != "personal" || record.kind != BackupSourceKind.PERSONAL || !record.enabled || record.order != 0) invalid()
        } else {
            if (record.kind != BackupSourceKind.SYSTEM || record.id == "personal" || record.order != systemOrder) invalid()
            systemOrder++
        }
        sources[record.id] = SourceState(sources.size, record.generation)
    }

    private fun acceptCandidate(record: BackupRecord.Candidate) {
        moveTo(Section.CANDIDATES)
        if (candidateCount >= limits.maxCandidateCount) limit()
        validateDictionaryValue(record.entryKey, record.text, record.annotation, record.okuriCondition)
        validateIdentifier(record.sourceId)
        val source = sources[record.sourceId] ?: invalid()
        val keyComparison = when {
            source.index < previousCandidateSource -> -1
            source.index > previousCandidateSource -> 1
            previousCandidateKey == null -> 1
            else -> compareCodePoints(record.entryKey, previousCandidateKey!!)
        }
        if (keyComparison < 0) order()
        if (source.index != previousCandidateSource || keyComparison > 0) {
            if (record.ordinal != 0) order()
            nextOrdinal = 1
        } else {
            if (record.ordinal != nextOrdinal) order()
            nextOrdinal++
        }
        previousCandidateSource = source.index
        previousCandidateKey = record.entryKey
        candidateCount++
    }

    private fun acceptSuppression(record: BackupRecord.Suppression) {
        moveTo(Section.SUPPRESSIONS)
        if (suppressionCount >= limits.maxSuppressionCount) limit()
        validateIdentifier(record.sourceId)
        validateDictionaryValue(record.entryKey, record.templateText, null, record.okuriCondition)
        val identity = listOf(record.sourceId, record.entryKey, record.templateText, record.okuriCondition)
        previousSuppression?.let {
            val comparison = compareNullableTuple(identity, it)
            if (comparison < 0) order()
            if (comparison == 0) fail(recordNumber, CompleteDictionaryBackupError.DUPLICATE_IDENTITY)
        }
        previousSuppression = identity
        suppressionCount++
    }

    private fun acceptVersion(record: BackupRecord.SourceVersion) {
        moveTo(Section.VERSIONS)
        if (sourceVersionCount >= limits.maxSourceVersionCount) limit()
        validateIdentifier(record.sourceId)
        if (record.lastGeneration < 0) invalid()
        previousVersionId?.let {
            val comparison = compareCodePoints(record.sourceId, it)
            if (comparison < 0) order()
            if (comparison == 0) fail(recordNumber, CompleteDictionaryBackupError.DUPLICATE_IDENTITY)
        }
        sources[record.sourceId]?.let { source ->
            if (record.lastGeneration < source.generation) invalid()
            source.versionSeen = true
        }
        previousVersionId = record.sourceId
        sourceVersionCount++
    }

    private fun acceptEnd(record: BackupRecord.End) {
        if (section == Section.START || section == Section.END) order()
        if (sources.isEmpty() || sources.values.any { !it.versionSeen }) invalid()
        if (
            record.sourceCount != sources.size || record.candidateCount != candidateCount ||
            record.suppressionCount != suppressionCount || record.sourceVersionCount != sourceVersionCount ||
            record.usageCount != usageCount
        ) fail(recordNumber, CompleteDictionaryBackupError.COUNT_MISMATCH)
        summary = BackupSummary(producerVersion!!, sources.size, candidateCount, suppressionCount, sourceVersionCount, usageCount)
        section = Section.END
    }

    private fun acceptUsage(record: BackupRecord.Usage) {
        if (formatVersion != 3) order()
        moveTo(Section.USAGES)
        if (usageCount >= limits.maxUsageCount) limit()
        validateDictionaryValue(record.readingKey, record.templateText, null, record.okuriCondition)
        if (record.committedText.isEmpty() || record.committedText.length > limits.maxStringChars ||
            !validUnicodeWithoutControls(record.committedText) || record.lastUsedSequence <= 0) invalid()
        val identity = listOf(record.readingKey, record.templateText, record.okuriCondition, record.committedText)
        previousUsage?.let {
            val comparison = compareNullableTuple(identity, it)
            if (comparison < 0) order()
            if (comparison == 0) fail(recordNumber, CompleteDictionaryBackupError.DUPLICATE_IDENTITY)
        }
        previousUsage = identity
        usageCount++
    }

    private fun requireSection(expected: Section) { if (section != expected) order() }

    private fun moveTo(target: Section) {
        if (section == Section.END || section.ordinal > target.ordinal || section == Section.START) order()
        section = target
    }

    private fun validateIdentifier(value: String) {
        if (value.length > limits.maxIdentifierChars || value.length > limits.maxStringChars) limit()
        if (value.isBlank() || !validUnicodeWithoutControls(value)) invalid()
    }

    private fun validateDictionaryValue(key: String, text: String, annotation: String?, okuri: String?) {
        listOfNotNull(key, text, annotation, okuri).forEach {
            if (it.length > limits.maxStringChars) limit()
        }
        if (annotation == "" || okuri == "") invalid()
        try {
            SkkDictionaryCodec.validateCandidateFields(key, text, annotation, okuri)
        } catch (_: SkkDictionaryFormatException) {
            invalid()
        }
    }

    private fun invalid(): Nothing = fail(recordNumber, CompleteDictionaryBackupError.INVALID_VALUE)
    private fun order(): Nothing = fail(recordNumber, CompleteDictionaryBackupError.INVALID_RECORD_ORDER)
    private fun limit(): Nothing = fail(recordNumber, CompleteDictionaryBackupError.LIMIT_EXCEEDED)
}

private sealed interface JsonValue {
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val value: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

private class JsonObjectParser(
    private val source: String,
    private val recordNumber: Long,
    private val maxStringChars: Int,
) {
    private companion object {
        const val MAX_FIELD_COUNT = 9
        const val MAX_FIELD_NAME_CHARS = 18
    }

    private var index = 0

    fun parse(): LinkedHashMap<String, JsonValue> {
        whitespace()
        expect('{')
        whitespace()
        val result = LinkedHashMap<String, JsonValue>()
        if (peek('}')) {
            index++
        } else {
            while (true) {
                val key = string()
                if (key.length > MAX_FIELD_NAME_CHARS || result.size >= MAX_FIELD_COUNT) {
                    fail(recordNumber, CompleteDictionaryBackupError.UNKNOWN_FIELD)
                }
                if (result.containsKey(key)) fail(recordNumber, CompleteDictionaryBackupError.DUPLICATE_FIELD)
                whitespace(); expect(':'); whitespace()
                result[key] = value()
                whitespace()
                when {
                    peek(',') -> { index++; whitespace() }
                    peek('}') -> { index++; break }
                    else -> jsonError()
                }
            }
        }
        whitespace()
        if (index != source.length) jsonError()
        return result
    }

    private fun value(): JsonValue = when {
        peek('"') -> JsonValue.StringValue(string())
        source.startsWith("true", index) -> { index += 4; JsonValue.BooleanValue(true) }
        source.startsWith("false", index) -> { index += 5; JsonValue.BooleanValue(false) }
        source.startsWith("null", index) -> { index += 4; JsonValue.NullValue }
        index < source.length && source[index] in '0'..'9' -> number()
        else -> jsonError()
    }

    private fun number(): JsonValue.NumberValue {
        val start = index
        if (source[index] == '0') {
            index++
            if (index < source.length && source[index] in '0'..'9') jsonError()
        } else {
            while (index < source.length && source[index] in '0'..'9') index++
        }
        if (index < source.length && source[index] in ".eE+-") jsonError()
        return JsonValue.NumberValue(source.substring(start, index))
    }

    private fun string(): String {
        expect('"')
        val result = StringBuilder()
        while (index < source.length) {
            val char = source[index++]
            when {
                char == '"' -> {
                    if (result.length > maxStringChars) fail(recordNumber, CompleteDictionaryBackupError.LIMIT_EXCEEDED)
                    val value = result.toString()
                    if (!validUnicodeWithoutControls(value)) fail(recordNumber, CompleteDictionaryBackupError.INVALID_VALUE)
                    return value
                }
                char == '\\' -> appendEscape(result)
                char.code < 0x20 -> jsonError()
                else -> result.append(char)
            }
            if (result.length > maxStringChars) fail(recordNumber, CompleteDictionaryBackupError.LIMIT_EXCEEDED)
        }
        jsonError()
    }

    private fun appendEscape(result: StringBuilder) {
        if (index >= source.length) jsonError()
        when (val escaped = source[index++]) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000c')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> result.append(readUnicodeEscape())
            else -> jsonError()
        }
    }

    private fun readUnicodeEscape(): Char {
        if (index + 4 > source.length) jsonError()
        var value = 0
        repeat(4) {
            val digit = when (val char = source[index++]) {
                in '0'..'9' -> char.code - '0'.code
                in 'a'..'f' -> char.code - 'a'.code + 10
                in 'A'..'F' -> char.code - 'A'.code + 10
                else -> jsonError()
            }
            value = value * 16 + digit
        }
        return value.toChar()
    }

    private fun whitespace() { while (index < source.length && source[index] in " \t") index++ }
    private fun expect(char: Char) { if (!peek(char)) jsonError(); index++ }
    private fun peek(char: Char): Boolean = index < source.length && source[index] == char
    private fun jsonError(): Nothing = fail(recordNumber, CompleteDictionaryBackupError.INVALID_JSON)
}

private fun Map<String, JsonValue>.requireExactly(recordNumber: Long, vararg expected: String) {
    val expectedSet = expected.toSet()
    keys.firstOrNull { it !in expectedSet }?.let { fail(recordNumber, CompleteDictionaryBackupError.UNKNOWN_FIELD) }
    expected.firstOrNull { !containsKey(it) }?.let { fail(recordNumber, CompleteDictionaryBackupError.MISSING_FIELD) }
}

private fun Map<String, JsonValue>.string(name: String, recordNumber: Long): String =
    (this[name] as? JsonValue.StringValue)?.value ?: fail(recordNumber, CompleteDictionaryBackupError.WRONG_TYPE)

private fun Map<String, JsonValue>.nullableString(name: String, recordNumber: Long): String? = when (val value = this[name]) {
    JsonValue.NullValue -> null
    is JsonValue.StringValue -> value.value
    else -> fail(recordNumber, CompleteDictionaryBackupError.WRONG_TYPE)
}

private fun Map<String, JsonValue>.boolean(name: String, recordNumber: Long): Boolean =
    (this[name] as? JsonValue.BooleanValue)?.value ?: fail(recordNumber, CompleteDictionaryBackupError.WRONG_TYPE)

private fun Map<String, JsonValue>.long(name: String, recordNumber: Long): Long {
    val text = (this[name] as? JsonValue.NumberValue)?.value ?: fail(recordNumber, CompleteDictionaryBackupError.WRONG_TYPE)
    return text.toLongOrNull() ?: fail(recordNumber, CompleteDictionaryBackupError.WRONG_TYPE)
}

private fun Map<String, JsonValue>.int(name: String, recordNumber: Long): Int {
    val value = long(name, recordNumber)
    if (value > Int.MAX_VALUE) fail(recordNumber, CompleteDictionaryBackupError.WRONG_TYPE)
    return value.toInt()
}

private fun validUnicodeWithoutControls(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char.isHighSurrogate()) {
            if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
            index += 2
            continue
        }
        if (char.isLowSurrogate() || char.code in 0x00..0x1f || char.code == 0x7f) return false
        index++
    }
    return true
}

private fun isValidStoredOrigin(value: String): Boolean = try {
    val uri = URI(value)
    when {
        uri.scheme.equals("https", ignoreCase = true) ->
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
        uri.scheme.equals("content", ignoreCase = true) ->
            !uri.isOpaque && !uri.rawAuthority.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
        else -> false
    }
} catch (_: Exception) {
    false
}

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

private fun compareNullableTuple(left: List<String?>, right: List<String?>): Int {
    for (index in left.indices) {
        val l = left[index]
        val r = right[index]
        val compared = when {
            l == null && r == null -> 0
            l == null -> -1
            r == null -> 1
            else -> compareCodePoints(l, r)
        }
        if (compared != 0) return compared
    }
    return 0
}

private fun fail(recordNumber: Long, error: CompleteDictionaryBackupError): Nothing =
    throw CompleteDictionaryBackupException(recordNumber, error)
