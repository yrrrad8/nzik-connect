package app.n_zik.android.connect

import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class PcmVerifierTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `real FFmpeg decodes local WebM and MP4 audio without network access`() {
        listOf("webm" to "libopus", "mp4" to "aac").forEach { (format, codec) ->
            val media = temp.resolve("sine.$format")
            val diagnostics = temp.resolve("encoder-$format.log")
            val encoder = ProcessBuilder(
                "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "fatal",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:a", codec,
                "-f", format, media.toString(),
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(diagnostics.toFile()).start()
            try {
                assertTrue(encoder.waitFor(15, TimeUnit.SECONDS), "Local fixture encoding timed out")
                assertEquals(0, encoder.exitValue()) {
                    "Local $format fixture encoding failed: ${Files.readString(diagnostics).take(4096)}"
                }
                // JUnit's synchronous entry point must wait for a real OS process, without virtual time.
                val pcmBytes = runBlocking { PcmVerifier().verify(media, "audio/$format", 1, "ffmpeg") }
                // FFmpeg versions round resampling boundaries differently; allow up to 10 ms.
                assertTrue(pcmBytes in 15_840L..16_000L, "Decoded fixture must cover at least 990 ms")
            } finally {
                if (encoder.isAlive) {
                    encoder.destroyForcibly()
                    encoder.waitFor(2, TimeUnit.SECONDS)
                }
            }
        }
    }

    @Test
    fun `decoder receives only local media and its artifacts are removed`() = runTest {
        val artifacts = mutableListOf<Path>()
        val verifier = PcmVerifier { command, pcm, diagnostics ->
            assertTrue(command.contains("file,pipe"))
            assertTrue(command.none { it.contains("://") })
            artifacts += listOf(pcm, diagnostics)
            Files.write(pcm, ByteArray(16_000))
            FinishedProcess()
        }
        assertEquals(16_000L, verifier.verify(temp.resolve("local.media"), "audio/webm", 1, "ffmpeg"))
        assertTrue(artifacts.all { !Files.exists(it) })
    }

    @Test
    fun `successful process with empty output and failed process cannot prove audio`() = runTest {
        expectFailure(FailureCode.PCM_TOO_SHORT) {
            PcmVerifier { _, _, _ -> FinishedProcess() }.verify(temp.resolve("local.media"), "audio/webm", 1, "ffmpeg")
        }
        expectFailure(FailureCode.DECODE_FAILED) {
            PcmVerifier { _, _, _ -> FinishedProcess(1) }.verify(temp.resolve("local.media"), "audio/mp4", 1, "ffmpeg")
        }
        assertThrows(ConnectFailure::class.java) { verifyPcmBytes(1, 1) }
    }

    @Test
    fun `cancellation terminates decoder and removes files`() = runTest {
        val process = FinishedProcess(alive = true)
        val artifacts = mutableListOf<Path>()
        val verifier = PcmVerifier { _, pcm, diagnostics ->
            artifacts += listOf(pcm, diagnostics)
            process
        }
        val job = launch { verifier.verify(temp.resolve("local.media"), "audio/webm", 1, "ffmpeg") }
        runCurrent()
        job.cancelAndJoin()
        assertFalse(process.isAlive)
        assertTrue(process.destroyed)
        assertTrue(artifacts.all { !Files.exists(it) })
    }

    @Test
    fun `transient file locks are retried before successful cleanup`() = runTest {
        val attempts = linkedMapOf<Path, Int>()
        val verifier = PcmVerifier(
            deleteFile = { path ->
                val count = attempts.getOrDefault(path, 0) + 1
                attempts[path] = count
                if (path.toString().endsWith(".raw") && count == 1) throw FileSystemException(path.toString())
                Files.deleteIfExists(path)
            },
            launcher = DecoderLauncher { _, pcm, _ -> Files.write(pcm, ByteArray(16_000)); FinishedProcess() },
        )
        assertEquals(16_000L, verifier.verify(temp.resolve("local.media"), "audio/webm", 1, "ffmpeg"))
        assertEquals(listOf(2, 1), attempts.values.toList())
        assertTrue(attempts.keys.all { !Files.exists(it) })
    }

    @Test
    fun `permanent PCM deletion failure is bounded and still removes diagnostics`() = runTest {
        val artifacts = mutableListOf<Path>()
        var failures = 0
        val verifier = PcmVerifier(
            deleteFile = { path ->
                if (path.toString().endsWith(".raw")) {
                    failures++
                    throw FileSystemException(path.toString())
                }
                Files.deleteIfExists(path)
            },
            launcher = DecoderLauncher { _, pcm, diagnostics ->
                artifacts += listOf(pcm, diagnostics)
                Files.write(pcm, ByteArray(16_000))
                FinishedProcess()
            },
        )
        try {
            verifier.verify(temp.resolve("local.media"), "audio/webm", 1, "ffmpeg")
            throw AssertionError("Expected bounded deletion failure")
        } catch (_: FileSystemException) {
            assertEquals(20, failures)
            assertFalse(Files.exists(artifacts.last()))
        } finally {
            artifacts.forEach { Files.deleteIfExists(it) }
        }
    }

    private class FinishedProcess(private val code: Int = 0, private var alive: Boolean = false) : Process() {
        var destroyed = false
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = code
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else code
        override fun isAlive(): Boolean = alive
        override fun destroy() { alive = false; destroyed = true }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
