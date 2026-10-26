package app.n_zik.android.connect

import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.Path

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal fun interface DecoderLauncher {
    fun start(command: List<String>, pcm: Path, diagnostics: Path): Process
}

internal class PcmVerifier(
    private val deleteFile: (Path) -> Unit = { Files.deleteIfExists(it); Unit },
    private val launcher: DecoderLauncher = DecoderLauncher { command, pcm, diagnostics ->
        ProcessBuilder(command).redirectOutput(pcm.toFile()).redirectError(diagnostics.toFile()).start()
    },
) : AudioVerifier {
    override suspend fun verify(target: Path, mimeType: String, seconds: Int, ffmpeg: String): Long {
        requireConnect(seconds in 1..30)
        val format = when (mimeType) {
            "audio/webm" -> "matroska"
            "audio/mp4" -> "mov"
            else -> throw ConnectFailure(FailureCode.UNSUPPORTED_TRANSPORT)
        }
        val pcm = Files.createTempFile("nzik-pcm-", ".raw")
        var diagnostics: Path? = null
        var process: Process? = null
        try {
            val diagnosticFile = Files.createTempFile("nzik-decoder-", ".log")
            diagnostics = diagnosticFile
            val command = listOf(
                ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "fatal",
                "-protocol_whitelist", "file,pipe", "-format_whitelist", "matroska,mov",
                "-f", format, "-i", target.toAbsolutePath().toString(),
                "-map", "0:a:0", "-t", seconds.toString(), "-vn", "-ac", "1", "-ar", "8000",
                "-c:a", "pcm_s16le", "-f", "s16le", "pipe:1",
            )
            val running = try {
                launcher.start(command, pcm, diagnosticFile)
            } catch (_: IOException) {
                throw ConnectFailure(FailureCode.DECODER_UNAVAILABLE)
            }
            process = running
            try {
                withTimeout(30_000) {
                    while (running.isAlive) delay(25)
                }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw ConnectFailure(FailureCode.TIMEOUT)
            }
            if (running.exitValue() != 0) throw ConnectFailure(FailureCode.DECODE_FAILED)
            return verifyPcmBytes(Files.size(pcm), seconds)
        } finally {
            withContext(NonCancellable) {
                try {
                    val running = process
                    if (running?.isAlive == true) {
                        running.destroyForcibly()
                        withTimeoutOrNull(2_000) { while (running.isAlive) delay(25) }
                    }
                } finally {
                    try {
                        deleteWithRetry(pcm)
                    } finally {
                        diagnostics?.let { deleteWithRetry(it) }
                    }
                }
            }
        }
    }

    private suspend fun deleteWithRetry(path: Path) {
        repeat(20) { attempt ->
            try {
                deleteFile(path)
                return
            } catch (error: FileSystemException) {
                if (attempt == 19) throw error
                // Windows can briefly retain a process or scanner handle after the decoder exits.
                delay(50)
            }
        }
    }
}

internal fun verifyPcmBytes(bytes: Long, seconds: Int): Long {
    val expected = seconds * 16_000L
    if (bytes < expected * 9 / 10 || bytes > expected + 16_000 || bytes % 2 != 0L) {
        throw ConnectFailure(FailureCode.PCM_TOO_SHORT)
    }
    return bytes
}
