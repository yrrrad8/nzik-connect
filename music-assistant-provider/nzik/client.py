"""Authenticated adapter to the standalone NZIK catalog service."""

from __future__ import annotations

import asyncio
import json
import re
import time
from collections import OrderedDict
from typing import Any
from urllib.parse import urlsplit

from aiohttp import ClientError, ClientSession, ClientTimeout


class BackendError(Exception):
    """Expose only a stable failure code to Music Assistant."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def validate_connection(base_url: str, token: str) -> str:
    """Accept only an authenticated loopback connection for this POC."""
    try:
        parsed = urlsplit(base_url)
        port = parsed.port
    except ValueError:
        raise BackendError("invalid_connection") from None
    if (
        parsed.scheme not in ("http", "https")
        or parsed.hostname not in ("127.0.0.1", "::1")
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in ("", "/")
        or parsed.query
        or parsed.fragment
        or port is None
        or not 1 <= port <= 65535
        or not isinstance(token, str)
        or not 32 <= len(token) <= 256
        or any(not 33 <= ord(char) <= 126 for char in token)
    ):
        raise BackendError("invalid_connection")
    return base_url.rstrip("/")


def validate_track_id(item_id: str) -> None:
    """Keep caller-controlled paths out of backend requests."""
    if not isinstance(item_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{11}", item_id):
        raise BackendError("invalid_track")


class NzikClient:
    """Bound requests and cache catalog lookups, never stream URLs."""

    def __init__(self, session: ClientSession, base_url: str, token: str) -> None:
        self.base_url = validate_connection(base_url, token)
        self._token = token
        self._session = session
        self._lock = asyncio.Lock()
        self._next_request = 0.0
        self._cache: OrderedDict[str, tuple[float, Any]] = OrderedDict()

    async def capabilities(self) -> dict[str, Any]:
        """Check backend protocol compatibility during setup."""
        result = await self._get("/v1/capabilities")
        if (
            not isinstance(result, dict)
            or result.get("protocolVersion") != 1
            or "track" not in result.get("searchMediaTypes", [])
            or "DIRECT" not in result.get("sourceTransports", [])
        ):
            raise BackendError("incompatible_backend")
        return result

    async def search(self, query: str, limit: int) -> list[dict[str, Any]]:
        """Search the Kotlin catalog."""
        query = query.strip()
        if not query or len(query) > 300 or not 1 <= limit <= 50:
            raise BackendError("invalid_query")
        result = await self._get("/v1/search", {"q": query, "limit": str(limit)}, ttl=15)
        if not isinstance(result, list):
            raise BackendError("invalid_backend_response")
        return result[:limit]

    async def track(self, item_id: str) -> dict[str, Any]:
        """Fetch reusable metadata without resolving audio."""
        validate_track_id(item_id)
        result = await self._get(f"/v1/tracks/{item_id}", ttl=60)
        if not isinstance(result, dict) or result.get("id") != item_id:
            raise BackendError("invalid_backend_response")
        return result

    async def source(self, item_id: str) -> dict[str, Any]:
        """Resolve immediately before playback; never cache the response."""
        validate_track_id(item_id)
        result = await self._get(f"/v1/tracks/{item_id}/source")
        if not isinstance(result, dict) or result.get("trackId") != item_id:
            raise BackendError("invalid_backend_response")
        return result

    async def _get(
        self, path: str, params: dict[str, str] | None = None, ttl: int = 0
    ) -> Any:
        key = path + repr(params)
        async with self._lock:
            now = time.monotonic()
            if ttl and (cached := self._cache.get(key)) and cached[0] > now:
                self._cache.move_to_end(key)
                return cached[1]
            await asyncio.sleep(max(0.0, self._next_request - now))
            self._next_request = time.monotonic() + 0.5
            try:
                async with self._session.get(
                    self.base_url + path,
                    params=params,
                    headers={"Authorization": f"Bearer {self._token}"},
                    timeout=ClientTimeout(total=100, connect=5, sock_read=95),
                    allow_redirects=False,
                ) as response:
                    if response.status in (401, 403):
                        raise BackendError("authentication_failed")
                    if response.status == 429:
                        self._next_request = time.monotonic() + 5
                        raise BackendError("backend_busy")
                    if response.status != 200:
                        raise BackendError("backend_unavailable")
                    # Catalog responses are small; bound malformed or compromised backends.
                    body = bytearray()
                    async for chunk in response.content.iter_chunked(16_384):
                        body.extend(chunk)
                        if len(body) > 1_048_576:
                            raise BackendError("invalid_backend_response")
                    result = json.loads(body)
            except (ClientError, TimeoutError):
                raise BackendError("backend_unavailable") from None
            except (ValueError, UnicodeError):
                raise BackendError("invalid_backend_response") from None
            if ttl:
                self._cache[key] = (time.monotonic() + ttl, result)
                self._cache.move_to_end(key)
                while len(self._cache) > 64:
                    self._cache.popitem(last=False)
            return result
