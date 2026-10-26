# NZIK Connect JVM playback proof

This module runs independently of Android and the phone. It reuses NZIK's JVM
`:oldtube` search/metadata parsers and its pinned InnerTubeX `v0.11.2` extractor.
It is the first playback proof, not a complete Connect server or Music Assistant provider.

## Build

Requirements: Java 21, FFmpeg on PATH, and network access to the configured Gradle
repositories and YouTube Music. No Android SDK, Android device or account cookie
is required for this anonymous proof. Some tracks/clients may require PO tokens;
this increment does not generate browser tokens and reports failures accurately.

From the repository root:

```sh
./gradlew -PconnectOnly=true :connect-server:test :connect-server:installDist
connect-server/build/install/connect-server/bin/connect-server --help
```

On Windows use `gradlew.bat` and
`connect-server\build\install\connect-server\bin\connect-server.bat`.
The `connectOnly` flag excludes Android and unrelated modules from configuration.
Normal Android builds continue to use the default settings.
The tests use FFmpeg to generate and decode local WebM and MP4 samples; they do
not contact YouTube. FFmpeg must therefore be available for tests as well as smoke.

## Live smoke

```sh
connect-server/build/install/connect-server/bin/connect-server search --query "Daft Punk Get Lucky"
connect-server/build/install/connect-server/bin/connect-server smoke --query "Daft Punk Get Lucky" --seconds 10
```

The smoke performs a fresh search, obtains metadata for the first matching video
ID, resolves that same track, consumes its audio and decodes it with FFmpeg.
Success requires a zero decoder exit and enough 8 kHz mono 16-bit PCM for at least
90% of the requested duration. A short track reduces the requested duration.
An HTTP success or a positive count of downloaded bytes cannot pass this check.

The final tagged log line contains a JSON report with `status`, `track`,
`transport`, `mediaBytes`, `pcmBytes`, `decodedDurationMs` and `refreshedSource`.
Failures emit a sanitized reason and exit nonzero. Diagnostics and media captures
are temporary and removed; signed URLs, headers and raw exceptions are not logged.
Output uses tagged JVM logging under the approved server-only exception to Timber.

Options for `smoke`:

| Option | Default | Allowed values |
|---|---|---|
| `--query` | Required | Nonblank, at most 300 characters |
| `--seconds` | `10` | `1..30` |
| `--max-bytes` | `8` | `2..32` MiB per capture |
| `--deadline` | `180` | `30..300` seconds for the complete smoke |
| `--ffmpeg` | `ffmpeg` | Executable name or local executable path |

Metadata can also be requested with `track --id VIDEO_ID` (11-character YouTube ID).

## Transport boundaries

- Direct streams retain required request headers and are sampled within a byte budget.
- Bounded Range streams require matching `206`, `Content-Range`, body length and offsets.
- SABR uses InnerTubeX's experimental `SabrAudioStream`, retaining initialization
  and media order. It is not treated as a downloadable direct URL.
- HLS is explicitly disabled in extraction capabilities for this proof.
- Expired or rejected media triggers at most one reset of cipher/player-configuration
  caches and fresh extraction. The reset waits for any active extraction.
- Cancellation unwinds response/Flow resources, kills FFmpeg and deletes captures.
- FFmpeg receives only local captures, with file/pipe protocols and known audio demuxers.

The Kotlin code does not reimplement YouTube signatures, deciphering or UMP/SABR.
It does not use yt-dlp, Bluetooth, mirroring or phone audio streaming.

## Linux container smoke

Run these from the repository root with a functioning Linux Docker engine:

```sh
docker build -f connect-server/Dockerfile -t nzik-connect-poc .
docker run --rm nzik-connect-poc smoke --query "Daft Punk Get Lucky" --seconds 10
```

The runtime uses Java 21 and FFmpeg as an unprivileged user. It exposes no network
listener. A successful container run is the Linux playback evidence; building the
image alone is insufficient. If the engine, repository downloads or YouTube access
are unavailable, run this manual smoke and record the report before claiming success.

## Initial loopback API

The live playback gate passed on Windows and in the Linux container on 2026-10-25:
track `4D7u5KF7SP8`, Direct transport, 628,294 media bytes, 160,000 PCM bytes,
10,000 ms decoded. This proves an audio sample, not full-song playback or live SABR.

Set `NZIK_CONNECT_TOKEN` to a randomly generated printable token of 32..256 characters.
The token is read from the environment and never logged. Start the local API:

```sh
export NZIK_CONNECT_TOKEN="$(openssl rand -hex 32)"
connect-server/build/install/connect-server/bin/connect-server serve --port 8765
```

The listener always binds to `127.0.0.1`. Every endpoint requires
`Authorization: Bearer TOKEN` and returns `Cache-Control: no-store`.

| GET endpoint | Response |
|---|---|
| `/v1/capabilities` | Supported search types and external source transports |
| `/v1/search?q=QUERY&limit=20` | Track list; limit `1..50` |
| `/v1/tracks/VIDEO_ID` | Track metadata |
| `/v1/tracks/VIDEO_ID/source` | Direct/Range URL, required headers, expiry and range constraints |

Source responses contain signed URLs and must be kept private. They are not logged.
Clients must honor their expiry, headers and bounded-range fields. Expired sources
are resolved again once; repeated rejection returns `503`. Invalid requests return
`400`, missing/incorrect authentication `401`, and upstream failures `502`/`504`.
The external API disables SABR in its extraction capabilities so supported
Direct/Range alternatives can be selected. It defensively returns `501` if an
unsupported SABR descriptor is supplied; the CLI retains library SABR support.

On a Linux Docker host, `--network host` allows the container's loopback listener
to be reached locally. Keep the token in the environment; do not publish the API
on a LAN interface. Music Assistant provider integration, player discovery, pairing,
queue synchronization, Android UI, HLS and browser token minting remain later work.

On Windows app-host environments where JVM NIO reports a loopback failure, a short
temporary directory can be set for the current process only:

```powershell
New-Item -ItemType Directory -Path '.gradle\jtmp' -Force | Out-Null
$taskTmp = (Resolve-Path '.gradle\jtmp').Path
$env:JDK_JAVA_OPTIONS = "-Djava.io.tmpdir=$taskTmp -Djdk.net.unixdomain.tmpdir=$taskTmp"
```

## License and upstream reference

This module follows the repository's GPL-3.0 license. InnerTubeX and its harness are
GPL-3.0; their QuackJS dependency is GPL-3.0-only. Distributions must include the
applicable licenses and corresponding source under those terms.

Initialization and the local capture/PCM verification approach reference the pinned
[InnerTubeX harness](https://github.com/MetrolistGroup/innertubex/tree/v0.11.2/harness).
Its internal harness classes are not published library APIs. NZIK reuses the public
extractor and SABR interfaces instead of copying the complete harness.
