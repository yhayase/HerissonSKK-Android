package jp.hayase.skk.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/** 設定画面とIMEで同じ検証済み世代を共有します。Activityは保持しません。 */
object CustomizationRuntime {
    @Volatile private var instance: CustomizationStore? = null

    @Synchronized
    fun get(context: Context): CustomizationStore = instance ?: CustomizationStore.create(
        context.applicationContext,
        Executor { command -> Handler(Looper.getMainLooper()).post(command) },
    ).also {
        instance = it
        it.loadAsync()
    }
}
