package jp.hayase.skk.settings

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import jp.hayase.skk.core.CandidateDisplayConfig
import jp.hayase.skk.core.CandidatePageMode
import jp.hayase.skk.core.PunctuationConfig
import jp.hayase.skk.core.romaji.RomajiRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class CustomizationStoreTest {
    @Test fun `AZIKとカスタムキーを版2で保存し旧版は標準割当として読む`() {
        val path = temporaryPath()
        val direct = Executor { it.run() }
        path.writeText(validJson())
        val store = CustomizationStore(path, direct, direct).also { it.loadAsync() }
        assertTrue(store.status is CustomizationStoreStatus.Ready)
        assertEquals(jp.hayase.skk.core.keys.KeyBindings(), store.snapshot.keyBindings)
        val requested = CustomizationSettings(0, CustomizationProfile.AZIK, emacsEnabled = true)
        store.save(requested, 0) { assertTrue(it is CustomizationWriteResult.Applied) }
        val json = org.json.JSONObject(path.readText())
        assertEquals(2, json.getInt("documentVersion"))
        store.close()
        val reopened = CustomizationStore(path, direct, direct).also { it.loadAsync() }
        assertEquals(requested.withGeneration(1), reopened.snapshot)
        reopened.close()
        deleteAtomicFiles(path)
    }

    @Test fun `追加規則の先頭文字と有効な編集割当の競合を拒否する`() {
        assertThrows(IllegalArgumentException::class.java) {
            CustomizationSettings(0, CustomizationProfile.CUSTOM, listOf(RomajiRule("q", "ん")))
        }
        val moved = jp.hayase.skk.core.keys.KeyBindings(jp.hayase.skk.core.keys.KeyBindings.defaults +
            (jp.hayase.skk.core.keys.SkkCommand.TOGGLE_KANA to jp.hayase.skk.core.keys.KeyGesture("[")))
        CustomizationSettings(0, CustomizationProfile.CUSTOM, listOf(RomajiRule("q", "ん")), keyBindings = moved)
        val collision = jp.hayase.skk.core.keys.KeyBindings(jp.hayase.skk.core.keys.KeyBindings.defaults +
            (jp.hayase.skk.core.keys.SkkCommand.EDIT_HOME to jp.hayase.skk.core.keys.KeyGesture("j", ctrl = true)))
        CustomizationSettings(0, keyBindings = collision)
        assertThrows(IllegalArgumentException::class.java) {
            CustomizationSettings(0, emacsEnabled = true, keyBindings = collision)
        }
    }

    @Test fun `設定モデルは規則を防御コピーしプロファイル境界を検証する`() {
        val mutable = mutableListOf(RomajiRule("ka", "か゚"))
        val settings = custom(0, mutable)
        mutable.clear()
        assertEquals(listOf(RomajiRule("ka", "か゚")), settings.customRules)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (settings.customRules as MutableList<RomajiRule>).clear()
        }
        assertThrows(IllegalArgumentException::class.java) {
            CustomizationSettings(0, CustomizationProfile.STANDARD, listOf(RomajiRule("ka", "か")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            custom(0, listOf(RomajiRule("Ka", "か")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            custom(0, listOf(RomajiRule("tt", "っ", "T")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            custom(0, listOf(RomajiRule("a".repeat(17), "あ")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            custom(0, listOf(RomajiRule("a", "あ".repeat(65))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CustomizationSettings(0, candidateDisplay = CandidateDisplayConfig("1234"))
        }
    }

    @Test fun `保存した全設定を再読込し世代競合では一切書かない`() {
        val path = temporaryPath()
        val direct = Executor { it.run() }
        val first = CustomizationStore(path, direct, direct)
        first.loadAsync()
        val requested = custom(
            0,
            listOf(RomajiRule("ka", "か゚"), RomajiRule("n", "", terminalOutput = "ん")),
            punctuation = PunctuationConfig("．", "，", false, true),
            candidate = CandidateDisplayConfig("1234567", CandidatePageMode.AUTO, 5),
            emacs = true,
        )
        var applied: CustomizationWriteResult? = null
        first.save(requested, 0) { applied = it }
        val saved = (applied as CustomizationWriteResult.Applied).settings
        assertEquals(1L, saved.generation)
        assertEquals(requested.withGeneration(1), saved)

        val bytes = path.readBytes()
        var conflict: CustomizationWriteResult? = null
        first.save(requested, 0) { conflict = it }
        assertEquals(CustomizationWriteResult.Conflict(1), conflict)
        assertTrue(bytes.contentEquals(path.readBytes()))
        first.close()

        val reopened = CustomizationStore(path, direct, direct)
        reopened.loadAsync()
        assertEquals(CustomizationStoreStatus.Ready(saved), reopened.status)
        assertEquals(saved, reopened.snapshot)
        assertNotSame(saved.customRules, reopened.snapshot.customRules)
        reopened.close()
        deleteAtomicFiles(path)
    }

    @Test fun `中断したAtomicFile書込は直前の耐久世代を読み直す`() {
        val path = temporaryPath()
        val atomic = AtomicFile(path)
        atomic.startWrite().let { output ->
            output.write(validJson().toByteArray())
            atomic.finishWrite(output)
        }
        atomic.startWrite().let { output ->
            output.write("{broken".toByteArray())
            output.close()
        }
        val direct = Executor { it.run() }
        val store = CustomizationStore(path, direct, direct)
        store.loadAsync()

        assertEquals(CustomizationStoreStatus.Ready(CustomizationSettings.defaults()), store.status)
        store.close()
        deleteAtomicFiles(path)
    }

    @Test fun `同じ世代の並行保存は直列実行時に再照合する`() {
        val serial = ManualExecutor()
        val file = MemoryFile()
        val store = CustomizationStore(file, serial, Executor { it.run() })
        store.loadAsync()
        serial.runAll()
        val results = mutableListOf<CustomizationWriteResult>()
        store.save(custom(0, listOf(RomajiRule("ka", "か"))), 0, results::add)
        store.save(custom(0, listOf(RomajiRule("ki", "き"))), 0, results::add)
        serial.runAll()

        assertTrue(results[0] is CustomizationWriteResult.Applied)
        assertEquals(CustomizationWriteResult.Conflict(1), results[1])
        assertEquals(1, file.writeCount)
    }

    @Test fun `書込失敗は公開世代と耐久世代を維持する`() {
        val direct = Executor { it.run() }
        val file = MemoryFile()
        val store = CustomizationStore(file, direct, direct)
        store.loadAsync()
        store.save(custom(0, listOf(RomajiRule("ka", "か"))), 0) { }
        val durable = checkNotNull(file.bytes).copyOf()
        file.failWrite = true
        var failed: CustomizationWriteResult? = null
        store.save(custom(1, listOf(RomajiRule("ki", "き"))), 1) { failed = it }

        assertEquals(CustomizationWriteResult.Failed(CustomizationStoreFailure.IO), failed)
        assertEquals(1, store.snapshot.generation)
        assertTrue(durable.contentEquals(file.bytes))
        file.failWrite = false
        val reopened = CustomizationStore(file, direct, direct)
        reopened.loadAsync()
        assertEquals(store.snapshot, reopened.snapshot)
    }

    @Test fun `破損入力は全体を拒否して保持し明示リセットだけが上書きする`() {
        val invalidDocuments = listOf(
            validJson().replace("\"generation\":0", "\"generation\":0,\"generation\":1"),
            validJson().replace("\"generation\":0", "\"generation\":0.0"),
            validJson().replace("\"profile\":\"STANDARD\"", "\"profile\":\"CUSTOM\"")
                .replace("\"customRules\":[]", ruleJson("Ka", "か")),
            validJson().replace("\"labels\":\"asdfjkl\"", "\"labels\":\"1234\""),
            validJson().dropLast(1) + ",\"unknown\":true}",
            "[".repeat(10_000) + "0" + "]".repeat(10_000),
        )
        invalidDocuments.forEach { text ->
            val direct = Executor { it.run() }
            val file = MemoryFile(text.toByteArray())
            val original = checkNotNull(file.bytes).copyOf()
            val store = CustomizationStore(file, direct, direct)
            store.loadAsync()
            assertTrue(store.status is CustomizationStoreStatus.DefaultDueToCorrupt)
            assertEquals(CustomizationSettings.defaults(), store.snapshot)
            assertTrue(original.contentEquals(file.bytes))
            var reset: CustomizationWriteResult? = null
            store.reset(0) { reset = it }
            assertTrue(reset is CustomizationWriteResult.Applied)
            assertEquals(1L, store.snapshot.generation)
            assertEquals(1, file.writeCount)
        }
    }

    @Test fun `ファイル上限超過と読込IOを型付き状態にする`() {
        val direct = Executor { it.run() }
        val oversized = MemoryFile(ByteArray(CustomizationStore.MAX_FILE_BYTES + 1) { ' '.code.toByte() })
        val corruptStore = CustomizationStore(oversized, direct, direct)
        corruptStore.loadAsync()
        assertTrue(corruptStore.status is CustomizationStoreStatus.DefaultDueToCorrupt)
        assertEquals(0, oversized.writeCount)

        val unreadable = MemoryFile(validJson().toByteArray()).apply { failRead = true }
        val errorStore = CustomizationStore(unreadable, direct, direct)
        errorStore.loadAsync()
        assertEquals(
            CustomizationStoreStatus.Error(CustomizationSettings.defaults(), CustomizationStoreFailure.IO),
            errorStore.status,
        )
        var result: CustomizationWriteResult? = null
        errorStore.reset(0) { result = it }
        assertEquals(CustomizationWriteResult.Failed(CustomizationStoreFailure.NOT_READY), result)
        assertEquals(0, unreadable.writeCount)
    }

    private fun custom(
        generation: Long,
        rules: List<RomajiRule>,
        punctuation: PunctuationConfig = PunctuationConfig(),
        candidate: CandidateDisplayConfig = CandidateDisplayConfig(),
        emacs: Boolean = false,
    ) = CustomizationSettings(
        generation,
        CustomizationProfile.CUSTOM,
        rules,
        punctuation,
        candidate,
        emacs,
    )

    private fun validJson() = """
        {"documentVersion":1,"generation":0,"profile":"STANDARD","customRules":[],
        "punctuation":{"period":"。","comma":"、","fullwidthParentheses":true,"fullwidthBrackets":false},
        "candidateDisplay":{"labels":"asdfjkl","pageMode":"FIXED","fixedPageSize":7},"emacsEnabled":false}
    """.trimIndent().replace("\n", "")

    private fun ruleJson(input: String, output: String) =
        "\"customRules\":[{\"input\":\"$input\",\"output\":\"$output\",\"remaining\":\"\",\"terminalOutput\":null}]"

    private fun temporaryPath(): File {
        val directory = RuntimeEnvironment.getApplication().cacheDir
        return File(directory, "customization-${System.nanoTime()}.json")
    }

    private fun deleteAtomicFiles(path: File) {
        path.delete()
        File(path.path + ".bak").delete()
        File(path.path + ".new").delete()
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class MemoryFile(initial: ByteArray? = null) : CustomizationFileAccess {
        var bytes: ByteArray? = initial?.copyOf()
        var failRead = false
        var failWrite = false
        var writeCount = 0

        override fun exists(): Boolean = bytes != null

        override fun read(maxBytes: Int): ByteArray {
            if (failRead) throw IOException("read failed")
            val value = checkNotNull(bytes)
            if (value.size > maxBytes) throw IllegalArgumentException("too large")
            return value.copyOf()
        }

        override fun write(bytes: ByteArray) {
            if (failWrite) throw IOException("write failed")
            this.bytes = bytes.copyOf()
            writeCount++
        }
    }
}
