# NZIK Connect POC

Standalone Kotlin/JVM music server and Music Assistant provider, published as a deployment snapshot of reviewed NZIK work. No Android application source or application Git history is included.

## Run

Follow [the provider installation guide](music-assistant-provider/README.md) for Docker Compose, or [the Kotlin server guide](connect-server/README.md) for the independent CLI/API. Run commands from this repository root; references to the N-Zik repository root in those original guides mean this directory.

```sh
export NZIK_CONNECT_TOKEN="$(openssl rand -hex 32)"
docker compose -f music-assistant-provider/compose.yaml up --build -d
```

The default UI is available at http://127.0.0.1:8095. For a Linux host with home players, use the host-network override documented in the installation guide. The Kotlin service stays authenticated on loopback. Music Assistant 2.10.8 is pinned; it runs independently of the phone.

For a native JVM build, Java 21 and FFmpeg are required:

```sh
./gradlew -PconnectOnly=true :connect-server:test :connect-server:installDist
```

The original Gradle layout is preserved for code sharing. Always use `-PconnectOnly=true` for native Gradle commands in this snapshot; Docker already supplies it. This repository does not include the Android modules that the full upstream build normally loads.

## Verified scope and limitations

Search, metadata, provider discovery/setup and real decoding through MA's audio engine were verified locally. A complete track decoded 248.413 seconds; seeking to 30 seconds decoded the expected remainder; a second track produced audio. Sixteen focused server tests and the Docker builds passed. After the metadata-hydration fix, all thirteen provider tests passed on MA 2.10.6 and 2.10.8 with models 1.1.205.

On 2026-11-06, the provider was updated in an existing home MA 2.10.8 installation. Stronger Closer by 8th Day played through NZIK Connect on ReSpeaker Salon Music for more than ninety seconds, and audible playback was confirmed. The phone was not involved. This was a short smoke test; full-track completion, seeking, source expiration, reconnect and extended reliability remain unverified on that home installation. Opening the track-detail page stalled in the first browser tab; playback from search succeeded in a fresh tab.

This is a POC for anonymous publicly playable tracks and Direct HTTP audio. See [known issues](KNOWN_ISSUES.md) for the eight accepted follow-ups. Personal libraries, album/artist/playlist browsing, Android handoff and Range/SABR/HLS playback through this adapter are not implemented. The official HA App has no external-provider installer; this snapshot uses Docker packaging.

## Source and licensing

The implementation is an unchanged tracked-file export from reviewed local NZIK revision `fede4c3b4406a8165de33a0ce9411241f793074e`. The canonical project is [N-Zik](https://github.com/N-Zik-Group/N-Zik); this local revision has not been published upstream. [SOURCE_SNAPSHOT.json](SOURCE_SNAPSHOT.json) records source paths, Git blob hashes and modes so later exports can be audited without maintaining a second implementation.

Source code is covered by the included [GPL-3.0 license](LICENSE). Preserve the original notices and [provider attribution](music-assistant-provider/NOTICE.md). Music Assistant remains its separately licensed upstream software. No tokens, local settings, generated build files or binary application packages are included.
