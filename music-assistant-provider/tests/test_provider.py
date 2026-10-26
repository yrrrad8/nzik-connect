"""Run with the actual Music Assistant 2.10.8 models and aiohttp."""

from __future__ import annotations

import asyncio
import copy
import json
import logging
import sys
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

from aiohttp import ClientSession, web
from music_assistant_models.config_entries import ProviderConfig
from music_assistant_models.enums import MediaType, ProviderFeature, ProviderType, StreamType
from music_assistant_models.errors import LoginFailed, ResourceTemporarilyUnavailable
from music_assistant_models.provider import ProviderManifest

from music_assistant.models.setup_flow import SetupFlowError
from music_assistant.providers.nzik import parse_source, setup
from music_assistant.providers.nzik.client import BackendError, NzikClient, validate_connection
from music_assistant.providers.nzik.logging_filter import MediaCredentialFilter
from music_assistant.providers.nzik.setup_flow import run_setup


TOKEN = "test-only-connection-token-0123456789"
TRACK = {
    "id": "abcdefghijk", "title": "Track", "artists": ["Artist"],
    "album": "Album", "durationSeconds": 120, "thumbnail": "https://example.test/art.jpg",
}


def audio_source() -> dict:
    """Create a safe, synthetic source descriptor."""
    return {
        "trackId": TRACK["id"], "transport": "DIRECT", "mimeType": "audio/webm",
        "url": "https://test.googlevideo.com/videoplayback?signed=test-only",
        "headers": {"User-Agent": "NZIK test", "Range": "bytes=0-", "Accept-Encoding": "gzip"},
        "expiresAtEpochMs": int(time.time() * 1000) + 90_000,
        "contentLength": 123456, "requireBoundedRange": False, "rangeChunkSizeBytes": None,
    }


class ProviderTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.requests = []
        self.status = 200
        self.delay = False
        self.entered = asyncio.Event()
        app = web.Application()
        app.router.add_get("/{path:.*}", self.respond)
        self.runner = web.AppRunner(app, shutdown_timeout=0.1)
        await self.runner.setup()
        server = web.TCPSite(self.runner, "127.0.0.1", 0)
        await server.start()
        port = self.runner.addresses[0][1]
        self.url = f"http://127.0.0.1:{port}"
        self.session = ClientSession()
        self.client = NzikClient(self.session, self.url, TOKEN)
        self.config = ProviderConfig(
            values={}, type=ProviderType.MUSIC, domain="nzik", instance_id="nzik-test",
        )
        self.values = {"server_url": self.url, "token": TOKEN}
        self.mass = SimpleNamespace(
            http_session=self.session, cache=None,
            config=SimpleNamespace(get=lambda key, default=None: self.values, decrypt_string=lambda value: value),
        )
        manifest_path = Path(__file__).parents[1] / "nzik" / "manifest.json"
        self.manifest = ProviderManifest.from_dict(json.loads(manifest_path.read_text()))

    async def asyncTearDown(self) -> None:
        await self.session.close()
        await self.runner.cleanup()

    async def respond(self, request: web.Request) -> web.Response:
        self.requests.append(request.path)
        if request.headers.get("Authorization") != f"Bearer {TOKEN}":
            return web.json_response({"reason": "unauthorized"}, status=401)
        if self.status != 200:
            return web.Response(status=self.status, text="sensitive-upstream-detail")
        if self.delay:
            self.entered.set()
            await asyncio.sleep(5)
        if request.path.endswith("capabilities"):
            return web.json_response({"protocolVersion": 1, "searchMediaTypes": ["track"], "sourceTransports": ["DIRECT", "RANGE"]})
        if request.path.endswith("source"):
            return web.json_response(audio_source())
        if request.path.endswith("search"):
            return web.json_response([TRACK])
        return web.json_response(TRACK)

    async def test_setup_mapping_and_private_stream_serialization(self) -> None:
        provider = await setup(self.mass, self.manifest, self.config)
        self.assertEqual(provider.supported_features, {ProviderFeature.SEARCH})
        self.assertEqual(provider.supported_media_types, {MediaType.TRACK})
        results = await provider.search("song", [MediaType.TRACK], 1)
        track = results.tracks[0]
        self.assertEqual(track.item_id, TRACK["id"])
        self.assertEqual(track.artists[0].name, "Artist")
        self.assertEqual(track.album.name, "Album")
        self.assertEqual(track.provider_mappings.pop().provider_instance, "nzik-test")
        details = await provider.get_stream_details(TRACK["id"], MediaType.TRACK)
        self.assertEqual(details.stream_type, StreamType.HTTP)
        self.assertTrue(details.can_seek)
        self.assertEqual(details.size, 123456)
        self.assertTrue(80 <= details.expiration <= 85)
        self.assertIn("User-Agent: NZIK test\r\n", details.extra_input_args[1])
        self.assertNotIn("Range", details.extra_input_args[1])
        serialized = json.dumps(details.to_dict())
        self.assertNotIn("signed", serialized)
        self.assertNotIn("NZIK test", serialized)
        self.assertNotIn(TOKEN, serialized)

    async def test_catalog_cache_and_uncached_audio(self) -> None:
        await self.client.search("song", 1)
        await self.client.search("song", 1)
        await self.client.track(TRACK["id"])
        await self.client.track(TRACK["id"])
        await self.client.source(TRACK["id"])
        await self.client.source(TRACK["id"])
        self.assertEqual(self.requests.count("/v1/search"), 1)
        self.assertEqual(self.requests.count("/v1/tracks/abcdefghijk"), 1)
        self.assertEqual(self.requests.count("/v1/tracks/abcdefghijk/source"), 2)

    async def test_auth_outage_and_unsupported_search_are_safe(self) -> None:
        provider = await setup(self.mass, self.manifest, self.config)
        self.assertEqual((await provider.search("song", [MediaType.ALBUM])).tracks, [])
        self.status = 503
        with self.assertRaises(ResourceTemporarilyUnavailable) as raised:
            await provider.search("song", [MediaType.TRACK])
        self.assertNotIn("sensitive", str(raised.exception))
        self.values["token"] = "wrong-token-012345678901234567890123456789"
        with self.assertRaises(LoginFailed):
            await setup(self.mass, self.manifest, self.config)

    async def test_cancel_releases_request_lock_and_session(self) -> None:
        self.delay = True
        task = asyncio.create_task(self.client.source(TRACK["id"]))
        await asyncio.wait_for(self.entered.wait(), 2)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertFalse(self.client._lock.locked())
        self.delay = False
        self.assertEqual((await self.client.capabilities())["protocolVersion"], 1)

    async def test_reconfigure_preserves_secret_and_retries_safe_error(self) -> None:
        session = SimpleNamespace(
            context=SimpleNamespace(setup_data={"server_url": self.url, "token": TOKEN}),
            form=AsyncMock(return_value={"server_url": self.url, "token": ""}),
            finish=AsyncMock(side_effect=[SetupFlowError("test failure"), None]),
        )
        await run_setup(session)
        self.assertEqual(session.finish.await_count, 2)
        self.assertEqual(session.finish.await_args.args[0]["token"], TOKEN)
        self.assertIsNotNone(session.form.await_args.kwargs["errors"])
        for entry in session.form.await_args.args[0]:
            if entry.key == "token":
                self.assertIsNone(entry.value)


class SourceValidationTest(unittest.TestCase):
    def test_decoder_logs_redact_signed_urls_headers_and_exception_tails(self) -> None:
        address = audio_source()["url"]
        try:
            raise RuntimeError("decoder failed: " + address)
        except RuntimeError:
            record = logging.LogRecord(
                "music_assistant.ffmpeg", logging.ERROR, __file__, 0,
                "Failed %s\nAuthorization: Bearer private-token\nCookie: private-cookie",
                (address,), sys.exc_info(),
            )
        MediaCredentialFilter().filter(record)
        rendered = logging.Formatter().format(record)
        for secret in ("signed=test-only", "private-token", "private-cookie"):
            self.assertNotIn(secret, rendered)
        self.assertIn("decoder failed", rendered)
        self.assertIn("<NZIK media URL>", rendered)

    def test_transport_expiry_host_and_header_injection(self) -> None:
        for changes in (
            {"transport": "RANGE", "requireBoundedRange": True},
            {"transport": "SABR"},
            {"expiresAtEpochMs": 0},
            {"url": "https://foreign.test/audio"},
            {"url": "http://test.googlevideo.com/audio"},
            {"headers": {"User-Agent": "value\r\nInjected: yes"}},
            {"headers": {"Bad:Header": "value"}},
        ):
            with self.subTest(changes=changes):
                value = copy.deepcopy(audio_source())
                value.update(changes)
                with self.assertRaises(BackendError):
                    parse_source(value)

    def test_only_loopback_backend_connections(self) -> None:
        for url in ("http://host.test:8765", "http://127.0.0.1:8765/secret", "http://user@127.0.0.1:8765", "file:///tmp/audio"):
            with self.subTest(url=url), self.assertRaises(BackendError):
                validate_connection(url, TOKEN)
        self.assertEqual(validate_connection("http://127.0.0.1:8765/", TOKEN), "http://127.0.0.1:8765")


if __name__ == "__main__":
    unittest.main()
