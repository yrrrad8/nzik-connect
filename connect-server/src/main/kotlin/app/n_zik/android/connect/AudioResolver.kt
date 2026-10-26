package app.n_zik.android.connect

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.findSabrProtocolException
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface AudioExtractionSession {
    suspend fun resolve(id: String, allowSabr: Boolean): ResolvedAudio
    suspend fun close()
}

internal class ResettableAudioResolver(private val createSession: () -> AudioExtractionSession) {
    private val mutex = Mutex()
    private var session: AudioExtractionSession? = null
    private var closed = false

    suspend fun resolve(id: String, allowSabr: Boolean): ResolvedAudio = mutex.withLock {
        check(!closed) { "Audio resolver is closed" }
        val active = session ?: createSession().also { session = it }
        active.resolve(id, allowSabr)
    }

    suspend fun reset() = mutex.withLock {
        check(!closed) { "Audio resolver is closed" }
        val previous = session
        session = null
        // The next extraction must reload both player configuration and cipher programs.
        withContext(NonCancellable) { previous?.close() }
    }

    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            closed = true
            val previous = session
            session = null
            previous?.close()
        }
    }
}

internal class InnerTubeExtractionSession(private val http: HttpClient, innerTube: InnerTube) : AudioExtractionSession {
    private val cipher = YouTubeCipherService(http)
    // Initialization follows the GPL-3.0 v0.11.2 harness without Android/browser adapters.
    // https://github.com/MetrolistGroup/innertubex/blob/v0.11.2/harness/src/main/kotlin/com/metrolist/innertubex/harness/Main.kt
    private val extractor = InnerTubeExtractor(
        configParser = YtConfigParserImpl(http, innerTube, cipherService = cipher),
        cipherService = cipher,
        innerTube = innerTube,
    )

    @OptIn(ExperimentalSabrApi::class)
    override suspend fun resolve(id: String, allowSabr: Boolean): ResolvedAudio {
        val stream = extractor.extract(
            videoId = id,
            hints = extractionHints(allowSabr),
            audioQuality = AudioQuality.HIGH,
        ) ?: throw ConnectFailure(FailureCode.EXTRACTION_FAILED)
        val bootstrap = stream.sabrBootstrap
        val mime = stream.mimeType.orEmpty().substringBefore(';').trim().lowercase()
        if (mime !in setOf("audio/webm", "audio/mp4")) throw ConnectFailure(FailureCode.UNSUPPORTED_TRANSPORT)
        return ResolvedAudio(
            id = id,
            transport = when {
                bootstrap != null -> Transport.SABR
                stream.requireBoundedRange || stream.useRangeChunks -> Transport.RANGE
                else -> Transport.DIRECT
            },
            mimeType = mime,
            url = stream.audioUrl.takeUnless { bootstrap != null },
            headers = stream.headers,
            contentLength = stream.contentLengthBytes,
            expiresAtEpochMs = stream.expiresAt?.toEpochMilliseconds(),
            rangeChunkSize = stream.rangeChunkSizeBytes,
            bitrate = stream.bitrate,
            chunks = bootstrap?.let {
                SabrAudioStream(httpClient = http, bootstrap = it).chunks().map { chunk -> chunk.data }.mapSabrFailures()
            },
        )
    }

    override suspend fun close() = cipher.dispose()
}

internal fun extractionHints(allowSabr: Boolean): ContentHints =
    ContentHints(wantVideo = false).withStreamCapabilities(
        allowHls = false,
        allowSabr = allowSabr,
        allowBoundedRange = true,
    )

internal fun Flow<ByteArray>.mapSabrFailures(): Flow<ByteArray> = catch { error ->
    if (error is CancellationException) throw error
    currentCoroutineContext().ensureActive()
    if (error.findSabrProtocolException()?.httpStatusCode in setOf(401, 403, 410)) {
        throw ConnectFailure(FailureCode.SOURCE_REJECTED)
    }
    throw error
}
