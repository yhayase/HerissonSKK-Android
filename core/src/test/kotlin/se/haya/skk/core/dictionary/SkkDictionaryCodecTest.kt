package se.haya.skk.core.dictionary

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkkDictionaryCodecTest {
    @Test
    fun `UTF-8 の候補 注釈 送り条件と順序を解析する`() {
        val source = """
            ;; -*- coding: utf-8 -*-
            ;; okuri-ari entries.
            おおk /前/[く/多;多い/次/]/中/[き/大/]/
            ;; okuri-nasi entries.
            にほん /日本;国名/二本/
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val document = SkkDictionaryCodec.parse(source)

        assertEquals(SkkDictionaryEncoding.UTF8, document.encoding)
        assertEquals(listOf("おおk", "にほん"), document.entries.map { it.key })
        assertEquals(
            listOf(
                SkkDictionaryCandidate("前"),
                SkkDictionaryCandidate("多", "多い", "く"),
                SkkDictionaryCandidate("次", okuriCondition = "く"),
                SkkDictionaryCandidate("中"),
                SkkDictionaryCandidate("大", okuriCondition = "き"),
            ),
            document.entries[0].candidates,
        )
        assertEquals(
            listOf(SkkDictionaryCandidate("日本", "国名"), SkkDictionaryCandidate("二本")),
            document.entries[1].candidates,
        )
    }

    @Test
    fun `EUC-JP を明示指定と自動判定で strict に読む`() {
        val bytes = "にほん /日本;国名/\n".toByteArray(Charset.forName("EUC-JP"))

        val explicit = SkkDictionaryCodec.parse(bytes, SkkDictionaryEncoding.EUC_JP)
        val automatic = SkkDictionaryCodec.parse(bytes)

        assertEquals(SkkDictionaryEncoding.EUC_JP, explicit.encoding)
        assertEquals(SkkDictionaryEncoding.EUC_JP, automatic.encoding)
        assertEquals("日本", automatic.entries.single().candidates.single().text)
        assertThrows(SkkDictionaryFormatException::class.java) {
            SkkDictionaryCodec.parse(bytes, SkkDictionaryEncoding.UTF8)
        }
    }

    @Test
    fun `UTF-8 BOM と CRLF と CR を受理する`() {
        val body = ";; comment\r\nかな /仮名/\rべつ /別/\n".toByteArray(StandardCharsets.UTF_8)
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + body

        val document = SkkDictionaryCodec.parse(bytes)

        assertEquals(listOf("かな", "べつ"), document.entries.map { it.key })
    }

    @Test
    fun `L 辞書で使われる注釈と記号の見出しを読み書きする`() {
        val source = """
            ゆるb /弛;[文語]/緩;[文語]/
            もっとm /最;(most)/尤;(reasonable)「それも-もだ」/
            drpepper /ドクターペッパー;www.drpepper.com/Dr Pepper;"Dr"はドットなし/
            #/# /#0月#0日/#1／#1/
            / /／/÷/
            a /α;alpha/エー/
        """.trimIndent()

        val parsed = SkkDictionaryCodec.parseText(source)
        val reparsed = SkkDictionaryCodec.parse(SkkDictionaryCodec.encodeUtf8(parsed))

        assertEquals(6, parsed.entries.size)
        assertEquals("[文語]", parsed.entries[0].candidates[0].annotation)
        assertEquals("(most)", parsed.entries[1].candidates[0].annotation)
        assertEquals("\"Dr\"はドットなし", parsed.entries[2].candidates[1].annotation)
        assertEquals(parsed.entries.associateBy { it.key }, reparsed.entries.associateBy { it.key })
    }

    @Test
    fun `候補の任意式は L 辞書の注釈を許可しても拒否する`() {
        for (source in listOf("かな /(progn x)/", "かな /(progn \"危険\")/")) {
            val error = assertThrows(SkkDictionaryFormatException::class.java) {
                SkkDictionaryCodec.parseText(source)
            }
            assertEquals(SkkDictionaryError.UNSUPPORTED_EXPRESSION, error.error)
        }
    }

    @Test
    fun `限定 concat と個人注釈マーカーを評価せず復号する`() {
        val source = """てすと /(concat "C\057C++\073guide\\path\"");*(concat "注\057釈\073補足")/"""

        val candidate = SkkDictionaryCodec.parseText(source).entries.single().candidates.single()

        assertEquals("C/C++;guide\\path\"", candidate.text)
        assertEquals("注/釈;補足", candidate.annotation)
    }

    @Test
    fun `特殊文字と絵文字を UTF-8 で意味的に往復する`() {
        val original = SkkDictionaryDocument(
            listOf(
                SkkDictionaryEntry(
                    "おおk",
                    listOf(
                        SkkDictionaryCandidate("[/;\\\"😀", "URL https://example.test/a;b", "く"),
                        SkkDictionaryCandidate("]"),
                    ),
                ),
                SkkDictionaryEntry(
                    "かな",
                    listOf(
                        SkkDictionaryCandidate("(literal)", "注釈;二つ目"),
                        SkkDictionaryCandidate("(concat 未完了", "*先頭の星"),
                    ),
                ),
            ),
            SkkDictionaryEncoding.UTF8,
        )

        val bytes = SkkDictionaryCodec.encodeUtf8(original)
        val reparsed = SkkDictionaryCodec.parse(bytes)

        assertEquals(original.entries.sortedBy { it.key }, reparsed.entries.sortedBy { it.key })
        assertFalse(bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
        assertTrue(bytes.toString(StandardCharsets.UTF_8).endsWith("\n"))
    }

    @Test
    fun `書き出しは区分内の入力順を保持し連続条件をまとめる`() {
        val document = SkkDictionaryDocument(
            listOf(
                SkkDictionaryEntry("😀", listOf(SkkDictionaryCandidate("顔"))),
                SkkDictionaryEntry("あ", listOf(SkkDictionaryCandidate("亜"))),
                SkkDictionaryEntry(
                    "おおk",
                    listOf(
                        SkkDictionaryCandidate("多", okuriCondition = "く"),
                        SkkDictionaryCandidate("大", okuriCondition = "く"),
                        SkkDictionaryCandidate("広"),
                    ),
                ),
            ),
            SkkDictionaryEncoding.UTF8,
        )

        val formatted = SkkDictionaryCodec.format(document)

        assertTrue(formatted.indexOf("おおk /") < formatted.indexOf(";; okuri-nasi entries."))
        assertTrue(formatted.indexOf("😀 /") < formatted.indexOf("あ /"))
        assertTrue(formatted.contains("おおk /[く/多/大/]/広/"))
    }

    @Test
    fun `重複は最初の位置を保ち空注釈だけを補完する`() {
        val source = """
            かな /先/共通/後/
            かな /共通;補完/末尾/先;競合しない/
            かな /共通;別注釈/
        """.trimIndent()

        val document = SkkDictionaryCodec.parseText(source)

        assertEquals(listOf("先", "共通", "後", "末尾"), document.entries.single().candidates.map { it.text })
        assertEquals("補完", document.entries.single().candidates[1].annotation)
        assertEquals(3, document.diagnostics.duplicateCandidateCount)
        assertEquals(1, document.diagnostics.annotationConflictCount)
    }

    @Test(timeout = 3_000)
    fun `単一見出しの大量候補を線形に重複統合する`() {
        val source = buildString {
            append("かな /")
            repeat(50_000) { append("候補").append(it).append('/') }
        }

        val document = SkkDictionaryCodec.parseText(source)

        assertEquals(50_000, document.entries.single().candidates.size)
    }

    @Test
    fun `同じ本文でも送り条件が違えば別候補にする`() {
        val source = "おおk /大/[く/大/]/[き/大/]/\n"

        val document = SkkDictionaryCodec.parseText(source)

        assertEquals(listOf(null, "く", "き"), document.entries.single().candidates.map { it.okuriCondition })
        assertEquals(0, document.diagnostics.duplicateCandidateCount)
    }

    @Test
    fun `不正な構文は行番号だけを含む例外で全体を拒否する`() {
        val secret = "秘密候補"
        val source = "かな /正常/\nこわれ /$secret\n後 /未適用/\n"

        val exception = assertThrows(SkkDictionaryFormatException::class.java) {
            SkkDictionaryCodec.parseText(source)
        }

        assertEquals(2, exception.lineNumber)
        assertEquals(SkkDictionaryError.INVALID_CANDIDATE_LIST, exception.error)
        assertFalse(exception.message.orEmpty().contains(secret))
    }

    @Test
    fun `任意 Lisp と壊れた concat を拒否する`() {
        val invalid = listOf(
            "かな /(progn \"危険\")/" to SkkDictionaryError.UNSUPPORTED_EXPRESSION,
            "かな /(concat \"未完了)/" to SkkDictionaryError.INVALID_CANDIDATE_LIST,
            "かな /(concat \"不明\\141\")/" to SkkDictionaryError.UNSUPPORTED_EXPRESSION,
            "かな /(concat \"複数\" \"文字列\")/" to SkkDictionaryError.UNSUPPORTED_EXPRESSION,
        )
        for ((source, expected) in invalid) {
            val exception = assertThrows(SkkDictionaryFormatException::class.java) {
                SkkDictionaryCodec.parseText(source)
            }
            assertEquals(expected, exception.error)
        }
    }

    @Test
    fun `空 閉じ忘れ 入れ子の送り条件ブロックを拒否する`() {
        val invalid = listOf(
            "おおk /[き//]/",
            "おおk /[く/候補/",
            "おおk /]/",
            "おおk /[abc]/",
            "zz-fixture-ddskk-bad /[/独立試験空/]/",
            "zz-fixture-ddskk-bad /[に/[ぬ/独立試験入子/]/]/",
        )
        invalid.forEach { source ->
            val exception = assertThrows(SkkDictionaryFormatException::class.java) {
                SkkDictionaryCodec.parseText(source)
            }
            assertEquals(SkkDictionaryError.INVALID_OKURI_BLOCK, exception.error)
        }
    }

    @Test
    fun `促音で始まる送り条件を保持する`() {
        val document = SkkDictionaryCodec.parseText("つk /[っか/突/]/")

        assertEquals("っか", document.entries.single().candidates.single().okuriCondition)
    }

    @Test
    fun `見出し子音と異なる送り条件を失わず往復する`() {
        assertOpaqueOkuriRoundTrip("zz-fixture-ddskk-1k", "に", "独立試験子音候補", "独立試験子音注釈")
    }

    @Test
    fun `ASCII を含む送り条件を失わず往復する`() {
        assertOpaqueOkuriRoundTrip("zz-fixture-ddskk-2k", "n-", "独立試験英字候補", "独立試験英字注釈")
        assertOpaqueOkuriRoundTrip("zz-fixture-ddskk-extra-4", "に n;\\😀", "独立試験任意候補", "独立試験任意注釈")
    }

    @Test
    fun `送りなし見出しの送り条件ブロックを失わず往復する`() {
        assertOpaqueOkuriRoundTrip("zz-fixture-ddskk-3", "ぬ", "独立試験無送候補", "独立試験無送注釈")
    }

    private fun assertOpaqueOkuriRoundTrip(key: String, condition: String, text: String, annotation: String) {
        val source = "$key /独立試験無条件/[$condition/$text;$annotation/]/独立試験後続/"
        val document = SkkDictionaryCodec.parseText(source)
        assertEquals(
            listOf(
                SkkDictionaryCandidate("独立試験無条件"),
                SkkDictionaryCandidate(text, annotation, condition),
                SkkDictionaryCandidate("独立試験後続"),
            ),
            document.entries.single().candidates,
        )
        assertEquals(document.entries, SkkDictionaryCodec.parse(SkkDictionaryCodec.encodeUtf8(document)).entries)
    }

    @Test
    fun `既知の動的日付式と空見出しを除外して正常候補を保持する`() {
        val document = SkkDictionaryCodec.parseText(
            "zz-fixture-warning-safe /独立警告正常候補/\n" +
                "zz-fixture-warning-date /(skk-current-date nil nil t)/\n" +
                " /独立警告空候補;独立警告注釈/",
            policy = SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED,
        )
        assertEquals(listOf("zz-fixture-warning-safe"), document.entries.map { it.key })
    }

    @Test
    fun `同じ行と送りブロックの正常候補は非対応式と混在しても保持する`() {
        val document = SkkDictionaryCodec.parseText(
            "zz-fixture-warning-mixed /独立警告先頭/(skk-current-date nil nil t)/" +
                "[に/(skk-current-date nil nil t)/独立警告末尾/]/",
            policy = SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED,
        )
        assertEquals(
            listOf(SkkDictionaryCandidate("独立警告先頭"), SkkDictionaryCandidate("独立警告末尾", okuriCondition = "に")),
            document.entries.single().candidates,
        )
    }

    @Test
    fun `既定の厳密解析は既知式と空見出しも拒否する`() {
        for (source in listOf("zz-fixture-warning-date /(skk-current-date nil nil t)/", " /独立警告空候補/")) {
            assertThrows(SkkDictionaryFormatException::class.java) { SkkDictionaryCodec.parseText(source) }
        }
    }

    @Test
    fun `警告は除外件数と行番号と理由だけを保持する`() {
        val document = SkkDictionaryCodec.parseText(
            "zz-fixture-warning-safe /独立警告正常候補/\n" +
                "zz-fixture-warning-date /(skk-current-date nil nil t)/\n" +
                "zz-fixture-warning-mixed /独立警告先頭/(skk-ignore-dic-word \"独立警告末尾\")/\n" +
                " /独立警告空候補/",
            policy = SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED,
        )
        val warnings = document.diagnostics.warnings
        assertEquals(3, warnings.count)
        assertEquals(3, warnings.skippedCandidateCount)
        assertEquals(2, warnings.skippedEntryCount)
        assertEquals(listOf(2, 3, 4), warnings.samples.map { it.lineNumber })
        assertEquals(SkkDictionaryWarningReason.values().toSet(), warnings.counts.keys)
        assertFalse(warnings.toString().contains("独立警告"))
        assertThrows(UnsupportedOperationException::class.java) { (warnings.samples as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (warnings.counts as MutableMap).clear() }
    }

    @Test
    fun `非対応だけのファイルは適用候補がないため拒否するが空ファイルは従来どおり読む`() {
        val error = assertThrows(SkkDictionaryFormatException::class.java) {
            SkkDictionaryCodec.parseText("zz-fixture-warning-date /(skk-current-date nil nil t)/", policy = SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED)
        }
        assertEquals(SkkDictionaryError.NO_SUPPORTED_ENTRIES, error.error)
        assertTrue(SkkDictionaryCodec.parseText(";; コメント\n", policy = SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED).entries.isEmpty())
    }

    @Test
    fun `警告行の後続破損や未知式や壊れた concat は部分適用しない`() {
        val invalid = listOf(
            "zz-fixture-warning-date /(skk-current-date nil nil t)//",
            "zz-fixture-warning-date /[に/(skk-current-date nil nil t)/",
            " /独立警告空候補//",
            "zz-fixture-warning-date /(skk-current-date nil nil t)tail/",
            "zz-fixture-warning-date /(skk-current-date (progn t))/",
            "zz-fixture-warning-date /(skk-ignore-dic-word \"\")/",
            "zz-fixture-warning-date /(skk-ignore-dic-word \"独立警告末尾\" extra)/",
            "zz-fixture-warning-date /(progn t)/",
            "zz-fixture-warning-date /(skk-current-date \"独立警告注釈\t\")/",
            "zz-fixture-warning-date /(concat \"独立警告先頭\" \"独立警告末尾\")/",
            "zz-fixture-warning-date /(concat \"独立警告先頭\\141\")/",
            "zz-fixture-warning-date /(skk-current-date nil nil t);(concat \"独立警告先頭\" \"独立警告末尾\")/",
        )
        for (source in invalid) {
            assertThrows(SkkDictionaryFormatException::class.java) {
                SkkDictionaryCodec.parseText("zz-fixture-warning-safe /独立警告正常候補/\n$source", policy = SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED)
            }
        }
    }

    @Test
    fun `抑止式の既知引用エスケープを評価せず候補単位で除外する`() {
        val document = SkkDictionaryCodec.parseText(
            "zz-fixture-warning-mixed /独立警告先頭/" +
                "(skk-ignore-dic-word \"独立警告末尾\\057\\073\\n\\r\\\\\\\"\")/独立警告正常候補/",
            policy = SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED,
        )
        assertEquals(listOf("独立警告先頭", "独立警告正常候補"), document.entries.single().candidates.map { it.text })
        assertEquals(1, document.diagnostics.warnings.skippedCandidateCount)
        assertEquals(mapOf(SkkDictionaryWarningReason.UNSUPPORTED_SUPPRESSION_EXPRESSION to 1), document.diagnostics.warnings.counts)
    }

    @Test
    fun `非対応注釈を持つ候補も除外件数に含め後続を保持する`() {
        val document = SkkDictionaryCodec.parseText(
            "zz-fixture-warning-mixed /独立警告先頭;(skk-current-date nil nil t)/独立警告末尾/",
            policy = SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED,
        )
        assertEquals(listOf("独立警告末尾"), document.entries.single().candidates.map { it.text })
        assertEquals(1, document.diagnostics.warnings.skippedCandidateCount)
        assertEquals(0, document.diagnostics.warnings.skippedEntryCount)
    }

    @Test
    fun `閉じ括弧のない通常文字列も厳密解析と警告解析で往復する`() {
        for (value in listOf("(zz-fixture-warning-unclosed-literal", "(skk-current-date zz-fixture-warning-literal")) {
            val entries = listOf(SkkDictionaryEntry("zz-fixture-warning-safe", listOf(SkkDictionaryCandidate(value))))
            val formatted = SkkDictionaryCodec.format(entries)
            for (policy in SkkDictionaryImportPolicy.values()) {
                assertEquals(entries, SkkDictionaryCodec.parseText(formatted, policy = policy).entries)
            }
        }
    }

    @Test
    fun `既知の日付式の保存されたコールバックを評価せず除外する`() {
        val callbacks = listOf(
            "(lambda (zz-fixture-warning-stamp) (list zz-fixture-warning-stamp))",
            "'(lambda (zz-fixture-warning-stamp) (list zz-fixture-warning-stamp))",
            "#'(lambda (zz-fixture-warning-stamp) (list zz-fixture-warning-stamp))",
            "'zz-fixture-warning-stamp",
        )
        for (callback in callbacks) {
            val document = SkkDictionaryCodec.parseText(
                "zz-fixture-warning-mixed /独立警告先頭/(skk-current-date $callback nil t)/独立警告末尾/",
                policy = SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED,
            )
            assertEquals(listOf("独立警告先頭", "独立警告末尾"), document.entries.single().candidates.map { it.text })
            assertEquals(1, document.diagnostics.warnings.skippedCandidateCount)
        }
    }

    @Test
    fun `日付コールバックの破損と読取特殊構文と構造上限を拒否する`() {
        val callbacks = listOf(
            "(lambda (zz-fixture-warning-stamp))",
            "(lambda (zz-fixture-warning-stamp) #.(progn t))",
            "(lambda (zz-fixture-warning-stamp) (nil . t))",
            "(lambda (zz-fixture-warning-stamp) \u0060nil)",
            "(lambda (zz-fixture-warning-stamp) ,nil)",
            "(lambda (zz-fixture-warning-stamp) " + "(".repeat(66) + "nil" + ")".repeat(66) + ")",
            "(lambda (zz-fixture-warning-stamp) " + "nil ".repeat(4097) + ")",
        )
        for (callback in callbacks) {
            val error = assertThrows(SkkDictionaryFormatException::class.java) {
                SkkDictionaryCodec.parseText(
                    "zz-fixture-warning-safe /独立警告正常候補/\nzz-fixture-warning-date /(skk-current-date $callback)/",
                    policy = SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED,
                )
            }
            assertEquals(SkkDictionaryError.UNSUPPORTED_EXPRESSION, error.error)
        }
    }

    @Test
    fun `警告サンプルの上限後も全件数を数える`() {
        val source = "zz-fixture-warning-safe /独立警告正常候補/\n" +
            (1..150).joinToString("\n") { "zz-fixture-warning-date /(skk-current-date nil nil t)/" }
        val warnings = SkkDictionaryCodec.parseText(source, policy = SkkDictionaryImportPolicy.SKIP_KNOWN_UNSUPPORTED).diagnostics.warnings
        assertEquals(150, warnings.count)
        assertEquals(150, warnings.skippedEntryCount)
        assertEquals(150, warnings.skippedCandidateCount)
        assertEquals(SkkDictionaryCodec.MAX_WARNING_SAMPLES, warnings.samples.size)
    }

    @Test
    fun `送り条件の構文文字と制御文字は書き出しと共通検証でも拒否する`() {
        for (condition in listOf("", "に/", "に[", "に]", "に\"", "に\t", "に\n", "に\u0000", "に\u007f", "に\ud800")) {
            val entry = SkkDictionaryEntry("zz-fixture-ddskk-invalid", listOf(SkkDictionaryCandidate("独立試験境界候補", okuriCondition = condition)))
            assertThrows(SkkDictionaryFormatException::class.java) {
                SkkDictionaryCodec.format(listOf(entry))
            }
            assertThrows(SkkDictionaryFormatException::class.java) {
                SkkDictionaryCodec.validateCandidateFields(entry.key, entry.candidates.single().text, null, condition)
            }
        }
    }

    @Test
    fun `空候補 制御文字 不正サロゲートを拒否する`() {
        assertThrows(SkkDictionaryFormatException::class.java) {
            SkkDictionaryCodec.parseText("かな //")
        }
        assertThrows(SkkDictionaryFormatException::class.java) {
            SkkDictionaryCodec.parseText("かな /候補\u0000/")
        }
        val invalidSurrogate = SkkDictionaryDocument(
            listOf(SkkDictionaryEntry("かな", listOf(SkkDictionaryCandidate("\ud800")))),
            SkkDictionaryEncoding.UTF8,
        )
        assertThrows(SkkDictionaryFormatException::class.java) {
            SkkDictionaryCodec.format(invalidSurrogate)
        }
    }

    @Test
    fun `無視するコメント内でも不正サロゲートを行番号付きで拒否する`() {
        val secret = "非公開コメント"
        val source = ";; $secret\n;; ignored \ud800\nかな /仮名/"

        val exception = assertThrows(SkkDictionaryFormatException::class.java) {
            SkkDictionaryCodec.parseText(source)
        }

        assertEquals(2, exception.lineNumber)
        assertEquals(SkkDictionaryError.INVALID_VALUE, exception.error)
        assertFalse(exception.message.orEmpty().contains(secret))
    }

    @Test
    fun `過大な行を行番号付きで拒否する`() {
        val source = ";; ok\nかな /${"あ".repeat(SkkDictionaryCodec.MAX_LINE_CHARS)}/"

        val exception = assertThrows(SkkDictionaryFormatException::class.java) {
            SkkDictionaryCodec.parseText(source)
        }

        assertEquals(2, exception.lineNumber)
        assertEquals(SkkDictionaryError.LINE_TOO_LONG, exception.error)
    }

    @Test
    fun `不正なバイト列は置換せず拒否する`() {
        val exception = assertThrows(SkkDictionaryFormatException::class.java) {
            SkkDictionaryCodec.parse(byteArrayOf(0xff.toByte()))
        }

        assertEquals(1, exception.lineNumber)
        assertEquals(SkkDictionaryError.DECODING_FAILED, exception.error)
    }

    @Test
    fun `入力リストを後から変更してもモデルは変わらない`() {
        val sourceCandidates = mutableListOf(SkkDictionaryCandidate("元"))
        val entry = SkkDictionaryEntry("もと", sourceCandidates)
        val sourceEntries = mutableListOf(entry)
        val document = SkkDictionaryDocument(sourceEntries, SkkDictionaryEncoding.UTF8)

        sourceCandidates += SkkDictionaryCandidate("追加")
        sourceEntries.clear()

        assertEquals(listOf(SkkDictionaryCandidate("元")), entry.candidates)
        assertEquals(listOf(entry), document.entries)
    }

    @Test
    fun `UTF-8 書き出しは同じ入力に同じバイト列を返す`() {
        val document = SkkDictionaryDocument(
            listOf(SkkDictionaryEntry("かな", listOf(SkkDictionaryCandidate("仮名")))),
            SkkDictionaryEncoding.EUC_JP,
        )

        assertArrayEquals(SkkDictionaryCodec.encodeUtf8(document), SkkDictionaryCodec.encodeUtf8(document))
    }
}
