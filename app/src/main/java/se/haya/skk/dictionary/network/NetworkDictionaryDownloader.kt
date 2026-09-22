package se.haya.skk.dictionary.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import java.util.zip.GZIPInputStream
import se.haya.skk.core.dictionary.SkkDictionaryCodec

enum class NetworkDictionaryFailure {
    INVALID_URL,
    PRIVATE_ADDRESS,
    REDIRECT_LIMIT,
    HTTP_ERROR,
    COMPRESSED_TOO_LARGE,
    DECOMPRESSED_TOO_LARGE,
    CANCELLED,
    IO,
}

class NetworkDictionaryException(
    val failure: NetworkDictionaryFailure,
    cause: Throwable? = null,
) : IOException(failure.name, cause)

data class NetworkDictionaryDownload(val bytes: ByteArray, val finalUrl: String)

class NetworkDictionaryCancellation {
    @Volatile private var cancelled = false
    private var connection: HttpURLConnection? = null

    val isCancelled: Boolean get() = cancelled

    @Synchronized internal fun attach(next: HttpURLConnection) {
        if (cancelled) {
            next.disconnect()
            throw NetworkDictionaryException(NetworkDictionaryFailure.CANCELLED)
        }
        connection = next
    }

    @Synchronized internal fun detach(current: HttpURLConnection) {
        if (connection === current) connection = null
    }

    @Synchronized fun cancel() {
        cancelled = true
        connection?.disconnect()
        connection = null
    }

    internal fun check() {
        if (cancelled || Thread.currentThread().isInterrupted) {
            throw NetworkDictionaryException(NetworkDictionaryFailure.CANCELLED)
        }
    }
}

class NetworkDictionaryDownloader internal constructor(
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val resolveAddresses: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val maxRedirects: Int = 5,
    private val maxBytes: Int = SkkDictionaryCodec.MAX_FILE_BYTES,
) {
    fun download(rawUrl: String, cancellation: NetworkDictionaryCancellation): NetworkDictionaryDownload {
        var url = validate(rawUrl)
        repeat(maxRedirects + 1) { redirectCount ->
            cancellation.check()
            validateResolvedAddresses(url)
            val connection = try {
                openConnection(url)
            } catch (error: NetworkDictionaryException) {
                throw error
            } catch (error: Exception) {
                throw NetworkDictionaryException(NetworkDictionaryFailure.IO, error)
            }
            cancellation.attach(connection)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.useCaches = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "skk-android/1")
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    if (redirectCount == maxRedirects) {
                        throw NetworkDictionaryException(NetworkDictionaryFailure.REDIRECT_LIMIT)
                    }
                    val location = connection.getHeaderField("Location")
                        ?: throw NetworkDictionaryException(NetworkDictionaryFailure.HTTP_ERROR)
                    url = validate(url.resolve(location).toString())
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw NetworkDictionaryException(NetworkDictionaryFailure.HTTP_ERROR)
                }
                val contentLength = connection.contentLengthLong
                if (contentLength > maxBytes) {
                    throw NetworkDictionaryException(NetworkDictionaryFailure.COMPRESSED_TOO_LARGE)
                }
                val encoding = connection.contentEncoding?.lowercase(Locale.ROOT)
                if (encoding != null && encoding != "identity" && encoding != "gzip") {
                    throw NetworkDictionaryException(NetworkDictionaryFailure.IO)
                }
                val compressed = connection.inputStream.use { input ->
                    readLimited(input, maxBytes, NetworkDictionaryFailure.COMPRESSED_TOO_LARGE, cancellation)
                }
                val gzip = encoding == "gzip" || compressed.isGzip()
                val bytes = if (gzip) {
                    try {
                        GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
                            readLimited(input, maxBytes, NetworkDictionaryFailure.DECOMPRESSED_TOO_LARGE, cancellation)
                        }
                    } catch (error: NetworkDictionaryException) {
                        throw error
                    } catch (error: IOException) {
                        throw NetworkDictionaryException(NetworkDictionaryFailure.IO, error)
                    }
                } else compressed
                return NetworkDictionaryDownload(bytes, url.toASCIIString())
            } catch (error: NetworkDictionaryException) {
                throw error
            } catch (error: IOException) {
                cancellation.check()
                throw NetworkDictionaryException(NetworkDictionaryFailure.IO, error)
            } finally {
                cancellation.detach(connection)
                connection.disconnect()
            }
        }
        throw NetworkDictionaryException(NetworkDictionaryFailure.REDIRECT_LIMIT)
    }

    private fun validate(raw: String): URI {
        val uri = try { URI(raw.trim()) } catch (error: Exception) {
            throw NetworkDictionaryException(NetworkDictionaryFailure.INVALID_URL, error)
        }
        if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.host.isNullOrBlank() ||
            uri.userInfo != null || uri.fragment != null || uri.rawQuery?.length.orZero() > MAX_QUERY_CHARS
        ) {
            throw NetworkDictionaryException(NetworkDictionaryFailure.INVALID_URL)
        }
        return uri.normalize()
    }

    private fun validateResolvedAddresses(uri: URI) {
        val addresses = try { resolveAddresses(requireNotNull(uri.host)) } catch (error: Exception) {
            throw NetworkDictionaryException(NetworkDictionaryFailure.IO, error)
        }
        if (addresses.isEmpty() || addresses.any { !it.isPublicNetworkAddress() }) {
            throw NetworkDictionaryException(NetworkDictionaryFailure.PRIVATE_ADDRESS)
        }
    }

    private fun readLimited(
        input: InputStream,
        limit: Int,
        failure: NetworkDictionaryFailure,
        cancellation: NetworkDictionaryCancellation,
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            cancellation.check()
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (count == 0) continue
            if (total > limit - count) throw NetworkDictionaryException(failure)
            output.write(buffer, 0, count)
            total += count
        }
    }

    private fun ByteArray.isGzip(): Boolean = size >= 2 && this[0] == 0x1f.toByte() && this[1] == 0x8b.toByte()

    private fun InetAddress.isPublicNetworkAddress(): Boolean {
        if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) return false
        val value = address
        if (value.size == 16) return (value[0].toInt() and 0xfe) != 0xfc
        if (value.size != 4) return false
        val first = value[0].toInt() and 0xff
        val second = value[1].toInt() and 0xff
        return !(first == 0 || first == 10 || first == 127 || first >= 224 ||
            first == 169 && second == 254 || first == 172 && second in 16..31 ||
            first == 192 && second == 168 || first == 100 && second in 64..127 ||
            first == 192 && second == 0 || first == 192 && second == 2 ||
            first == 198 && second in 18..19 || first == 198 && second == 51 ||
            first == 203 && second == 0)
    }

    private fun Int?.orZero(): Int = this ?: 0

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        const val MAX_QUERY_CHARS = 4_096
    }
}
