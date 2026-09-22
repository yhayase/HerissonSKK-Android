package se.haya.skk.dictionary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import se.haya.skk.core.DictionaryQuery
import se.haya.skk.core.dictionary.SkkDictionaryCodec
import se.haya.skk.dictionary.network.NetworkDictionaryCancellation
import se.haya.skk.dictionary.network.NetworkDictionaryCatalog
import se.haya.skk.dictionary.network.NetworkDictionaryDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 明示実行用です。公式 S 辞書へ実際に HTTPS 接続するため、通信可能な専用端末で実行します。 */
@RunWith(AndroidJUnit4::class)
class NetworkDictionaryLiveTest {
    @Test fun officialSmallDictionaryDownloadsAndSurvivesRepositoryReopen() {
        assumeTrue(
            "ネットワーク接続を伴う試験は instrumentation 引数 networkLive=true で明示実行します",
            InstrumentationRegistry.getArguments().getString("networkLive") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "network-live-${UUID.randomUUID()}.db"
        val catalog = checkNotNull(NetworkDictionaryCatalog.find("S"))
        try {
            val downloaded = NetworkDictionaryDownloader().download(catalog.url, NetworkDictionaryCancellation())
            val document = SkkDictionaryCodec.parse(downloaded.bytes)
            assertTrue("公式 S 辞書にエントリーがありません", document.entries.isNotEmpty())
            SQLiteDictionaryRepository(context, name).use { repository ->
                repository.importSystem("official-s", catalog.name, document, originUrl = catalog.url)
            }
            // 取得器を使わず、再オープンした SQLite の内容だけで候補を読みます。
            SQLiteDictionaryRepository(context, name).use { repository ->
                val source = repository.listSources().single { it.id == "official-s" }
                assertEquals(catalog.url, source.originUrl)
                assertEquals(1L, source.generation)
                val candidates = repository.lookup("にほん").asComposite()
                    .lookup(DictionaryQuery("にほん")).map { it.text }
                assertTrue("導入した S 辞書から日本を検索できません", "日本" in candidates)
                repository.importSystem("official-s", catalog.name, document,
                    expectedGeneration = source.generation, originUrl = source.originUrl)
                assertEquals(2L, repository.listSources().single { it.id == "official-s" }.generation)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
