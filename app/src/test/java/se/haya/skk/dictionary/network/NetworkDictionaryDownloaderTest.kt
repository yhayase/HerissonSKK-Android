package se.haya.skk.dictionary.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkDictionaryDownloaderTest {
    private val publicAddress = arrayOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)))

    @Test fun `plain と gzip を復号して上限内の本文を返す`() {
        val plain = "かな /仮名/\n".toByteArray()
        val gzip = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(plain) }
        }.toByteArray()
        for (body in listOf(plain, gzip)) {
            val downloader = downloader(mapOf("https://example.com/dict" to Response(200, body)))
            assertArrayEquals(plain, downloader.download("https://example.com/dict", NetworkDictionaryCancellation()).bytes)
        }
    }

    @Test fun `転送先も HTTPS と公開アドレスを検査する`() {
        val seen = mutableListOf<String>()
        val downloader = NetworkDictionaryDownloader(
            openConnection = { uri ->
                seen += uri.toString()
                FakeConnection(uri.toURL(), if (seen.size == 1) Response(302, location = "https://cdn.example.net/dict") else Response(200, "ok".toByteArray()))
            },
            resolveAddresses = { publicAddress },
        )
        val result = downloader.download("https://example.com/start", NetworkDictionaryCancellation())
        assertEquals("https://cdn.example.net/dict", result.finalUrl)
        assertEquals(listOf("https://example.com/start", "https://cdn.example.net/dict"), seen)

        val downgrade = downloader(mapOf("https://example.com/start" to Response(302, location = "http://example.com/dict")))
        assertFailure(NetworkDictionaryFailure.INVALID_URL) {
            downgrade.download("https://example.com/start", NetworkDictionaryCancellation())
        }
    }

    @Test fun `認証情報 fragment と private address を拒否する`() {
        listOf(
            "http://example.com/dict",
            "https://user:secret@example.com/dict",
            "https://example.com/dict#part",
        ).forEach { url ->
            assertFailure(NetworkDictionaryFailure.INVALID_URL) {
                downloader(emptyMap()).download(url, NetworkDictionaryCancellation())
            }
        }
        val privateDownloader = NetworkDictionaryDownloader(
            openConnection = { error("接続してはいけません") },
            resolveAddresses = { arrayOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))) },
        )
        assertFailure(NetworkDictionaryFailure.PRIVATE_ADDRESS) {
            privateDownloader.download("https://example.com/dict", NetworkDictionaryCancellation())
        }
    }

    @Test fun `圧縮本文と展開本文の上限超過を区別する`() {
        val compressed = downloader(
            mapOf("https://example.com/dict" to Response(200, ByteArray(9))),
            maxBytes = 8,
        )
        assertFailure(NetworkDictionaryFailure.COMPRESSED_TOO_LARGE) {
            compressed.download("https://example.com/dict", NetworkDictionaryCancellation())
        }

        val gzip = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(ByteArray(100)) }
        }.toByteArray()
        val expanded = downloader(
            mapOf("https://example.com/dict" to Response(200, gzip, declaredLength = -1)),
            maxBytes = 30,
        )
        assertFailure(NetworkDictionaryFailure.DECOMPRESSED_TOO_LARGE) {
            expanded.download("https://example.com/dict", NetworkDictionaryCancellation())
        }
    }

    @Test fun `開始前の取消は接続しない`() {
        val cancellation = NetworkDictionaryCancellation().apply { cancel() }
        assertFailure(NetworkDictionaryFailure.CANCELLED) {
            downloader(emptyMap()).download("https://example.com/dict", cancellation)
        }
    }

    private fun downloader(responses: Map<String, Response>, maxBytes: Int = 1024) = NetworkDictionaryDownloader(
        openConnection = { uri -> FakeConnection(uri.toURL(), requireNotNull(responses[uri.toString()])) },
        resolveAddresses = { publicAddress },
        maxBytes = maxBytes,
    )

    private fun assertFailure(expected: NetworkDictionaryFailure, block: () -> Unit) {
        assertEquals(expected, assertThrows(NetworkDictionaryException::class.java) { block() }.failure)
    }

    private data class Response(
        val code: Int,
        val body: ByteArray = ByteArray(0),
        val location: String? = null,
        val declaredLength: Long = body.size.toLong(),
    )

    private class FakeConnection(url: URL, private val response: Response) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = response.code
        override fun getInputStream() = ByteArrayInputStream(response.body)
        override fun getContentLengthLong(): Long = response.declaredLength
        override fun getHeaderField(name: String?): String? = if (name == "Location") response.location else null
    }
}
