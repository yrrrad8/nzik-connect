package app.n_zik.android.connect

import java.net.SocketTimeoutException

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ConnectApiTest {
    private val token = "test-only-bearer-token-01234567890123456789"

    @Test
    fun `every endpoint rejects absent and incorrect authentication before music calls`() = testApplication {
        val music = FakeMusic()
        application { connectApi(music, token) }
        listOf("/v1/capabilities", "/v1/search?q=query", "/v1/tracks/abcdefghijk", "/v1/tracks/abcdefghijk/source").forEach { path ->
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
            assertEquals(HttpStatusCode.Unauthorized, client.get(path) { header(HttpHeaders.Authorization, "Bearer ${"x".repeat(40)}") }.status)
        }
        assertEquals(0, music.calls)
    }

    @Test
    fun `authenticated search metadata and capabilities serialize truthfully`() = testApplication {
        val music = FakeMusic().apply {
            tracks = tracks + (1..24).map { ConnectTrack("track%06d".format(it), "Track $it", listOf("Artist")) }
        }
        application { connectApi(music, token) }
        val search = client.get("/v1/search?q=query&limit=1") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, search.status)
        assertEquals("no-store", search.headers[HttpHeaders.CacheControl])
        val tracks = Json.decodeFromString<List<ConnectTrack>>(search.bodyAsText())
        assertEquals("abcdefghijk", tracks.single().id)
        val defaults = client.get("/v1/search?q=query") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(music.tracks.take(20), Json.decodeFromString<List<ConnectTrack>>(defaults.bodyAsText()))
        val metadata = client.get("/v1/tracks/abcdefghijk") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(tracks.single(), Json.decodeFromString<ConnectTrack>(metadata.bodyAsText()))
        val capabilities = client.get("/v1/capabilities") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(listOf(Transport.DIRECT, Transport.RANGE), Json.decodeFromString<ApiCapabilities>(capabilities.bodyAsText()).sourceTransports)
    }

    @Test
    fun `malformed query limit and track ID never reach the service`() = testApplication {
        val music = FakeMusic()
        application { connectApi(music, token) }
        listOf("/v1/search?q=", "/v1/search?q=query&limit=no", "/v1/search?q=query&limit=0", "/v1/tracks/invalid/source").forEach { path ->
            assertEquals(HttpStatusCode.BadRequest, client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        }
        assertEquals(0, music.calls)
    }

    @Test
    fun `source retains bounded-range requirements without logging credentials`() = testApplication {
        val music = FakeMusic().apply { sources = listOf(source(Transport.RANGE)) }
        application { connectApi(music, token) }
        val response = client.get("/v1/tracks/abcdefghijk/source") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, response.status)
        val descriptor = Json.decodeFromString<SourceDescriptor>(response.bodyAsText())
        assertEquals(true, descriptor.requireBoundedRange)
        assertEquals(512L * 1024, descriptor.rangeChunkSizeBytes)
        assertEquals(mapOf("User-Agent" to "test-agent"), descriptor.headers)
        assertFalse(descriptor.toString().contains("videoplayback"))
        assertFalse(response.bodyAsText().contains(token))
        assertEquals(listOf(false), music.sabrCapabilities)
    }

    @Test
    fun `expired API source is resolved again once and never returned stale`() = testApplication {
        val music = FakeMusic().apply { sources = listOf(source(expiresAt = 1), source(expiresAt = 1)) }
        application { connectApi(music, token, clock = { 10_000 }) }
        val response = client.get("/v1/tracks/abcdefghijk/source") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(2, music.resolutions)
        assertEquals(1, music.refreshes)
        assertEquals(listOf(false, false), music.sabrCapabilities)
    }

    @Test
    fun `SABR source is explicitly unsupported by the initial external API`() = testApplication {
        val music = FakeMusic().apply { sources = listOf(source(Transport.SABR)) }
        application { connectApi(music, token) }
        val response = client.get("/v1/tracks/abcdefghijk/source") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.NotImplemented, response.status)
        assertFalse(response.bodyAsText().contains("https://"))
    }

    @Test
    fun `short tokens and non-media URLs are rejected`() {
        assertThrows(ConnectFailure::class.java) { validateApiToken("short") }
        assertThrows(ConnectFailure::class.java) { validateMediaUrl("http://127.0.0.1/private") }
    }

    @Test
    fun `upstream HTTP timeout returns gateway timeout`() = testApplication {
        val music = FakeMusic().apply {
            searchAction = { musicRequest(FailureCode.SEARCH_FAILED) { throw SocketTimeoutException("test-only") } }
        }
        application { connectApi(music, token) }
        val response = client.get("/v1/search?q=query") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.GatewayTimeout, response.status)
        assertEquals("{\"reason\":\"TIMEOUT\"}", response.bodyAsText())
    }

    @Test
    fun `request deadline cancels a stalled service and returns gateway timeout`() = testApplication {
        val closed = CompletableDeferred<Unit>()
        val music = FakeMusic().apply {
            searchAction = {
                try { awaitCancellation() } finally { closed.complete(Unit) }
            }
        }
        application { connectApi(music, token, requestTimeoutMs = 100) }
        val response = client.get("/v1/search?q=query") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.GatewayTimeout, response.status)
        withTimeout(2_000) { closed.await() }
    }

    @Test
    fun `server request cancellation escapes error mapping and cancels the service`() = testApplication {
        val entered = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val requestJob = CompletableDeferred<Job>()
        val escaped = CompletableDeferred<Pair<CancellationException, HttpStatusCode?>>()
        val music = FakeMusic().apply {
            searchAction = {
                entered.complete(Unit)
                try { awaitCancellation() } finally { closed.complete(Unit) }
            }
        }
        application {
            intercept(ApplicationCallPipeline.Setup) {
                requestJob.complete(currentCoroutineContext().job)
                try {
                    proceed()
                } catch (error: CancellationException) {
                    escaped.complete(error to call.response.status())
                    throw error
                }
            }
            connectApi(music, token)
        }
        coroutineScope {
            val request = async {
                runCatching { client.get("/v1/search?q=query") { header(HttpHeaders.Authorization, "Bearer $token") } }
            }
            try {
                withTimeout(2_000) { entered.await() }
                // Test-host request jobs are parented to the engine, independently of the client job.
                withTimeout(2_000) { requestJob.await() }.cancel()
                withTimeout(2_000) { closed.await() }
                val (_, statusAtEscape) = withTimeout(2_000) { escaped.await() }
                assertNull(statusAtEscape)
            } finally {
                request.cancelAndJoin()
            }
        }
    }

    private class FakeMusic : ConnectMusicService {
        var calls = 0
        var resolutions = 0
        var refreshes = 0
        var sources = listOf(source())
        var tracks = listOf(ConnectTrack("abcdefghijk", "Track", listOf("Artist")))
        var searchAction: suspend (String) -> List<ConnectTrack> = { tracks }
        val sabrCapabilities = mutableListOf<Boolean>()

        override suspend fun search(query: String): List<ConnectTrack> {
            calls++
            return searchAction(query)
        }
        override suspend fun metadata(id: String): ConnectTrack {
            calls++
            return ConnectTrack(id, "Track", listOf("Artist"))
        }
        override suspend fun resolve(id: String, allowSabr: Boolean): ResolvedAudio {
            calls++
            sabrCapabilities += allowSabr
            return sources[minOf(resolutions++, sources.lastIndex)]
        }
        override suspend fun refresh() { calls++; refreshes++ }
    }

    companion object {
        private fun source(transport: Transport = Transport.DIRECT, expiresAt: Long? = null) = ResolvedAudio(
            id = "abcdefghijk",
            transport = transport,
            mimeType = "audio/webm",
            url = "https://r1.googlevideo.com/videoplayback?test-only=true",
            headers = mapOf("User-Agent" to "test-agent"),
            expiresAtEpochMs = expiresAt,
        )
    }
}
