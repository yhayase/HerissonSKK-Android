package jp.hayase.skk.settings

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import jp.hayase.skk.core.CandidateDisplayConfig
import jp.hayase.skk.core.CandidatePageMode
import jp.hayase.skk.core.PunctuationConfig
import jp.hayase.skk.core.romaji.RomajiRule
import jp.hayase.skk.core.keys.KeyBindings
import jp.hayase.skk.core.keys.KeyGesture
import jp.hayase.skk.core.keys.SkkCommand
import jp.hayase.skk.core.keys.SpecialKey
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

enum class CustomizationStoreFailure { NOT_READY, INVALID_DATA, IO, GENERAL }

sealed interface CustomizationStoreStatus {
    val settings: CustomizationSettings

    data class Loading(override val settings: CustomizationSettings) : CustomizationStoreStatus
    data class Ready(override val settings: CustomizationSettings) : CustomizationStoreStatus
    data class DefaultDueToCorrupt(override val settings: CustomizationSettings) : CustomizationStoreStatus
    data class Error(
        override val settings: CustomizationSettings,
        val failure: CustomizationStoreFailure,
    ) : CustomizationStoreStatus
}

sealed interface CustomizationWriteResult {
    data class Applied(val settings: CustomizationSettings) : CustomizationWriteResult
    data class Conflict(val currentGeneration: Long) : CustomizationWriteResult
    data class Failed(val failure: CustomizationStoreFailure) : CustomizationWriteResult
}

/** カスタマイズ専用 JSON を直列 I/O で保存し、公開済みスナップショットだけを同期公開します。 */
class CustomizationStore internal constructor(
    private val file: CustomizationFileAccess,
    private val serialExecutor: Executor,
    private val callbackExecutor: Executor,
    private val ownedExecutor: ExecutorService? = null,
) : Closeable {
    @Volatile private var closed = false
    @Volatile private var published: CustomizationStoreStatus =
        CustomizationStoreStatus.Loading(CustomizationSettings.defaults())

    constructor(
        context: Context,
        serialExecutor: Executor,
        callbackExecutor: Executor,
    ) : this(
        AtomicCustomizationFile(File(context.applicationContext.filesDir, FILE_NAME)),
        serialExecutor,
        callbackExecutor,
    )

    internal constructor(
        path: File,
        serialExecutor: Executor,
        callbackExecutor: Executor,
    ) : this(AtomicCustomizationFile(path), serialExecutor, callbackExecutor)

    val status: CustomizationStoreStatus get() = published
    val snapshot: CustomizationSettings get() = published.settings

    @Synchronized
    fun loadAsync(callback: ((CustomizationStoreStatus) -> Unit)? = null) {
        check(!closed) { "カスタマイズ設定は閉じられています" }
        serialExecutor.execute {
            val next = try {
                if (!file.exists()) {
                    CustomizationStoreStatus.Ready(CustomizationSettings.defaults())
                } else {
                    CustomizationStoreStatus.Ready(CustomizationJson.decode(file.read(MAX_FILE_BYTES)))
                }
            } catch (_: InvalidCustomizationFileException) {
                CustomizationStoreStatus.DefaultDueToCorrupt(CustomizationSettings.defaults())
            } catch (_: JSONException) {
                CustomizationStoreStatus.DefaultDueToCorrupt(CustomizationSettings.defaults())
            } catch (_: IllegalArgumentException) {
                CustomizationStoreStatus.DefaultDueToCorrupt(CustomizationSettings.defaults())
            } catch (_: IOException) {
                CustomizationStoreStatus.Error(snapshot, CustomizationStoreFailure.IO)
            } catch (_: RuntimeException) {
                CustomizationStoreStatus.Error(snapshot, CustomizationStoreFailure.GENERAL)
            }
            publish(next)
            callback?.let { deliver(it, next) }
        }
    }

    fun save(
        settings: CustomizationSettings,
        expectedGeneration: Long,
        callback: (CustomizationWriteResult) -> Unit,
    ) = write(expectedGeneration, settings, callback)

    fun reset(
        expectedGeneration: Long,
        callback: (CustomizationWriteResult) -> Unit,
    ) = write(expectedGeneration, CustomizationSettings.defaults(expectedGeneration), callback)

    @Synchronized
    private fun write(
        expectedGeneration: Long,
        requested: CustomizationSettings,
        callback: (CustomizationWriteResult) -> Unit,
    ) {
        check(!closed) { "カスタマイズ設定は閉じられています" }
        serialExecutor.execute {
            val current = published
            if (current is CustomizationStoreStatus.Loading || current is CustomizationStoreStatus.Error) {
                deliver(callback, CustomizationWriteResult.Failed(CustomizationStoreFailure.NOT_READY))
                return@execute
            }
            val currentGeneration = current.settings.generation
            if (expectedGeneration != currentGeneration) {
                deliver(callback, CustomizationWriteResult.Conflict(currentGeneration))
                return@execute
            }
            if (requested.generation != expectedGeneration) {
                deliver(callback, CustomizationWriteResult.Failed(CustomizationStoreFailure.INVALID_DATA))
                return@execute
            }
            val result = try {
                val nextGeneration = Math.addExact(expectedGeneration, 1)
                val validated = requested.withGeneration(nextGeneration)
                val bytes = CustomizationJson.encode(validated)
                if (bytes.size > MAX_FILE_BYTES) throw InvalidCustomizationFileException()
                file.write(bytes)
                publish(CustomizationStoreStatus.Ready(validated))
                CustomizationWriteResult.Applied(validated)
            } catch (_: InvalidCustomizationFileException) {
                CustomizationWriteResult.Failed(CustomizationStoreFailure.INVALID_DATA)
            } catch (_: IllegalArgumentException) {
                CustomizationWriteResult.Failed(CustomizationStoreFailure.INVALID_DATA)
            } catch (_: ArithmeticException) {
                CustomizationWriteResult.Failed(CustomizationStoreFailure.INVALID_DATA)
            } catch (_: IOException) {
                CustomizationWriteResult.Failed(CustomizationStoreFailure.IO)
            } catch (_: RuntimeException) {
                CustomizationWriteResult.Failed(CustomizationStoreFailure.GENERAL)
            }
            deliver(callback, result)
        }
    }

    @Synchronized
    private fun publish(value: CustomizationStoreStatus) {
        if (!closed) published = value
    }

    private fun <T> deliver(callback: (T) -> Unit, value: T) {
        callbackExecutor.execute { if (!closed) callback(value) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        ownedExecutor?.shutdown()
    }

    companion object {
        const val MAX_FILE_BYTES = 2 * 1_024 * 1_024
        private const val FILE_NAME = "customization-settings.json"

        fun create(context: Context, callbackExecutor: Executor): CustomizationStore {
            val serial = Executors.newSingleThreadExecutor()
            return CustomizationStore(
                AtomicCustomizationFile(File(context.applicationContext.filesDir, FILE_NAME)),
                serial,
                callbackExecutor,
                serial,
            )
        }
    }
}

internal interface CustomizationFileAccess {
    fun exists(): Boolean
    @Throws(IOException::class) fun read(maxBytes: Int): ByteArray
    @Throws(IOException::class) fun write(bytes: ByteArray)
}

private class AtomicCustomizationFile(path: File) : CustomizationFileAccess {
    private val atomic = AtomicFile(path)

    override fun exists(): Boolean = atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()

    override fun read(maxBytes: Int): ByteArray {
        try {
            atomic.openRead().use { input ->
                val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1_024))
                val buffer = ByteArray(8 * 1_024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw InvalidCustomizationFileException()
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        } catch (error: FileNotFoundException) {
            throw IOException("設定ファイルを読み込めません", error)
        }
    }

    override fun write(bytes: ByteArray) {
        var output: java.io.FileOutputStream? = null
        try {
            val stream = atomic.startWrite()
            output = stream
            stream.write(bytes)
            atomic.finishWrite(stream)
            output = null
        } catch (error: IOException) {
            output?.let(atomic::failWrite)
            throw error
        } catch (error: RuntimeException) {
            output?.let(atomic::failWrite)
            throw error
        }
    }
}

private object CustomizationJson {
    private const val SCHEMA_VERSION = 2
    private const val MAX_JSON_DEPTH = 16
    private val ROOT_FIELDS = setOf(
        "documentVersion", "generation", "profile", "customRules", "punctuation",
        "candidateDisplay", "emacsEnabled", "keyBindings",
    )
    private val RULE_FIELDS = setOf("input", "output", "remaining", "terminalOutput")
    private val PUNCTUATION_FIELDS = setOf(
        "period", "comma", "fullwidthParentheses", "fullwidthBrackets",
    )
    private val CANDIDATE_FIELDS = setOf("labels", "pageMode", "fixedPageSize")

    fun encode(settings: CustomizationSettings): ByteArray {
        val rules = JSONArray()
        settings.customRules.forEach { rule ->
            rules.put(JSONObject().apply {
                put("input", rule.input)
                put("output", rule.output)
                put("remaining", rule.remaining)
                put("terminalOutput", rule.terminalOutput ?: JSONObject.NULL)
            })
        }
        val root = JSONObject().apply {
            put("documentVersion", SCHEMA_VERSION)
            put("generation", settings.generation)
            put("profile", settings.profile.name)
            put("customRules", rules)
            put("punctuation", JSONObject().apply {
                put("period", settings.punctuation.period)
                put("comma", settings.punctuation.comma)
                put("fullwidthParentheses", settings.punctuation.fullwidthParentheses)
                put("fullwidthBrackets", settings.punctuation.fullwidthBrackets)
            })
            put("candidateDisplay", JSONObject().apply {
                put("labels", settings.candidateDisplay.labels)
                put("pageMode", settings.candidateDisplay.pageMode.name)
                put("fixedPageSize", settings.candidateDisplay.fixedPageSize)
            })
            put("emacsEnabled", settings.emacsEnabled)
            put("keyBindings", JSONArray().apply {
                settings.keyBindings.bindings.forEach { (command, key) -> put(JSONObject().apply {
                    put("command", command.name)
                    put("text", key.text ?: JSONObject.NULL)
                    put("special", key.special?.name ?: JSONObject.NULL)
                    put("ctrl", key.ctrl); put("alt", key.alt); put("shift", key.shift)
                    put("ignoreShift", key.ignoreShift)
                }) }
            })
        }
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): CustomizationSettings {
        val text = strictUtf8(bytes)
        rejectDuplicateFields(text)
        val root = JSONObject(text)
        val version = root.requiredInteger("documentVersion")
        if (version != 1L && version != SCHEMA_VERSION.toLong()) {
            throw InvalidCustomizationFileException()
        }
        root.requireFields(if (version == 1L) ROOT_FIELDS - "keyBindings" else ROOT_FIELDS)
        val generation = root.requiredInteger("generation")
        val profile = enumValue<CustomizationProfile>(root.requiredString("profile"))
        if (version == 1L && profile == CustomizationProfile.AZIK) throw InvalidCustomizationFileException()
        val ruleArray = root.requiredArray("customRules")
        if (ruleArray.length() > jp.hayase.skk.core.romaji.RomanRuleSet.MAX_RULES) {
            throw InvalidCustomizationFileException()
        }
        val rules = ArrayList<RomajiRule>(ruleArray.length())
        repeat(ruleArray.length()) { index ->
            val value = ruleArray.opt(index)
            if (value !is JSONObject) throw InvalidCustomizationFileException()
            value.requireFields(RULE_FIELDS)
            val terminal = value.opt("terminalOutput").let {
                when (it) {
                    JSONObject.NULL -> null
                    is String -> it
                    else -> throw InvalidCustomizationFileException()
                }
            }
            rules += RomajiRule(
                value.requiredString("input"),
                value.requiredString("output"),
                value.requiredString("remaining"),
                terminal,
            )
        }
        val punctuationObject = root.requiredObject("punctuation").requireFields(PUNCTUATION_FIELDS)
        val punctuation = PunctuationConfig(
            punctuationObject.requiredString("period"),
            punctuationObject.requiredString("comma"),
            punctuationObject.requiredBoolean("fullwidthParentheses"),
            punctuationObject.requiredBoolean("fullwidthBrackets"),
        )
        val candidateObject = root.requiredObject("candidateDisplay").requireFields(CANDIDATE_FIELDS)
        val pageSize = candidateObject.requiredInteger("fixedPageSize")
        if (pageSize !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw InvalidCustomizationFileException()
        }
        val candidate = CandidateDisplayConfig(
            candidateObject.requiredString("labels"),
            enumValue<CandidatePageMode>(candidateObject.requiredString("pageMode")),
            pageSize.toInt(),
        )
        val bindings = if (version == 1L) KeyBindings() else {
            val values = root.requiredArray("keyBindings")
            if (values.length() != SkkCommand.entries.size) throw InvalidCustomizationFileException()
            val keys = LinkedHashMap<SkkCommand, KeyGesture>()
            repeat(values.length()) { index ->
                val row = (values.opt(index) as? JSONObject ?: throw InvalidCustomizationFileException())
                    .requireFields(setOf("command", "text", "special", "ctrl", "alt", "shift", "ignoreShift"))
                fun nullable(name: String): String? = when (val value = row.opt(name)) {
                    JSONObject.NULL -> null
                    is String -> value
                    else -> throw InvalidCustomizationFileException()
                }
                val command = enumValue<SkkCommand>(row.requiredString("command"))
                val key = KeyGesture(nullable("text"), nullable("special")?.let { enumValue<SpecialKey>(it) },
                    row.requiredBoolean("ctrl"), row.requiredBoolean("alt"), row.requiredBoolean("shift"),
                    row.requiredBoolean("ignoreShift"))
                if (keys.put(command, key) != null) throw InvalidCustomizationFileException()
            }
            KeyBindings(keys)
        }
        return CustomizationSettings(
            generation,
            profile,
            rules,
            punctuation,
            candidate,
            root.requiredBoolean("emacsEnabled"),
            bindings,
        )
    }

    private fun strictUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw InvalidCustomizationFileException()
    }

    private fun JSONObject.requireFields(expected: Set<String>): JSONObject {
        val actual = keys().asSequence().toSet()
        if (actual != expected) throw InvalidCustomizationFileException()
        return this
    }

    private fun JSONObject.requiredString(name: String): String =
        opt(name) as? String ?: throw InvalidCustomizationFileException()

    private fun JSONObject.requiredBoolean(name: String): Boolean =
        opt(name) as? Boolean ?: throw InvalidCustomizationFileException()

    private fun JSONObject.requiredInteger(name: String): Long = when (val value = opt(name)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw InvalidCustomizationFileException()
    }

    private fun JSONObject.requiredArray(name: String): JSONArray =
        opt(name) as? JSONArray ?: throw InvalidCustomizationFileException()

    private fun JSONObject.requiredObject(name: String): JSONObject =
        opt(name) as? JSONObject ?: throw InvalidCustomizationFileException()

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name == value } ?: throw InvalidCustomizationFileException()

    /** JSONObject が後勝ちにする重複名を、構文木の各 object ごとに先に拒否します。 */
    private fun rejectDuplicateFields(text: String) {
        val tokener = JSONTokener(text)
        parseValue(tokener, 0)
        if (tokener.nextClean().code != 0) throw InvalidCustomizationFileException()
    }

    private fun parseValue(tokener: JSONTokener, depth: Int) {
        if (depth > MAX_JSON_DEPTH) throw InvalidCustomizationFileException()
        when (val first = tokener.nextClean()) {
            '{' -> parseObject(tokener, depth)
            '[' -> parseArray(tokener, depth)
            '"' -> tokener.nextString(first)
            0.toChar() -> throw InvalidCustomizationFileException()
            else -> {
                tokener.back()
                if (tokener.nextValue() is String) throw InvalidCustomizationFileException()
            }
        }
    }

    private fun parseObject(tokener: JSONTokener, depth: Int) {
        val names = mutableSetOf<String>()
        if (tokener.nextClean() == '}') return
        tokener.back()
        while (true) {
            val quote = tokener.nextClean()
            if (quote != '"') throw InvalidCustomizationFileException()
            val name = tokener.nextString(quote)
            if (!names.add(name) || tokener.nextClean() != ':') throw InvalidCustomizationFileException()
            parseValue(tokener, depth + 1)
            when (tokener.nextClean()) {
                '}' -> return
                ',' -> Unit
                else -> throw InvalidCustomizationFileException()
            }
        }
    }

    private fun parseArray(tokener: JSONTokener, depth: Int) {
        if (tokener.nextClean() == ']') return
        tokener.back()
        while (true) {
            parseValue(tokener, depth + 1)
            when (tokener.nextClean()) {
                ']' -> return
                ',' -> Unit
                else -> throw InvalidCustomizationFileException()
            }
        }
    }
}

private class InvalidCustomizationFileException : IllegalArgumentException()
