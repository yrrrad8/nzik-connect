package app.n_zik.android.connect

import java.nio.file.Files
import java.nio.file.Path

import com.metrolist.innertubex.sabr.SabrProtocolException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SmokeRunnerTest {
    @TempDir
    lateinit var temp: Path

    private val options = SmokeOptions("query", seconds = 1, deadlineMs = 30_000)
    private val capture = AudioCapture { _, path, _, _ -> Files.write(path, byteArrayOf(1)); 1L }
    private val verifier = AudioVerifier { _, _, seconds, _ -> seconds * 16_000L }

    @Test
    fun `smoke searches and decodes the same track and removes media`() = runTest {
        val music = FakeMusic()
        val report = runner(music).run(options)
        assertEquals("pass", report.status)
        assertEquals(1_000L, report.decodedDurationMs)
        assertEquals(listOf("search:query", "metadata:abcdefghijk", "resolve:abcdefghijk"), music.calls)
        assertDirectoryEmpty()
    }

    @Test
    fun `expired source is refreshed once before consumption`() = runTest {
        val music = FakeMusic().apply {
            sources = listOf(source(expiresAt = 1), source())
        }
        val report = runner(music).run(options)
        assertTrue(report.refreshedSource)
        assertEquals(1, music.refreshes)
        assertEquals(2, music.resolutions)
        assertDirectoryEmpty()
    }

    @Test
    fun `repeated media rejection stops after one refresh`() = runTest {
        val music = FakeMusic()
        val rejected = AudioCapture { _, _, _, _ -> throw ConnectFailure(FailureCode.SOURCE_REJECTED) }
        expectFailure(FailureCode.SOURCE_REJECTED) { runner(music, rejected).run(options) }
        assertEquals(1, music.refreshes)
        assertEquals(2, music.resolutions)
        assertDirectoryEmpty()
    }

    @Test
    fun `rejected SABR chunks refresh once before a successful retry`() = runTest {
        val music = FakeMusic().apply {
            sources = listOf(
                ResolvedAudio("abcdefghijk", Transport.SABR, "audio/webm", chunks = flow<ByteArray> {
                    throw SabrProtocolException("rejected", httpStatusCode = 403)
                }.mapSabrFailures()),
                ResolvedAudio("abcdefghijk", Transport.SABR, "audio/webm", chunks = flowOf(byteArrayOf(1))),
            )
        }
        val consumer = StreamConsumer { _, _, _ -> throw AssertionError("SABR must use library chunks") }
        assertTrue(runner(music, consumer).run(options).refreshedSource)
        assertEquals(1, music.refreshes)
        assertEquals(2, music.resolutions)
        assertDirectoryEmpty()
    }

    @Test
    fun `empty search and unverified PCM never pass smoke`() = runTest {
        expectFailure(FailureCode.NO_MATCHES) { runner(FakeMusic().apply { empty = true }).run(options) }
        val emptyPcm = AudioVerifier { _, _, _, _ -> 0L }
        expectFailure(FailureCode.PCM_TOO_SHORT) {
            SmokeRunner(FakeMusic(), capture, emptyPcm, temporaryDirectory = temp).run(options)
        }
        assertDirectoryEmpty()
    }

    @Test
    fun `timeout cancels capture and deletes its temporary file`() = runTest {
        var closed = false
        val stalled = AudioCapture { _, _, _, _ ->
            try {
                awaitCancellation()
            } finally {
                closed = true
            }
        }
        try {
            runner(FakeMusic(), stalled).run(options)
            throw AssertionError("Expected timeout")
        } catch (_: TimeoutCancellationException) {
            assertTrue(closed)
        }
        assertDirectoryEmpty()
    }

    private fun runner(music: FakeMusic, consumer: AudioCapture = capture) =
        SmokeRunner(music, consumer, verifier, clock = { 10_000 }, temporaryDirectory = temp)

    private fun assertDirectoryEmpty() {
        Files.list(temp).use { assertEquals(0L, it.count()) }
    }

    private class FakeMusic : ConnectMusicService {
        val calls = mutableListOf<String>()
        var sources = listOf(source())
        var empty = false
        var refreshes = 0
        var resolutions = 0

        override suspend fun search(query: String): List<ConnectTrack> {
            calls += "search:$query"
            return if (empty) emptyList() else listOf(ConnectTrack("abcdefghijk", "Track", listOf("Artist")))
        }

        override suspend fun metadata(id: String): ConnectTrack {
            calls += "metadata:$id"
            return ConnectTrack(id, "Track", listOf("Artist"))
        }

        override suspend fun resolve(id: String, allowSabr: Boolean): ResolvedAudio {
            calls += "resolve:$id"
            return sources[minOf(resolutions++, sources.lastIndex)]
        }

        override suspend fun refresh() { refreshes++ }
    }

    companion object {
        private fun source(expiresAt: Long? = null) =
            ResolvedAudio("abcdefghijk", Transport.DIRECT, "audio/webm", expiresAtEpochMs = expiresAt)
    }
}
