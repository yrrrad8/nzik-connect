"""Keep upstream decoder diagnostics from disclosing NZIK media credentials."""

from __future__ import annotations

import logging
import re
import traceback


_URL = re.compile(r"https?://[^\s\"'<>]+", re.IGNORECASE)
_HEADER = re.compile(
    r"\b(Authorization|Cookie|Set-Cookie|X-Goog-Visitor-Id|X-Youtube-Identity-Token)\s*:[^\r\n]*",
    re.IGNORECASE,
)


def redact_media_credentials(text: str) -> str:
    """Retain diagnostics while removing media URLs and authentication headers."""
    text = _URL.sub(
        lambda match: "<NZIK media URL>" if ".googlevideo.com" in match[0].lower() else match[0],
        text,
    )
    return _HEADER.sub(lambda match: match[1] + ": <redacted>", text)


class MediaCredentialFilter(logging.Filter):
    """Sanitize FFmpeg messages and exception tails before handlers serialize them."""

    def filter(self, record: logging.LogRecord) -> bool:
        message = record.getMessage()
        sanitized = redact_media_credentials(message)
        if sanitized != message:
            record.msg, record.args = sanitized, ()
        if record.exc_info:
            exception = "".join(traceback.format_exception(*record.exc_info))
            sanitized_exception = redact_media_credentials(exception)
            if sanitized_exception != exception:
                record.exc_text = sanitized_exception
                record.exc_info = None
        return True


def install_media_log_filter() -> None:
    """Cover MA's root queue handler, including messages emitted by child loggers."""
    for handler in logging.getLogger().handlers:
        if not any(isinstance(item, MediaCredentialFilter) for item in handler.filters):
            handler.addFilter(MediaCredentialFilter())
