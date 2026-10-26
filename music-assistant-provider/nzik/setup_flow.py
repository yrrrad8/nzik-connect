"""Interactive connection setup using MA's encrypted setup data."""

from __future__ import annotations

from typing import TYPE_CHECKING

from music_assistant_models.config_entries import ConfigEntry
from music_assistant_models.enums import ConfigEntryType

from music_assistant.models.setup_flow import SetupFlowError

if TYPE_CHECKING:
    from music_assistant.models.setup_flow import SetupSession


async def run_setup(session: SetupSession) -> None:
    """Collect credentials without prefilling or exposing the saved token."""
    values = dict(session.context.setup_data)
    errors: dict[str, str] | None = None
    while True:
        entries = [
            ConfigEntry(
                key="server_url", type=ConfigEntryType.STRING, label="NZIK server URL",
                default_value="http://127.0.0.1:8765", value=values.get("server_url"), required=True,
                description="Loopback address shared by Music Assistant and the Kotlin server.",
            ),
            ConfigEntry(
                key="token", type=ConfigEntryType.SECURE_STRING, label="NZIK API token",
                required=not bool(values.get("token")),
                description="The server's NZIK_CONNECT_TOKEN; kept encrypted by Music Assistant.",
            ),
        ]
        submitted = await session.form(entries, step_id="connection", errors=errors, last_step=True)
        values.update((key, value) for key, value in submitted.items() if key != "token" or value)
        try:
            await session.finish(values)
            return
        except SetupFlowError:
            errors = {"base": "NZIK connection failed. Check the server address and API token."}
