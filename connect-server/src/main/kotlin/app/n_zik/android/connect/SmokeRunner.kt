package app.n_zik.android.connect

import java.nio.file.Files
import java.nio.file.Path

import kotlinx.coroutines.withTimeout

internal class SmokeRunner(
    private val music: ConnectMusicService,
    private val capture: AudioCapture,
    private val verifier: AudioVerifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val temporaryDirectory: Path? = null,
) {
    suspend fun run(options: SmokeOptions): SmokeReport = withTimeout(options.deadlineMs) {
        connectLogger.info("stage=search")
        val selected = music.search(options.query).firstOrNull() ?: throw ConnectFailure(FailureCode.NO_MATCHES)
        connectLogger.info("stage=metadata")
        val track = music.metadata(selected.id)
        if (track.id != selected.id) throw ConnectFailure(FailureCode.METADATA_FAILED)
        val seconds = minOf(options.seconds.toLong(), track.durationSeconds?.takeIf { it > 0 } ?: 30L).toInt()
        for (attempt in 0..1) {
            connectLogger.info("stage=resolve attempt=$attempt")
            val source = music.resolve(track.id)
            if (source.id != track.id) throw ConnectFailure(FailureCode.EXTRACTION_FAILED)
            if (source.expiresAtEpochMs?.let { it <= clock() + 5_000 } == true) {
                if (attempt == 1) throw ConnectFailure(FailureCode.SOURCE_REJECTED)
                music.refresh()
                continue
            }
            val file = temporaryDirectory?.let { Files.createTempFile(it, "nzik-media-", ".media") }
                ?: Files.createTempFile("nzik-media-", ".media")
            try {
                connectLogger.info("stage=capture transport=${source.transport.name}")
                val mediaBytes = capture.capture(source, file, options.maxBytes, seconds)
                if (mediaBytes <= 0) throw ConnectFailure(FailureCode.EMPTY_MEDIA)
                connectLogger.info("stage=decode")
                val pcmBytes = verifyPcmBytes(verifier.verify(file, source.mimeType, seconds, options.ffmpeg), seconds)
                return@withTimeout SmokeReport(
                    status = "pass",
                    track = track,
                    transport = source.transport,
                    mediaBytes = mediaBytes,
                    pcmBytes = pcmBytes,
                    decodedDurationMs = pcmBytes * 1_000 / 16_000,
                    refreshedSource = attempt > 0,
                )
            } catch (error: ConnectFailure) {
                if (attempt == 1 || error.code != FailureCode.SOURCE_REJECTED) throw error
                music.refresh()
            } finally {
                Files.deleteIfExists(file)
            }
        }
        throw ConnectFailure(FailureCode.SOURCE_REJECTED)
    }
}
