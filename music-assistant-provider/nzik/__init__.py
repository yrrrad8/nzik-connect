"""NZIK adapter for Music Assistant 2.10.8; YouTube logic runs in Kotlin."""

from __future__ import annotations

import hashlib
import math
import re
import time
from typing import TYPE_CHECKING, Any
from urllib.parse import urlsplit

from music_assistant_models.enums import ContentType, ImageType, MediaType, ProviderFeature, StreamType
from music_assistant_models.errors import LoginFailed, MediaNotFoundError, ResourceTemporarilyUnavailable
from music_assistant_models.media_items import (
    AudioFormat,
    ItemMapping,
    MediaItemImage,
    ProviderMapping,
    SearchResults,
    Track,
    UniqueList,
)
from music_assistant_models.streamdetails import StreamDetails

from music_assistant.models.music_provider import MusicProvider

from .client import BackendError, NzikClient, validate_track_id
from .logging_filter import install_media_log_filter

if TYPE_CHECKING:
    from music_assistant_models.config_entries import ProviderConfig
    from music_assistant_models.provider import ProviderManifest

    from music_assistant.mass import MusicAssistant


async def setup(
    mass: MusicAssistant, manifest: ProviderManifest, config: ProviderConfig
) -> NzikProvider:
    """Validate the backend before making this instance available."""
    install_media_log_filter()
    provider = NzikProvider(mass, manifest, config, {ProviderFeature.SEARCH})
    try:
        provider.client = NzikClient(
            mass.http_session,
            str(provider.get_setup_value("server_url") or ""),
            str(provider.get_setup_value("token") or ""),
        )
        await provider.client.capabilities()
    except BackendError as error:
        raise LoginFailed(f"NZIK connection failed: {error.reason}") from None
    return provider


class NzikProvider(MusicProvider):
    """Expose the standalone Kotlin catalog as normal MA tracks."""

    client: NzikClient

    @property
    def supported_media_types(self) -> set[MediaType]:
        """Declare only the implemented catalog type."""
        return {MediaType.TRACK}

    @property
    def is_streaming_provider(self) -> bool:
        """Keep Music Assistant from treating this source as local files."""
        return True

    async def search(
        self, search_query: str, media_types: list[MediaType], limit: int = 5
    ) -> SearchResults:
        """Search songs through Kotlin, respecting the caller's media filter."""
        if MediaType.TRACK not in media_types or limit <= 0 or not search_query.strip():
            return SearchResults()
        try:
            rows = await self.client.search(search_query, min(limit, 50))
            return SearchResults(tracks=[self._track(row) for row in rows])
        except BackendError as error:
            raise ResourceTemporarilyUnavailable(f"NZIK search failed: {error.reason}") from None

    async def get_track(self, prov_track_id: str) -> Track:
        """Resolve a stable track identifier into MA metadata."""
        try:
            return self._track(await self.client.track(prov_track_id))
        except BackendError as error:
            raise MediaNotFoundError(f"NZIK metadata unavailable: {error.reason}") from None

    async def get_stream_details(self, item_id: str, media_type: MediaType) -> StreamDetails:
        """Resolve an internal audio address just before Music Assistant plays it."""
        if media_type != MediaType.TRACK:
            raise MediaNotFoundError("NZIK supports tracks only")
        try:
            track = await self.get_track(item_id)
            source = await self.client.source(item_id)
            path, headers, mime, expiration = parse_source(source)
        except BackendError as error:
            raise ResourceTemporarilyUnavailable(f"NZIK audio unavailable: {error.reason}") from None
        return StreamDetails(
            provider=self.instance_id,
            item_id=item_id,
            media_type=MediaType.TRACK,
            stream_type=StreamType.HTTP,
            path=path,
            audio_format=AudioFormat(content_type=ContentType.try_parse(mime)),
            duration=track.duration or None,
            size=source.get("contentLength"),
            expiration=expiration,
            allow_seek=bool(track.duration),
            can_seek=bool(track.duration),
            extra_input_args=["-headers", headers] if headers else [],
        )

    def _track(self, row: dict[str, Any]) -> Track:
        try:
            validate_track_id(row["id"])
            if not isinstance(row["title"], str) or not row["title"].strip():
                raise ValueError
            artists = row["artists"]
            if not isinstance(artists, list) or any(not isinstance(name, str) for name in artists):
                raise ValueError
            duration = row.get("durationSeconds")
            if duration is not None and (type(duration) is not int or duration < 0):
                raise ValueError
            track = Track(
                item_id=row["id"],
                provider=self.instance_id,
                name=row["title"],
                duration=duration or 0,
                provider_mappings={
                    ProviderMapping(
                        item_id=row["id"], provider_domain=self.domain,
                        provider_instance=self.instance_id,
                    )
                },
                artists=UniqueList(
                    ItemMapping(
                        media_type=MediaType.ARTIST,
                        item_id="name-" + hashlib.sha256(name.encode()).hexdigest()[:24],
                        provider=self.instance_id,
                        name=name,
                    ) for name in artists if name.strip()
                ),
            )
            if row.get("album"):
                track.album = ItemMapping(
                    media_type=MediaType.ALBUM,
                    item_id="track-album-" + row["id"],
                    provider=self.instance_id,
                    name=str(row["album"]),
                )
            image = row.get("thumbnail")
            if isinstance(image, str) and urlsplit(image).scheme == "https":
                track.metadata.images = UniqueList([
                    MediaItemImage(type=ImageType.THUMB, path=image, provider="builtin", remotely_accessible=True)
                ])
            return track
        except (KeyError, TypeError, ValueError):
            raise BackendError("invalid_backend_response") from None


def parse_source(source: dict[str, Any]) -> tuple[str, str, str, int]:
    """Validate the transport contract before giving it to FFmpeg."""
    if source.get("transport") != "DIRECT" or source.get("requireBoundedRange") is not False:
        raise BackendError("unsupported_transport")
    path = source.get("url")
    try:
        parsed = urlsplit(path) if isinstance(path, str) else None
        if (
            parsed is None or parsed.scheme != "https" or not parsed.hostname
            or not parsed.hostname.endswith(".googlevideo.com")
            or parsed.username is not None or parsed.password is not None
            or parsed.port not in (None, 443) or parsed.fragment
            or any(ord(char) <= 32 or ord(char) == 127 for char in path)
        ):
            raise ValueError
        mime = source.get("mimeType")
        if mime not in ("audio/mp4", "audio/webm"):
            raise ValueError
        headers = source.get("headers")
        if not isinstance(headers, dict) or len(headers) > 64:
            raise ValueError
        lines = []
        for key, value in headers.items():
            if (
                not isinstance(key, str) or not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", key)
                or not isinstance(value, str) or any(ord(char) < 32 or ord(char) == 127 for char in value)
                or len(key) + len(value) > 8192
            ):
                raise ValueError
            if key.lower() not in ("host", "range", "accept-encoding", "connection", "content-length"):
                lines.append(f"{key}: {value}\r\n")
        if sum(len(line) for line in lines) > 32_768:
            raise ValueError
        length = source.get("contentLength")
        if length is not None and (type(length) is not int or length <= 0):
            raise ValueError
        expiry = source.get("expiresAtEpochMs")
        if expiry is not None and (type(expiry) is not int or expiry <= time.time() * 1000 + 5000):
            raise BackendError("expired_source")
        ttl = min(600, max(1, math.floor(expiry / 1000 - time.time() - 5))) if expiry else 60
        return path, "".join(lines), mime, ttl
    except (ValueError, TypeError):
        raise BackendError("invalid_backend_response") from None
