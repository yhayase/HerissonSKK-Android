package jp.hayase.skk.core.dictionary

/** 未取得の検索結果です。load は入力スレッドの外で実行し、完了後に操作を再試行します。 */
class DeferredDictionaryReadException(val load: () -> Unit) : RuntimeException(null, null, false, false)
