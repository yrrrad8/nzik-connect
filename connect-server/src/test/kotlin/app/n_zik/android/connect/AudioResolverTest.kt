package app.n_zik.android.connect

import java.net.SocketTimeoutException

import com.metrolist.innertubex.sabr.SabrProtocolException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioResolverTest {
    @Test
    fun `reset replaces extraction state and waits for active extraction`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sessions = mutableListOf<FakeSession>()
        val resolver = ResettableAudioResolver {
            FakeSession().also { sessions += it }
        }
        resolver.resolve("abcdefghijk", allowSabr = false)
        sessions.single().beforeResolve = { entered.complete(Unit); release.await() }
        val active = async { resolver.resolve("abcdefghijk", allowSabr = false) }
        entered.await()
        val reset = async { resolver.reset() }
        runCurrent()
        assertFalse(reset.isCompleted)
        assertFalse(sessions.single().closed)
        release.complete(Unit)
        active.await()
        reset.await()
        assertTrue(sessions.single().closed)
        resolver.resolve("abcdefghijk", allowSabr = true)
        assertEquals(2, sessions.size)
        assertEquals(listOf(false, false), sessions.first().capabilities)
        assertEquals(listOf(true), sessions.last().capabilities)
        resolver.close()
        assertTrue(sessions.all { it.closed })
    }

    @Test
    fun `consumer capabilities reach the library hints`() {
        assertFalse(extractionHints(false).allowSabr)
        assertTrue(extractionHints(true).allowSabr)
        assertFalse(extractionHints(true).allowHls)
        assertTrue(extractionHints(false).allowBoundedRange)
    }

    @Test
    fun `SABR rejections are mapped without swallowing other failures or cancellation`() = runTest {
        listOf(401, 403, 410).forEach { status ->
            expectFailure(FailureCode.SOURCE_REJECTED) {
                flow<ByteArray> { throw IllegalStateException("wrapped", SabrProtocolException("rejected", httpStatusCode = status)) }
                    .mapSabrFailures().collect()
            }
        }
        listOf(SabrProtocolException("other", httpStatusCode = 500), CancellationException("cancelled")).forEach { expected ->
            try {
                flow<ByteArray> { throw expected }.mapSabrFailures().collect()
                throw AssertionError("Expected original failure")
            } catch (actual: Exception) {
                assertSame(expected, actual)
            }
        }
    }

    @Test
    fun `HTTP timeouts retain their classification at the music service boundary`() = runTest {
        listOf(
            SocketTimeoutException("test socket timeout"),
            ConnectTimeoutException("test connect timeout"),
            HttpRequestTimeoutException("https://example.invalid", 1),
        ).forEach { timeout ->
            expectFailure(FailureCode.TIMEOUT) {
                musicRequest(FailureCode.EXTRACTION_FAILED) { throw timeout }
            }
        }
    }

    private class FakeSession : AudioExtractionSession {
        var closed = false
        var beforeResolve: suspend () -> Unit = {}
        val capabilities = mutableListOf<Boolean>()
        override suspend fun resolve(id: String, allowSabr: Boolean): ResolvedAudio {
            check(!closed)
            beforeResolve()
            capabilities += allowSabr
            return ResolvedAudio(id, Transport.DIRECT, "audio/webm")
        }
        override suspend fun close() { closed = true }
    }
}
