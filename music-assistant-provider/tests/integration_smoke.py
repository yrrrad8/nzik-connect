"""Prove real NZIK playback through MA's StreamsAudio without storing audio."""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
from contextlib import aclosing
from pathlib import Path
from types import SimpleNamespace

from aiohttp import ClientSession
from music_assistant_models.config_entries import ProviderConfig
from music_assistant_models.enums import ContentType, MediaType, ProviderType
from music_assistant_models.media_items import AudioFormat
from music_assistant_models.provider import ProviderManifest

from music_assistant.controllers.streams.audio import StreamsAudio
from music_assistant.providers.nzik import setup


async def run(options: argparse.Namespace) -> dict:
    """Count discarded PCM from the actual MA streaming controller."""
    token = os.environ.get("NZIK_CONNECT_TOKEN", "")
    values = {"server_url": options.server, "token": token}
    manifest = ProviderManifest.from_dict(json.loads(
        (Path(__file__).parents[1] / "nzik" / "manifest.json").read_text()
    ))
    config = ProviderConfig(values={}, type=ProviderType.MUSIC, domain="nzik", instance_id="nzik-smoke")
    async with ClientSession() as session:
        mass = SimpleNamespace(
            http_session=session, cache=None, loop=asyncio.get_running_loop(),
            config=SimpleNamespace(get=lambda key, default=None: values, decrypt_string=lambda value: value),
        )
        provider = await setup(mass, manifest, config)
        provider.available = True
        mass.get_provider = lambda *args, **kwargs: provider
        audio = StreamsAudio(mass)
        pcm = AudioFormat(content_type=ContentType.PCM_S16LE, sample_rate=16000, bit_depth=16, channels=1)
        results = await provider.search(options.query, [MediaType.TRACK], 5)
        tracks = [track for track in results.tracks if 45 <= track.duration <= 600]
        if len(tracks) < 2:
            raise RuntimeError("insufficient_test_tracks")
        first = await provider.get_track(tracks[0].item_id)

        async def decode(item_id: str, offset: int = 0, sample_seconds: int = 0) -> float:
            details = await provider.get_stream_details(item_id, MediaType.TRACK)
            count = 0
            async with aclosing(audio.get_media_stream(details, pcm, seek_position=offset)) as stream:
                async for chunk in stream:
                    count += len(chunk)
                    if sample_seconds and count >= sample_seconds * pcm.pcm_sample_size:
                        break
            return count / pcm.pcm_sample_size

        full = await decode(first.item_id)
        if abs(full - first.duration) > max(3, first.duration * 0.03):
            raise RuntimeError("incomplete_track")
        offset = min(30, first.duration // 3)
        remainder = await decode(first.item_id, offset)
        if abs(remainder - (full - offset)) > 3:
            raise RuntimeError("incorrect_seek")
        second = await decode(tracks[1].item_id, sample_seconds=5)
        if second < 5:
            raise RuntimeError("second_track_failed")
        return {
            "status": "pass", "pipeline": "Music Assistant 2.10.8 StreamsAudio",
            "trackId": first.item_id, "metadataSeconds": first.duration,
            "decodedSeconds": round(full, 3), "seekSeconds": offset,
            "remainderSeconds": round(remainder, 3),
            "secondTrackId": tracks[1].item_id, "secondTrackSampleSeconds": round(second, 3),
            "physicalPlayerVerified": False,
        }


async def main() -> None:
    """Print safe evidence only, including on upstream decoder failure."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--query", default="Daft Punk Get Lucky")
    parser.add_argument("--server", default="http://127.0.0.1:8765")
    options = parser.parse_args()
    # Upstream decoder exceptions can contain signed URLs in their FFmpeg log tails.
    logging.disable(logging.CRITICAL)
    try:
        async with asyncio.timeout(1200):
            report = await run(options)
    except Exception as error:
        print(json.dumps({"status": "fail", "exceptionType": type(error).__name__}))
        raise SystemExit(1) from None
    print(json.dumps(report))


if __name__ == "__main__":
    asyncio.run(main())
