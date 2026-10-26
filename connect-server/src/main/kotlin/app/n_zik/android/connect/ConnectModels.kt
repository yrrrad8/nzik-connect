package app.n_zik.android.connect

import java.nio.file.Path

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
internal data class ConnectTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationSeconds: Long? = null,
    val thumbnail: String? = null,
)

@Serializable
internal enum class Transport { DIRECT, RANGE, SABR }

internal class ResolvedAudio(
    val id: String,
    val transport: Transport,
    val mimeType: String,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val contentLength: Long? = null,
    val expiresAtEpochMs: Long? = null,
    val rangeChunkSize: Long = 512L * 1024,
    val bitrate: Int? = null,
    val chunks: Flow<ByteArray>? = null,
)

internal enum class FailureCode {
    INVALID_ARGUMENT, SEARCH_FAILED, METADATA_FAILED, NO_MATCHES, EXTRACTION_FAILED,
    UNSUPPORTED_TRANSPORT, SOURCE_REJECTED, INVALID_MEDIA_RESPONSE, BYTE_BUDGET_EXCEEDED,
    EMPTY_MEDIA, DECODER_UNAVAILABLE, DECODE_FAILED, PCM_TOO_SHORT, TIMEOUT, UNEXPECTED_FAILURE,
}

internal class ConnectFailure(val code: FailureCode) : RuntimeException(code.name)

internal data class SmokeOptions(
    val query: String,
    val seconds: Int = 10,
    val maxBytes: Long = 8L * 1024 * 1024,
    val deadlineMs: Long = 180_000,
    val ffmpeg: String = "ffmpeg",
) {
    init {
        validateQuery(query)
        requireConnect(seconds in 1..30 && maxBytes in 2L * 1024 * 1024..32L * 1024 * 1024)
        requireConnect(deadlineMs in 30_000..300_000)
        requireConnect(ffmpeg.isNotBlank() && !ffmpeg.contains("://"))
    }
}

@Serializable
internal data class SmokeReport(
    val status: String,
    val track: ConnectTrack,
    val transport: Transport,
    val mediaBytes: Long,
    val pcmBytes: Long,
    val decodedDurationMs: Long,
    val refreshedSource: Boolean,
)

internal fun requireConnect(value: Boolean) {
    if (!value) throw ConnectFailure(FailureCode.INVALID_ARGUMENT)
}

internal fun validateQuery(query: String) {
    requireConnect(query.isNotBlank() && query.length <= 300)
}

internal fun validateTrackId(id: String) {
    requireConnect(Regex("[A-Za-z0-9_-]{11}").matches(id))
}

internal fun interface AudioCapture {
    suspend fun capture(source: ResolvedAudio, target: Path, maxBytes: Long, seconds: Int): Long
}

internal fun interface AudioVerifier {
    suspend fun verify(target: Path, mimeType: String, seconds: Int, ffmpeg: String): Long
}
