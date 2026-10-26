"""Verify provider discovery, encrypted setup and search on a fresh disposable MA server."""

from __future__ import annotations

import asyncio
import json
import os
import secrets

from aiohttp import ClientError, ClientSession, ClientTimeout, WSMsgType


async def run() -> dict:
    """Use MA's public setup and WebSocket APIs without printing credentials."""
    backend_token = os.environ["NZIK_CONNECT_TOKEN"]
    async with ClientSession(timeout=ClientTimeout(total=120)) as session:
        for attempt in range(60):
            try:
                async with session.get("http://127.0.0.1:8095/info", timeout=ClientTimeout(total=2)) as response:
                    if response.status == 200:
                        break
            except (ClientError, TimeoutError):
                pass
            if attempt == 59:
                raise RuntimeError("server_startup_timeout")
            await asyncio.sleep(1)
        async with session.post(
            "http://127.0.0.1:8095/setup",
            json={"username": "nzik-poc-test", "password": secrets.token_urlsafe(32), "device_name": "NZIK POC smoke"},
        ) as response:
            if response.status != 200:
                raise RuntimeError("fresh_disposable_server_required")
            authentication = await response.json()
        async with session.ws_connect("http://127.0.0.1:8095/ws") as websocket:
            sequence = 0

            async def command(name: str, args: dict | None = None):
                nonlocal sequence
                sequence += 1
                message_id = str(sequence)
                await websocket.send_json({"message_id": message_id, "command": name, "args": args or {}})
                async with asyncio.timeout(120):
                    async for incoming in websocket:
                        if incoming.type != WSMsgType.TEXT:
                            raise RuntimeError("websocket_closed")
                        message = json.loads(incoming.data)
                        if message.get("message_id") != message_id:
                            continue
                        if "error_code" in message:
                            raise RuntimeError(f"command_failed_{name}_{message['error_code']}")
                        if not message.get("partial"):
                            return message.get("result")
                raise RuntimeError("missing_command_response")

            await command("auth", {"token": authentication["token"]})
            manifests = await command("providers/manifests")
            if not any(row["domain"] == "nzik" for row in manifests):
                raise RuntimeError("provider_not_discovered")
            await command("config/onboard_complete")
            step = await command("config/providers/setup", {"provider_domain": "nzik"})
            if step.get("type") != "form":
                raise RuntimeError("connection_form_missing")
            step = await command("config/flows/submit", {
                "flow_id": step["flow_id"],
                "values": {"server_url": "http://127.0.0.1:8765", "token": backend_token},
            })
            if step.get("type") != "finish":
                raise RuntimeError("provider_setup_failed")
            instance_id = step["result"]["instance_id"]
            results = await command("music/search", {
                "search_query": "Daft Punk Get Lucky", "media_types": ["track"],
                "limit": 2, "library_only": False, "providers": [instance_id],
            })
            tracks = results["tracks"]
            if not tracks or any(row["provider"] != instance_id for row in tracks):
                raise RuntimeError("server_search_failed")
            if any("googlevideo.com" in json.dumps(row) for row in tracks):
                raise RuntimeError("private_source_serialized")
            configs = await command("config/providers", {"provider_domain": "nzik", "include_values": False})
            if backend_token in json.dumps(configs):
                raise RuntimeError("private_token_serialized")
            return {
                "status": "pass", "providerDiscovered": True, "setupFinished": True,
                "searchCount": len(tracks), "trackIds": [row["item_id"] for row in tracks],
                "configurationTokenHidden": True,
            }


async def main() -> None:
    """Fail with a safe diagnostic rather than dumping API response data."""
    try:
        async with asyncio.timeout(300):
            result = await run()
    except Exception as error:
        # Deliberate local codes contain no remote messages, tokens or URLs.
        reason = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print(json.dumps({"status": "fail", "reason": reason}))
        raise SystemExit(1) from None
    print(json.dumps(result))


if __name__ == "__main__":
    asyncio.run(main())
