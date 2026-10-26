package app.n_zik.android.connect

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class MediaResponse(
    val status: Int,
    val contentType: String?,
    val contentRange: String?,
    val contentLength: Long?,
    val body: Flow<ByteArray>,
)

internal fun interface DirectFetcher {
    suspend fun fetch(source: ResolvedAudio, range: LongRange?, receive: suspend (MediaResponse) -> Unit)
}

internal class KtorDirectFetcher(private val http: HttpClient) : DirectFetcher {
    override suspend fun fetch(source: ResolvedAudio, range: LongRange?, receive: suspend (MediaResponse) -> Unit) {
        val url = source.url ?: throw ConnectFailure(FailureCode.INVALID_MEDIA_RESPONSE)
        validateMediaUrl(url)
        http.prepareGet(url) {
            source.headers.forEach { (name, value) -> header(name, value) }
            headers.remove(HttpHeaders.Range)
            headers.remove(HttpHeaders.AcceptEncoding)
            headers.remove(HttpHeaders.Accept)
            header(HttpHeaders.AcceptEncoding, "identity")
            header(HttpHeaders.Accept, "*/*")
            range?.let { header(HttpHeaders.Range, "bytes=${it.first}-${it.last}") }
        }.execute { response ->
            val channel = response.bodyAsChannel()
            receive(
                MediaResponse(
                    status = response.status.value,
                    contentType = response.headers[HttpHeaders.ContentType],
                    contentRange = response.headers[HttpHeaders.ContentRange],
                    contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                    body = flow {
                        val buffer = ByteArray(16 * 1024)
                        try {
                            while (true) {
                                val count = channel.readAvailable(buffer)
                                if (count < 0) break
                                if (count > 0) emit(buffer.copyOf(count))
                            }
                        } finally {
                            channel.cancel()
                        }
                    },
                ),
            )
        }
    }
}

internal class StreamConsumer(private val fetcher: DirectFetcher) : AudioCapture {
    override suspend fun capture(source: ResolvedAudio, target: Path, maxBytes: Long, seconds: Int): Long {
        requireConnect(maxBytes > 0 && seconds > 0)
        if (source.contentLength != null && source.contentLength <= 0) invalidMedia()
        val desired = maxOf(512L * 1024, (source.bitrate?.toLong() ?: 512_000L) * seconds / 4 + 256L * 1024)
        val goal = minOf(maxBytes, desired, source.contentLength ?: Long.MAX_VALUE)
        var written = 0L
        Files.newOutputStream(target).use { output ->
            fun append(bytes: ByteArray) {
                if (bytes.size.toLong() > maxBytes - written) throw ConnectFailure(FailureCode.BYTE_BUDGET_EXCEEDED)
                output.write(bytes)
                written += bytes.size
            }
            try {
                when (source.transport) {
                    Transport.SABR -> {
                        val chunks = source.chunks ?: invalidMedia()
                        chunks.collect { bytes ->
                            currentCoroutineContext().ensureActive()
                            append(bytes)
                            if (written >= goal) throw CaptureComplete()
                        }
                    }
                    Transport.DIRECT -> fetcher.fetch(source, null) { response ->
                        validateType(response, source.mimeType)
                        if (response.status != 200) invalidMedia()
                        response.body.collect { bytes ->
                            currentCoroutineContext().ensureActive()
                            val count = minOf(bytes.size.toLong(), goal - written).toInt()
                            append(bytes.copyOf(count))
                            if (written >= goal) throw CaptureComplete()
                        }
                    }
                    Transport.RANGE -> {
                        if (source.rangeChunkSize <= 0) invalidMedia()
                        var totalLength = source.contentLength
                        while (written < minOf(goal, totalLength ?: Long.MAX_VALUE)) {
                            val start = written
                            val end = minOf(goal - 1, start + minOf(source.rangeChunkSize, 1024L * 1024) - 1)
                            fetcher.fetch(source, start..end) { response ->
                                validateType(response, source.mimeType)
                                val range = validateRange(response, start..end, totalLength)
                                totalLength = range.total
                                response.body.collect { bytes ->
                                    currentCoroutineContext().ensureActive()
                                    if (bytes.size.toLong() > range.end - written + 1) invalidMedia()
                                    append(bytes)
                                }
                                if (written != range.end + 1) invalidMedia()
                            }
                        }
                    }
                }
            } catch (_: CaptureComplete) {
                // Completing the sample unwinds the response/Flow and closes its network resources.
            }
        }
        if (written == 0L) throw ConnectFailure(FailureCode.EMPTY_MEDIA)
        return written
    }
}

private class CaptureComplete : RuntimeException(null, null, false, false)

private data class ContentRange(val end: Long, val total: Long)

private fun validateType(response: MediaResponse, expected: String) {
    if (response.status == 401 || response.status == 403 || response.status == 410) {
        throw ConnectFailure(FailureCode.SOURCE_REJECTED)
    }
    val type = response.contentType?.substringBefore(';')?.trim()?.lowercase()
    if (type !in setOf(expected, "application/octet-stream")) invalidMedia()
}

private fun validateRange(response: MediaResponse, requested: LongRange, knownLength: Long?): ContentRange {
    if (response.status != 206) invalidMedia()
    val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.contentRange.orEmpty()) ?: invalidMedia()
    val start = match.groupValues[1].toLongOrNull() ?: invalidMedia()
    val end = match.groupValues[2].toLongOrNull() ?: invalidMedia()
    val total = match.groupValues[3].toLongOrNull() ?: invalidMedia()
    if (total <= 0 || start != requested.first || end != minOf(requested.last, total - 1) || end < start) invalidMedia()
    if (knownLength != null && total != knownLength) invalidMedia()
    if (response.contentLength != null && response.contentLength != end - start + 1) invalidMedia()
    return ContentRange(end, total)
}

private fun invalidMedia(): Nothing = throw ConnectFailure(FailureCode.INVALID_MEDIA_RESPONSE)

internal fun validateMediaUrl(url: String) {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        invalidMedia()
    }
    if (uri.scheme != "https" || uri.host?.endsWith(".googlevideo.com") != true || uri.userInfo != null) invalidMedia()
}
