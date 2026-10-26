package app.n_zik.android.connect

import it.fast4x.innertube.Innertube
import it.fast4x.innertube.models.NavigationEndpoint
import it.fast4x.innertube.models.Thumbnail
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MusicMappingTest {
    @Test
    fun `legacy music metadata retains stable ID and survives serialization`() {
        val song = Innertube.SongItem(
            info = Innertube.Info("Track", NavigationEndpoint.Endpoint.Watch(videoId = "abcdefghijk")),
            authors = listOf(Innertube.Info("Artist", NavigationEndpoint.Endpoint.Browse(browseId = "artist"))),
            album = Innertube.Info("Album", NavigationEndpoint.Endpoint.Browse(browseId = "album")),
            durationText = "1:02:03",
            thumbnail = Thumbnail("https://example.invalid/art", 100, 100),
        )
        val track = song.toConnectTrack()
        assertEquals("abcdefghijk", track.id)
        assertEquals(listOf("Artist"), track.artists)
        assertEquals(3723L, track.durationSeconds)
        assertEquals(track, Json.decodeFromString<ConnectTrack>(Json.encodeToString(track)))
    }

    @Test
    fun `malformed and overflowing durations remain unknown`() {
        listOf(null, "LIVE", "1:99", "-1:30", "9223372036854775807:00", "1:2:3:4").forEach {
            assertNull(parseDuration(it))
        }
        assertEquals(185L, parseDuration("3:05"))
    }

    @Test
    fun `invalid CLI values fail before creating music clients`() {
        assertEquals(FailureCode.INVALID_ARGUMENT, assertThrows(ConnectFailure::class.java) { validateQuery(" ") }.code)
        assertThrows(ConnectFailure::class.java) { validateTrackId("https://example.invalid") }
        assertThrows(ConnectFailure::class.java) { parseOptions(listOf("--query", "x", "--query", "y")) }
        assertThrows(ConnectFailure::class.java) { parseOptions(listOf("--query")) }
        assertThrows(ConnectFailure::class.java) { SmokeOptions("query", seconds = 0) }
    }
}
