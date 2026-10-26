FROM ghcr.io/music-assistant/server:2.10.8@sha256:661e88bea76a9e79f38feca791f6c7373376ab60766ee01f56b66418ca243b68
COPY music-assistant-provider/nzik /tmp/nzik-provider
RUN PROVIDERS="$(python -c 'import sysconfig; print(sysconfig.get_path("purelib"))')/music_assistant/providers" \
    && test -d "$PROVIDERS" \
    && test ! -e "$PROVIDERS/nzik" \
    && mv /tmp/nzik-provider "$PROVIDERS/nzik" \
    && python -m compileall -q "$PROVIDERS/nzik"
LABEL org.opencontainers.image.source="https://github.com/N-Zik-Group/N-Zik" \
    org.opencontainers.image.title="Music Assistant with NZIK POC" \
    org.opencontainers.image.licenses="Apache-2.0 AND GPL-3.0-only"
