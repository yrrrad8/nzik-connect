package app.n_zik.android.connect

import java.net.SocketTimeoutException

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import it.fast4x.innertube.Innertube
import it.fast4x.innertube.requests.searchPage
import it.fast4x.innertube.requests.song
import it.fast4x.innertube.utils.InnertubeLogger
import it.fast4x.innertube.utils.from
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal interface ConnectMusicService {
    suspend fun search(query: String): List<ConnectTrack>
    suspend fun metadata(id: String): ConnectTrack
    suspend fun resolve(id: String, allowSabr: Boolean = true): ResolvedAudio
    suspend fun refresh()
}

internal class InnerTubeMusicService : ConnectMusicService {
    private val listener = InnertubeLogger.Listener { _, level, _, _ ->
        connectLogger.fine("InnerTube diagnostic: ${level.name}")
    }

    init {
        InnertubeLogger.addListener(listener)
    }

    private val transport = Innertube.extractionTransport()
    private val resolver = ResettableAudioResolver {
        InnerTubeExtractionSession(transport.httpClient, transport.innerTube)
    }

    override suspend fun search(query: String): List<ConnectTrack> {
        validateQuery(query)
        return musicRequest(FailureCode.SEARCH_FAILED) {
            val result = Innertube.searchPage<Innertube.SongItem>(
                query = query.trim(),
                params = Innertube.SearchFilter.Song.value,
                fromMusicShelfRendererContent = Innertube.SongItem.Companion::from,
            ) ?: run {
                currentCoroutineContext().ensureActive()
                throw ConnectFailure(FailureCode.SEARCH_FAILED)
            }
            val page = result.getOrThrow() ?: throw ConnectFailure(FailureCode.SEARCH_FAILED)
            page.items.orEmpty().filter { it.key.isNotBlank() }.map { it.toConnectTrack() }
        }
    }

    override suspend fun metadata(id: String): ConnectTrack {
        validateTrackId(id)
        return musicRequest(FailureCode.METADATA_FAILED) {
            val result = Innertube.song(id) ?: run {
                currentCoroutineContext().ensureActive()
                throw ConnectFailure(FailureCode.METADATA_FAILED)
            }
            val item = result.getOrThrow() ?: throw ConnectFailure(FailureCode.METADATA_FAILED)
            item.toConnectTrack().also {
                if (it.id != id) throw ConnectFailure(FailureCode.METADATA_FAILED)
            }
        }
    }

    override suspend fun resolve(id: String, allowSabr: Boolean): ResolvedAudio {
        validateTrackId(id)
        return musicRequest(FailureCode.EXTRACTION_FAILED) {
            resolver.resolve(id, allowSabr)
        }
    }

    override suspend fun refresh() {
        musicRequest(FailureCode.EXTRACTION_FAILED) { resolver.reset() }
    }

    suspend fun close() {
        try {
            resolver.close()
        } finally {
            transport.innerTube.close()
            transport.httpClient.close()
            InnertubeLogger.removeListener(listener)
        }
    }
}

internal fun Innertube.SongItem.toConnectTrack(): ConnectTrack {
    validateTrackId(key)
    return ConnectTrack(
        id = key,
        title = title.orEmpty(),
        artists = authors.orEmpty().mapNotNull { it.name },
        album = album?.name,
        durationSeconds = parseDuration(durationText),
        thumbnail = thumbnail?.url,
    )
}

internal fun parseDuration(text: String?): Long? {
    val parts = text?.split(':')?.map { it.toLongOrNull() } ?: return null
    if (parts.size !in 2..3 || parts.any { it == null || it < 0 }) return null
    if (parts.drop(1).any { requireNotNull(it) >= 60 }) return null
    var seconds = 0L
    for (part in parts) {
        val value = requireNotNull(part)
        if (seconds > (Long.MAX_VALUE - value) / 60) return null
        seconds = seconds * 60 + value
    }
    return seconds
}

internal suspend fun <T> musicRequest(code: FailureCode, block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: ConnectFailure) {
    throw error
} catch (_: HttpRequestTimeoutException) {
    throw ConnectFailure(FailureCode.TIMEOUT)
} catch (_: ConnectTimeoutException) {
    throw ConnectFailure(FailureCode.TIMEOUT)
} catch (_: SocketTimeoutException) {
    throw ConnectFailure(FailureCode.TIMEOUT)
} catch (_: Exception) {
    connectLogger.fine("Music request failed: ${code.name}")
    throw ConnectFailure(code)
}
