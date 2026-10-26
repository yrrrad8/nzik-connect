package app.n_zik.android.connect

import java.nio.file.Files
import java.nio.file.Path

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class StreamConsumerTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `bounded requests preserve exact offsets and media bytes`() = runTest {
        val ranges = mutableListOf<LongRange?>()
        val consumer = StreamConsumer { _, range, receive ->
            ranges += range
            val requested = requireNotNull(range)
            val bytes = requested.map { it.toByte() }.toByteArray()
            receive(MediaResponse(206, "audio/webm", "bytes ${requested.first}-${requested.last}/6", bytes.size.toLong(), flowOf(bytes)))
        }
        val output = temp.resolve("media")
        assertEquals(6L, consumer.capture(rangeSource(), output, 8, 1))
        assertEquals(listOf(0L..2L, 3L..5L), ranges)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5), Files.readAllBytes(output))
    }

    @Test
    fun `unknown lengths and short final ranges terminate at the advertised total`() = runTest {
        listOf(null, 5L).forEachIndexed { index, length ->
            val ranges = mutableListOf<LongRange?>()
            val consumer = StreamConsumer { _, range, receive ->
                ranges += range
                val requested = requireNotNull(range)
                val end = minOf(requested.last, 4)
                val bytes = (requested.first..end).map { it.toByte() }.toByteArray()
                receive(MediaResponse(206, "audio/webm", "bytes ${requested.first}-$end/5", bytes.size.toLong(), flowOf(bytes)))
            }
            val source = ResolvedAudio("abcdefghijk", Transport.RANGE, "audio/webm", contentLength = length, rangeChunkSize = 3)
            val output = temp.resolve("short-$index")
            assertEquals(5L, consumer.capture(source, output, 8, 1))
            assertEquals(listOf(0L..2L, 3L..if (length == null) 5L else 4L), ranges)
            assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), Files.readAllBytes(output))
        }
        var request = 0
        val inconsistent = StreamConsumer { _, _, receive ->
            request++
            receive(if (request == 1) {
                MediaResponse(206, "audio/webm", "bytes 0-2/5", 3, flowOf(byteArrayOf(0, 1, 2)))
            } else {
                MediaResponse(206, "audio/webm", "bytes 3-5/6", 3, flowOf(byteArrayOf(3, 4, 5)))
            })
        }
        expectFailure(FailureCode.INVALID_MEDIA_RESPONSE) {
            inconsistent.capture(ResolvedAudio("abcdefghijk", Transport.RANGE, "audio/webm", rangeChunkSize = 3), temp.resolve("changed"), 8, 1)
        }
    }

    @Test
    fun `invalid ranges truncated bodies and HTML are rejected`() = runTest {
        val responses = listOf(
            MediaResponse(200, "audio/webm", null, 3, flowOf(byteArrayOf(0, 1, 2))),
            MediaResponse(206, "audio/webm", "bytes 1-3/6", 3, flowOf(byteArrayOf(1, 2, 3))),
            MediaResponse(206, "audio/webm", "bytes 0-2/6", 3, flowOf(byteArrayOf(0))),
            MediaResponse(206, "audio/webm", "bytes 0-2/6", 3, flowOf(byteArrayOf(0, 1, 2, 3))),
            MediaResponse(206, "text/html", "bytes 0-2/6", 3, flowOf(byteArrayOf(0, 1, 2))),
        )
        responses.forEachIndexed { index, response ->
            expectFailure(FailureCode.INVALID_MEDIA_RESPONSE) {
                StreamConsumer { _, _, receive -> receive(response) }.capture(rangeSource(), temp.resolve("bad-$index"), 8, 1)
            }
        }
    }

    @Test
    fun `SABR initialization and media retain order and cannot exceed budget`() = runTest {
        val consumer = StreamConsumer { _, _, _ -> throw AssertionError("SABR must use library chunks") }
        val source = ResolvedAudio("abcdefghijk", Transport.SABR, "audio/webm", chunks = flowOf(byteArrayOf(1, 2), byteArrayOf(3, 4, 5)))
        val output = temp.resolve("sabr")
        assertEquals(5L, consumer.capture(source, output, 8, 1))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), Files.readAllBytes(output))
        expectFailure(FailureCode.BYTE_BUDGET_EXCEEDED) { consumer.capture(source, temp.resolve("over-budget"), 4, 1) }
    }

    @Test
    fun `sampling and cancellation close the upstream flow`() = runTest {
        var closed = false
        val body = flow {
            try {
                emit(byteArrayOf(1, 2, 3, 4, 5))
                awaitCancellation()
            } finally {
                closed = true
            }
        }
        val consumer = StreamConsumer { _, _, receive -> receive(MediaResponse(200, "audio/webm", null, null, body)) }
        val source = ResolvedAudio("abcdefghijk", Transport.DIRECT, "audio/webm")
        assertEquals(4L, consumer.capture(source, temp.resolve("sample"), 4, 1))
        assertTrue(closed)
        closed = false
        val job = launch { consumer.capture(source, temp.resolve("cancel"), 100, 1) }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(closed)
    }

    @Test
    fun `rejected and empty sources have distinct failures`() = runTest {
        val source = ResolvedAudio("abcdefghijk", Transport.DIRECT, "audio/webm")
        expectFailure(FailureCode.SOURCE_REJECTED) {
            StreamConsumer { _, _, receive -> receive(MediaResponse(403, "text/html", null, null, emptyFlow())) }
                .capture(source, temp.resolve("rejected"), 8, 1)
        }
        expectFailure(FailureCode.EMPTY_MEDIA) {
            StreamConsumer { _, _, receive -> receive(MediaResponse(200, "audio/webm", null, null, emptyFlow())) }
                .capture(source, temp.resolve("empty"), 8, 1)
        }
    }

    private fun rangeSource() = ResolvedAudio("abcdefghijk", Transport.RANGE, "audio/webm", contentLength = 6, rangeChunkSize = 3)
}

internal suspend fun expectFailure(code: FailureCode, block: suspend () -> Unit) {
    try {
        block()
        throw AssertionError("Expected $code")
    } catch (error: ConnectFailure) {
        assertEquals(code, error.code)
    }
}
