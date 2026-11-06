"""Exercise MA's real metadata hydration and queue entry without playback."""

from __future__ import annotations

import asyncio
import copy
import hashlib
import json
import unittest
from contextlib import asynccontextmanager
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from music_assistant_models.config_entries import ProviderConfig
from music_assistant_models.enums import (
    MediaType,
    PlaybackState,
    PlayerType,
    ProviderFeature,
    ProviderType,
    QueueOption,
)
from music_assistant_models.errors import MediaNotFoundError
from music_assistant_models.player_queue import PlayerQueue
from music_assistant_models.provider import ProviderManifest

from music_assistant.controllers.music import MusicController
from music_assistant.controllers.player_queues import PlayerQueuesController
from music_assistant.controllers.player_queues.state import PlayerQueueData
from music_assistant.providers import nzik
from music_assistant.providers.nzik.client import NzikClient


TRACK = {
    "id": "abcdefghijk", "title": "Track", "artists": ["First Artist", "Second Artist"],
    "album": "Album", "durationSeconds": 120, "thumbnail": "https://example.test/art.jpg",
}
PLAYER_ID = "nzik-regression-player"


@asynccontextmanager
async def no_io_context(*_args, **_kwargs):
    """Keep cache refresh and player locking inside the isolated fixture."""
    yield


class HydrationTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.row = copy.deepcopy(TRACK)
        self.tasks = set()
        self.mass = SimpleNamespace(
            loop=asyncio.get_running_loop(),
            config=SimpleNamespace(get_raw_core_config_value=lambda _domain, _key, default=None: default),
            cache=SimpleNamespace(handle_refresh=no_io_context),
            register_api_command=Mock(),
            subscribe=Mock(),
            create_task=self.create_task,
            signal_event=Mock(),
            call_later=Mock(),
            cancel_timer=Mock(),
            streams=SimpleNamespace(
                audio_analysis=SimpleNamespace(get_track_audio_metadata=AsyncMock(return_value=None)),
                audio_processing=SimpleNamespace(prune=Mock()),
            ),
        )
        self.mass.music = MusicController(self.mass)
        for controller in (
            self.mass.music.tracks, self.mass.music.albums, self.mass.music.artists,
        ):
            controller.get_library_item_by_prov_id = AsyncMock(return_value=None)
        self.provider = self.make_provider()
        self.mass.get_provider = lambda instance: (
            self.provider if instance in (self.provider.instance_id, self.provider.domain) else None
        )

    async def asyncTearDown(self) -> None:
        pending = tuple(self.tasks)
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)

    def create_task(self, target, *args, **kwargs):
        for option in ("task_id", "abort_existing", "eager_start", "log_exceptions"):
            kwargs.pop(option, None)
        task = asyncio.create_task(target(*args, **kwargs))
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)
        return task

    def make_provider(self):
        manifest = ProviderManifest.from_dict(json.loads(
            Path(nzik.__file__).with_name("manifest.json").read_text()
        ))
        config = ProviderConfig(
            values={}, type=ProviderType.MUSIC, domain="nzik", instance_id="nzik-test",
        )
        provider = nzik.NzikProvider(self.mass, manifest, config, {ProviderFeature.SEARCH})
        provider.available = True
        provider.client = AsyncMock(spec=NzikClient)
        provider.client.track.side_effect = self.fetch_track
        provider.client.source.side_effect = AssertionError("Queue staging requested audio")
        return provider

    async def fetch_track(self, item_id):
        self.assertEqual(item_id, TRACK["id"])
        return copy.deepcopy(self.row)

    async def get_track(self):
        return await self.mass.music.tracks.get(
            TRACK["id"], self.provider.instance_id, recursive=True,
        )

    async def test_recursive_track_details_preserve_metadata_from_cold_cache(self) -> None:
        track = await self.get_track()
        self.assertEqual((track.item_id, track.name, track.duration), (TRACK["id"], "Track", 120))
        self.assertEqual([artist.name for artist in track.artists], TRACK["artists"])
        self.assertEqual(
            [artist.item_id for artist in track.artists],
            ["name-" + hashlib.sha256(name.encode()).hexdigest()[:24] for name in TRACK["artists"]],
        )
        self.assertEqual((track.album.item_id, track.album.name), ("track-album-" + TRACK["id"], "Album"))
        self.assertEqual(track.image.path, TRACK["thumbnail"])
        self.assertEqual(
            {(mapping.item_id, mapping.provider_domain, mapping.provider_instance, mapping.available)
             for mapping in track.provider_mappings},
            {(TRACK["id"], "nzik", "nzik-test", True)},
        )
        self.mass.music.tracks.get_library_item_by_prov_id.assert_awaited()
        self.mass.music.albums.get_library_item_by_prov_id.assert_awaited()
        self.provider.client.track.assert_awaited_once_with(TRACK["id"])
        self.assertEqual(self.provider.supported_media_types, {MediaType.TRACK})

    async def test_track_uri_enters_actual_queue_without_playback(self) -> None:
        player = SimpleNamespace(
            player_id=PLAYER_ID, type=PlayerType.PLAYER, extra_data={},
            state=SimpleNamespace(active_source="external-test-source"),
            play_media=AsyncMock(side_effect=AssertionError("Queue staging started playback")),
        )
        self.mass.players = SimpleNamespace(
            get_player=lambda player_id, *_args, **_kwargs: player if player_id == PLAYER_ID else None,
            get_group_and_player_lock=no_io_context,
            trigger_player_update=Mock(),
        )
        queues = self.mass.player_queues = PlayerQueuesController(self.mass)
        queues._queue_data[PLAYER_ID] = PlayerQueueData(queue=PlayerQueue(
            queue_id=PLAYER_ID, active=False, display_name="Regression queue", available=True,
            items=0,
        ))
        with patch.object(queues, "play_index", wraps=queues.play_index) as play_index:
            await queues.play_media(
                queue_id=PLAYER_ID,
                media=f"{self.provider.instance_id}://track/{TRACK['id']}",
                option=QueueOption.ADD,
            )
            play_index.assert_not_called()
        items = queues.items(PLAYER_ID)
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0].media_item.uri, f"nzik-test://track/{TRACK['id']}")
        self.assertEqual([artist.name for artist in items[0].media_item.artists], TRACK["artists"])
        self.assertEqual(items[0].media_item.album.name, "Album")
        self.assertEqual(items[0].image.path, TRACK["thumbnail"])
        self.assertEqual(queues.get(PLAYER_ID).state, PlaybackState.IDLE)
        self.provider.client.track.assert_awaited_once_with(TRACK["id"])
        self.provider.client.source.assert_not_awaited()
        player.play_media.assert_not_awaited()

    async def test_optional_metadata_survives_recursive_loading(self) -> None:
        cases = (
            ({key: value for key, value in TRACK.items()
              if key not in ("album", "thumbnail", "durationSeconds")}, TRACK["artists"], None, 0),
            ({**TRACK, "artists": [], "thumbnail": None}, [], "Album", 120),
            ({**TRACK, "artists": ["Artist", "Artist", " ", "Other"], "thumbnail": None},
             ["Artist", "Other"], "Album", 120),
        )
        for row, names, album_name, duration in cases:
            with self.subTest(row=row):
                self.row = row
                track = await self.get_track()
                self.assertEqual([artist.name for artist in track.artists], names)
                self.assertEqual(track.album.name if track.album else None, album_name)
                self.assertEqual(track.duration, duration)
                self.assertIsNone(track.image)

    async def test_standalone_related_lookups_are_typed_without_search_cache(self) -> None:
        await self.provider.get_track(TRACK["id"])
        for provider in (self.provider, self.make_provider()):
            for getter, item_id in (
                (provider.get_album, "track-album-" + TRACK["id"]),
                (provider.get_artist, "name-stale-artist"),
            ):
                with self.subTest(getter=getter.__name__), self.assertRaises(MediaNotFoundError):
                    await getter(item_id)

    async def test_invalid_required_metadata_is_a_typed_error(self) -> None:
        for row in (
            {key: value for key, value in TRACK.items() if key != "artists"},
            {**TRACK, "title": " "},
        ):
            with self.subTest(row=row), self.assertRaises(MediaNotFoundError):
                self.row = row
                await self.get_track()


if __name__ == "__main__":
    unittest.main()
