package app.n_zik.android.connect

import java.nio.file.Files
import java.nio.file.Path

import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KtorDirectFetcherTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `real HTTP adapter forwards media headers and overrides range and encoding`() = testApplication {
        val ranges = mutableListOf<String>()
        externalServices {
            hosts("https://r1.googlevideo.com") {
                routing {
                    get("/videoplayback") {
                        assertEquals("required-value", call.request.headers["X-Media-Test"])
                        assertEquals("identity", call.request.headers[HttpHeaders.AcceptEncoding])
                        assertEquals("*/*", call.request.headers[HttpHeaders.Accept])
                        val range = requireNotNull(call.request.headers[HttpHeaders.Range])
                        ranges += range
                        val start = if (range == "bytes=0-2") 0 else 3
                        assertEquals("bytes=$start-${start + 2}", range)
                        call.response.header(HttpHeaders.ContentRange, "bytes $start-${start + 2}/6")
                        call.respondBytes(ByteArray(3) { (start + it).toByte() }, ContentType.parse("audio/webm"), HttpStatusCode.PartialContent)
                    }
                }
            }
        }
        val source = ResolvedAudio(
            "abcdefghijk", Transport.RANGE, "audio/webm", url = "https://r1.googlevideo.com/videoplayback",
            headers = mapOf("X-Media-Test" to "required-value", "Range" to "bytes=99-100", "Accept-Encoding" to "gzip", "Accept" to "application/json"),
            contentLength = 6, rangeChunkSize = 3,
        )
        val output = temp.resolve("range.media")
        assertEquals(6L, StreamConsumer(KtorDirectFetcher(client)).capture(source, output, 8, 1))
        assertEquals(listOf("bytes=0-2", "bytes=3-5"), ranges)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5), Files.readAllBytes(output))
    }

    @Test
    fun `sample completion and cancellation finish the actual HTTP response job`() = testApplication {
        var responseJob: Job? = null
        externalServices {
            hosts("https://r1.googlevideo.com") {
                routing {
                    get("/videoplayback") {
                        call.respondBytes(byteArrayOf(0, 1, 2, 3, 4, 5), ContentType.parse("audio/webm"))
                    }
                }
            }
        }
        val http = createClient {
            HttpResponseValidator { validateResponse { responseJob = it.coroutineContext.job } }
        }
        val source = ResolvedAudio("abcdefghijk", Transport.DIRECT, "audio/webm", url = "https://r1.googlevideo.com/videoplayback")
        val fetcher = KtorDirectFetcher(http)
        assertEquals(4L, StreamConsumer(fetcher).capture(source, temp.resolve("sample.media"), 4, 1))
        assertTrue(requireNotNull(responseJob).isCompleted)
        val entered = CompletableDeferred<Unit>()
        coroutineScope {
            val job = launch {
                fetcher.fetch(source, null) { response ->
                    response.body.collect { entered.complete(Unit); awaitCancellation() }
                }
            }
            withTimeout(2_000) { entered.await() }
            job.cancelAndJoin()
        }
        assertTrue(requireNotNull(responseJob).isCompleted)
    }
}
