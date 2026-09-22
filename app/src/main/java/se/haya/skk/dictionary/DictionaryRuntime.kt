package se.haya.skk.dictionary

import android.content.Context

/** IME と設定画面で同じ公開済み世代を利用します。Activity は保持しません。 */
object DictionaryRuntime {
    @Volatile private var instance: DictionaryManager? = null

    @Synchronized
    fun get(context: Context): DictionaryManager = instance ?: DictionaryManager.create(
        context.applicationContext,
        fallbackSystems = if (context.resources.getBoolean(se.haya.skk.R.bool.include_test_dictionary)) {
            listOf(BuiltinDictionary.source)
        } else emptyList(),
    ).also {
        it.personalDataPolicy.setAllowed(
            context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("save_personal_data", true),
        )
        instance = it
        it.loadAsync()
    }
}
