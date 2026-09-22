package se.haya.skk.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** アプリ別の Emacs 編集設定です。値がないアプリは全体設定を継承します。 */
internal class AppEmacsEditingSettings private constructor(
    imeAndAppPackages: Set<String>,
    imeOnlyPackages: Set<String>,
    disabledPackages: Set<String>,
) {
    val imeAndAppPackages = immutableCopy(imeAndAppPackages)
    val imeOnlyPackages = immutableCopy(imeOnlyPackages)
    val disabledPackages = immutableCopy(disabledPackages)
    val overriddenPackages = immutableCopy(this.imeAndAppPackages + this.imeOnlyPackages + this.disabledPackages)

    init {
        require(this.imeAndAppPackages.intersect(this.imeOnlyPackages).isEmpty() &&
            this.imeAndAppPackages.intersect(this.disabledPackages).isEmpty() &&
            this.imeOnlyPackages.intersect(this.disabledPackages).isEmpty()) {
            "同じアプリに複数の編集モードを指定できません"
        }
        require(overriddenPackages.all(::isValidPackageName)) { "アプリのパッケージ名が不正です" }
    }

    fun overrideFor(packageName: String?): EmacsEditingMode? = when {
        packageName == null || !isValidPackageName(packageName) -> null
        packageName in imeAndAppPackages -> EmacsEditingMode.IME_AND_APP
        packageName in imeOnlyPackages -> EmacsEditingMode.IME_ONLY
        packageName in disabledPackages -> EmacsEditingMode.DISABLED
        else -> null
    }

    fun emacsEditingMode(packageName: String?, globalMode: EmacsEditingMode): EmacsEditingMode =
        overrideFor(packageName) ?: globalMode

    fun withOverride(packageName: String, mode: EmacsEditingMode?): AppEmacsEditingSettings {
        require(isValidPackageName(packageName)) { "アプリのパッケージ名が不正です" }
        val imeAndAppNext = imeAndAppPackages.toMutableSet().apply { remove(packageName) }
        val imeOnlyNext = imeOnlyPackages.toMutableSet().apply { remove(packageName) }
        val disabledNext = disabledPackages.toMutableSet().apply { remove(packageName) }
        when (mode) {
            EmacsEditingMode.IME_AND_APP -> imeAndAppNext += packageName
            EmacsEditingMode.IME_ONLY -> imeOnlyNext += packageName
            EmacsEditingMode.DISABLED -> disabledNext += packageName
            null -> Unit
        }
        return AppEmacsEditingSettings(imeAndAppNext, imeOnlyNext, disabledNext)
    }

    override fun equals(other: Any?) = other is AppEmacsEditingSettings &&
        imeAndAppPackages == other.imeAndAppPackages && imeOnlyPackages == other.imeOnlyPackages &&
        disabledPackages == other.disabledPackages

    override fun hashCode() = 31 * (31 * imeAndAppPackages.hashCode() + imeOnlyPackages.hashCode()) +
        disabledPackages.hashCode()

    companion object {
        fun defaults() = AppEmacsEditingSettings(emptySet(), emptySet(), emptySet())

        internal fun fromPersisted(all: Set<String>, ime: Set<String>, off: Set<String>): AppEmacsEditingSettings {
            val validAll = all.filterTo(linkedSetOf(), ::isValidPackageName)
            val validIme = ime.filterTo(linkedSetOf(), ::isValidPackageName).apply { removeAll(validAll) }
            val validOff = off.filterTo(linkedSetOf(), ::isValidPackageName).apply {
                removeAll(validAll); removeAll(validIme)
            }
            return AppEmacsEditingSettings(validAll, validIme, validOff)
        }

        internal fun fromLegacy(enabled: Set<String>, disabled: Set<String>, internalEnabled: Boolean) =
            if (internalEnabled) fromPersisted(enabled, disabled, emptySet())
            else fromPersisted(enabled, emptySet(), disabled)

        fun isValidPackageName(value: String) = value.length in 3..255 && PACKAGE_NAME.matches(value)
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private fun immutableCopy(value: Set<String>): Set<String> =
            Collections.unmodifiableSet(LinkedHashSet(value))
    }
}

internal enum class AppEmacsEditingSaveStatus { IDLE, PENDING, SAVED, FAILED }

internal data class AppEmacsEditingState(
    val savedSettings: AppEmacsEditingSettings,
    val visibleSettings: AppEmacsEditingSettings,
    val status: AppEmacsEditingSaveStatus = AppEmacsEditingSaveStatus.IDLE,
    val failedPackageName: String? = null,
    val failedOverride: EmacsEditingMode? = null,
)

/** アプリ別設定を非同期に保存し、保存失敗時には SharedPreferences のメモリー値も復元します。 */
internal class AppEmacsEditingStore(
    private val preferences: SharedPreferences,
    private val worker: Executor,
    private val callback: Executor,
    private val commitEditor: (SharedPreferences.Editor) -> Boolean = { it.commit() },
) {
    private var requestVersion = 0L
    private var completedVersion = 0L
    private var legacyInternalEnabled: Boolean? = null
    private var migrationPending = false
    private val migrationCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var state = readSettings().let { AppEmacsEditingState(it, it) }
    private val observers = mutableSetOf<(AppEmacsEditingState) -> Unit>()

    @Synchronized fun snapshot() = state

    fun observe(observer: (AppEmacsEditingState) -> Unit): Closeable {
        val initial = synchronized(this) { observers += observer; state }
        observer(initial)
        return Closeable { synchronized(this) { observers -= observer } }
    }

    /** 旧形式を旧全体設定と同じ実効値になる三択へ移し、保存だけをワーカーへ送ります。 */
    fun migrateLegacy(internalEnabled: Boolean) = migrateLegacyInternal(internalEnabled, null)

    fun migrateLegacy(internalEnabled: Boolean, completion: (Boolean) -> Unit) =
        migrateLegacyInternal(internalEnabled, completion)

    private fun migrateLegacyInternal(internalEnabled: Boolean, completion: ((Boolean) -> Unit)?) {
        val shouldPersist = synchronized(this) {
            if (isMigrated()) {
                completion?.invoke(true)
                return@synchronized false
            }
            val legacyInternal = legacyInternalEnabled ?: internalEnabled.also { legacyInternalEnabled = it }
            val migrated = readLegacySettings(legacyInternal)
            state = state.copy(savedSettings = migrated, visibleSettings = migrated)
            if (migrated.overriddenPackages.isEmpty()) {
                completion?.invoke(true)
                return@synchronized false
            }
            completion?.let { migrationCallbacks += it }
            if (migrationPending) false else true.also { migrationPending = true }
        }
        notifyObservers()
        if (!shouldPersist) return
        val legacyEnabled = preferences.stringSet(LEGACY_ENABLED_KEY)
        val legacyDisabled = preferences.stringSet(LEGACY_DISABLED_KEY)
        worker.execute {
            val settings = synchronized(this) { state.savedSettings }
            val saved = runCatching { writeSettings(settings) }.getOrDefault(false)
            if (!saved) runCatching { restoreLegacy(legacyEnabled, legacyDisabled) }
            callback.execute {
                val completions = synchronized(this) {
                    migrationPending = false
                    migrationCallbacks.toList().also { migrationCallbacks.clear() }
                }
                // 失敗時は旧形式を残すため、次回起動時に同じ変換を再試行できます。
                if (!saved) notifyObservers()
                completions.forEach { it(saved) }
            }
        }
    }

    /** `mode` が null の場合は、このアプリの指定を削除して全体設定を継承します。 */
    fun requestOverride(packageName: String, mode: EmacsEditingMode?) {
        require(AppEmacsEditingSettings.isValidPackageName(packageName)) { "アプリのパッケージ名が不正です" }
        val request = synchronized(this) {
            val requested = state.visibleSettings.withOverride(packageName, mode)
            if (requested == state.visibleSettings && state.status != AppEmacsEditingSaveStatus.FAILED) null else {
                requestVersion++
                state = state.copy(visibleSettings = requested, status = AppEmacsEditingSaveStatus.PENDING,
                    failedPackageName = null, failedOverride = null)
                Request(requestVersion, packageName, mode, requested)
            }
        } ?: return
        notifyObservers()
        worker.execute {
            val previous = readSettings()
            val saved = runCatching { writeSettings(request.settings) }.getOrDefault(false)
            if (!saved) runCatching { writeSettings(previous) }
            callback.execute {
                synchronized(this) {
                    if (request.version > completedVersion) {
                        completedVersion = request.version
                        if (request.version != requestVersion) {
                            state = state.copy(savedSettings = if (saved) request.settings else previous)
                        } else if (saved) {
                            state = AppEmacsEditingState(request.settings, request.settings, AppEmacsEditingSaveStatus.SAVED)
                        } else {
                            state = AppEmacsEditingState(previous, previous, AppEmacsEditingSaveStatus.FAILED,
                                request.packageName, request.mode)
                        }
                    }
                }
                notifyObservers()
            }
        }
    }

    fun emacsEditingMode(packageName: String?, globalMode: EmacsEditingMode) =
        snapshot().savedSettings.emacsEditingMode(packageName, globalMode)

    private fun readSettings() = if (isMigrated()) {
        AppEmacsEditingSettings.fromPersisted(preferences.stringSet(ALL_KEY), preferences.stringSet(IME_KEY),
            preferences.stringSet(OFF_KEY))
    } else readLegacySettings(legacyInternalEnabled ?: false)

    private fun readLegacySettings(internalEnabled: Boolean) = AppEmacsEditingSettings.fromLegacy(
        preferences.stringSet(LEGACY_ENABLED_KEY), preferences.stringSet(LEGACY_DISABLED_KEY), internalEnabled)

    private fun writeSettings(settings: AppEmacsEditingSettings) = commitEditor(preferences.edit()
        .putStringSet(ALL_KEY, settings.imeAndAppPackages)
        .putStringSet(IME_KEY, settings.imeOnlyPackages)
        .putStringSet(OFF_KEY, settings.disabledPackages)
        .putInt(VERSION_KEY, CURRENT_VERSION))

    private fun restoreLegacy(enabled: Set<String>, disabled: Set<String>) = commitEditor(preferences.edit()
        .remove(ALL_KEY).remove(IME_KEY).remove(OFF_KEY).remove(VERSION_KEY)
        .putStringSet(LEGACY_ENABLED_KEY, enabled).putStringSet(LEGACY_DISABLED_KEY, disabled))

    private fun isMigrated() = preferences.getInt(VERSION_KEY, 0) >= CURRENT_VERSION
    private fun SharedPreferences.stringSet(key: String) = getStringSet(key, emptySet())?.toSet().orEmpty()

    private fun notifyObservers() {
        val (current, listeners) = synchronized(this) { state to observers.toList() }
        listeners.forEach { it(current) }
    }

    private data class Request(val version: Long, val packageName: String, val mode: EmacsEditingMode?,
        val settings: AppEmacsEditingSettings)

    private companion object {
        const val VERSION_KEY = "emacs_mode_format_version"
        const val CURRENT_VERSION = 1
        const val ALL_KEY = "emacs_mode_ime_and_app_packages"
        const val IME_KEY = "emacs_mode_ime_only_packages"
        const val OFF_KEY = "emacs_mode_disabled_packages"
        const val LEGACY_ENABLED_KEY = "emacs_enabled_packages"
        const val LEGACY_DISABLED_KEY = "emacs_disabled_packages"
    }
}

/** アプリプロセス中はアプリ別設定の保存状態を共有し、Activity を所有しません。 */
internal object AppEmacsEditingRuntime {
    @Volatile private var instance: AppEmacsEditingStore? = null

    @Synchronized fun get(context: Context): AppEmacsEditingStore = instance ?: run {
        val application = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        AppEmacsEditingStore(application.getSharedPreferences("app-emacs-editing", Context.MODE_PRIVATE),
            Executors.newSingleThreadExecutor(), Executor { check(main.post(it)) }).also { instance = it }
    }
}
