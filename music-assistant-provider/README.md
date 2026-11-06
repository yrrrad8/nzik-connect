# NZIK Music Assistant POC

An installable music provider for **Music Assistant 2.10.8**, backed by the existing
Java 21 Kotlin `connect-server` and pinned InnerTubeX v0.11.2. Python only adapts MA's
interfaces; it does not extract YouTube audio. The phone is never involved.

## Scope

- Search songs, retrieve track metadata, and queue/play compatible HTTP Direct sources.
- Use MA's normal player selection, queue, volume, next/previous and seek controls.
- Resolve fresh audio immediately before playback; Kotlin handles near-expired sources.
- Anonymous, publicly playable content only. Account-only, subscription-restricted or
  region-restricted content is not promised. No personal library, album/artist/playlist
  browsing, Android handoff or NZIK Connect state protocol is implemented.
- Bounded Range, SABR and HLS sources are explicitly rejected by this adapter. The Kotlin
  CLI's broader transport support does not imply support by this provider.

MA 2.10.8 scans providers inside its installed package. It cannot install this provider
from a repository URL or a `custom_providers` directory. This package builds a derived
official MA image, adding only `music_assistant/providers/nzik`. **It is not installable
into the official Home Assistant App through its normal UI.** Deployment alongside HA
requires Docker, or a separately packaged HA App in later work. Do not replace an existing
MA installation or point the POC at its data directory.

## Start an isolated local installation

Prerequisites: Docker with Linux containers and Compose 2.24.4 or newer, internet access,
and port 8095 available. Run from the N-Zik repository root. Building the Kotlin image
runs its JVM tests and does not require Android.

Linux shell:

```sh
export NZIK_CONNECT_TOKEN="$(openssl rand -hex 32)"
docker compose -f music-assistant-provider/compose.yaml up --build -d
```

PowerShell:

```powershell
$env:NZIK_CONNECT_TOKEN = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
docker compose -f music-assistant-provider/compose.yaml up --build -d
```

Open http://127.0.0.1:8095, create the local MA administrator, then add the music provider
**NZIK Connect (POC)**. Enter `http://127.0.0.1:8765` and the same `NZIK_CONNECT_TOKEN`.
Use your shell's local variable to paste the token privately; never send it in screenshots
or commit it. MA encrypts the saved connection settings. Blank token input during later
reconfiguration preserves the existing token.

The default Compose installation publishes only MA's UI on host loopback. Kotlin's
authenticated API stays on loopback inside their shared network namespace. No open proxy
or public source endpoint is created. Keep the same token in the deployment environment
across restarts; changing it requires updating the provider's settings too.

Search for `Daft Punk Get Lucky`, choose a track, and select a configured MA player.
The default Docker bridge installation is useful for setup and automated audio checks;
LAN discovery and playback to real speakers need suitable Linux host networking.

## Linux host with home players

On the target Linux server, first verify that another MA server does not occupy MA's ports.
Use the same isolated POC data volume and token:

```sh
docker compose -f music-assistant-provider/compose.yaml \
  -f music-assistant-provider/compose.host.yaml up --build -d
```

Both services then use host networking. The NZIK API remains bound to `127.0.0.1:8765`;
only MA communicates with it. Configure the speakers through MA. MA's authenticated UI
and player streaming endpoints become reachable on the host's LAN addresses. Keep this
installation on a trusted home network. Opening the phone is not required.

## Verification

Adapter tests use real MA models, aiohttp fixtures and the pinned Python runtime.
The metadata regressions also use MA's actual track, album and artist controllers,
URI resolution and queue entry point. Only storage, network and player boundaries
are simulated. Explicit queue `ADD` stages a track without starting playback.

From the repository root, build the test image if needed, then run the suite without
production data, device discovery or published ports. The provider mount tests the
current source even when the image was built before a local edit:

```sh
docker compose -f music-assistant-provider/compose.yaml build music-assistant
docker run --rm --network none --read-only --tmpfs /tmp:rw,nosuid,nodev,size=128m \
  --entrypoint python -v "${PWD}/music-assistant-provider:/work:ro" \
  -v "${PWD}/music-assistant-provider/nzik:/app/venv/lib/python3.14/site-packages/music_assistant/providers/nzik:ro" \
  nzik-music-assistant:2.10.8-poc -B -m unittest discover -s /work/tests -p 'test_*.py' -v
```

Tracks carry complete artist metadata with the existing name-derived IDs. Standalone
artist and album lookups return MA's typed `MediaNotFoundError`; the normal track
controller retains the album label through its fallback. These embedded values do
not add artist/album catalog capabilities or require a warm search cache.

For a live full-song/seek/second-track check, start the services first, then:

```sh
docker run --rm --network "container:$(docker compose -f music-assistant-provider/compose.yaml ps -q music-assistant)" \
  --entrypoint python -e NZIK_CONNECT_TOKEN \
  -v "${PWD}/music-assistant-provider:/work:ro" -w /work \
  nzik-music-assistant:2.10.8-poc tests/integration_smoke.py
```

The smoke instantiates the real provider and MA `StreamsAudio`, consumes PCM in memory,
and prints a sanitized JSON result. It starts at the decoder boundary and does not
exercise catalog hydration or normal queue entry. It does not attach physical hardware
or persist audio. The provider installs a logging filter on MA's root handlers to redact
googlevideo media addresses and credential headers, including upstream FFmpeg exception
tails. Keep normal logging at info level and inspect diagnostics before sharing them.

Verified on 2026-10-26: all eight adapter tests passed. Actual Compose startup, provider
discovery/setup and MA server search passed; the saved token was encrypted on disk and
absent from the configuration API. The local decoder helper decoded 248.413 seconds for
`Rgrt_8mXrK8`, seek to 30 seconds decoded 218.421 seconds, and `4D7u5KF7SP8` decoded a
five-second sample. Physical speaker output and the user's HA deployment remain unverified.
`tests/install_smoke.py` separately verifies discovery, setup and search against a fresh,
disposable MA server in that namespace. It creates an administrator with a random password;
never run it against an installation you intend to keep. Both container builds and
Android `assembleDebug` passed. Kotlin tasks were unchanged and up-to-date.

Verified on 2026-10-27: all 13 adapter and metadata/queue regression tests passed on
MA 2.10.8 and the tested MA 2.10.6 image, both using models 1.1.205. The unchanged
original adapter failed the same metadata/queue regressions with `NotImplementedError`
in both versions. Cold-cache loading, retained artist/album names, absent artwork,
optional metadata and typed unsupported lookups are covered. Physical playback of
this repair remains pending; these local results do not establish speaker output.

## Stop and rollback

```sh
docker compose -f music-assistant-provider/compose.yaml down
```

Add the host override to the command if used at startup. The dedicated POC volume is
retained; existing MA/HA data is untouched. Do not add `--volumes` unless intentionally
discarding the POC's configuration. There is no push or published image in this workflow.

See [NOTICE.md](NOTICE.md) and [the Kotlin server README](../connect-server/README.md)
for licensing and upstream attribution.
