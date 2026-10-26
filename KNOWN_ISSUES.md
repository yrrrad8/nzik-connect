# Accepted POC follow-ups

Reviewed on 2026-10-26. The user accepted the current POC scope and left these items open. They are not fixed by this source export.

1. Classify startup connectivity failures as retryable instead of LoginFailed, so MA can recover when the Kotlin backend starts late or becomes unavailable.
2. Preserve temporary metadata failures instead of reporting valid tracks as missing.
3. Avoid blocking playback resolution and cache reads behind a slow request holding the shared client lock.
4. Complete bounded extraction refresh after a CDN rejects an unexpired source on the MA playback path. CLI recovery alone does not establish provider recovery.
5. Verify playback and seek through an actual running MA queue, including next/previous controls. Existing full-song and seek evidence uses MA StreamsAudio directly.
6. Test that provider setup installs the credential-redaction filter, rather than testing the filter object only.
7. Verify secrecy with the values-bearing MA configuration API response as well as the restricted response. Disk encryption was checked separately.
8. State the PowerShell runtime prerequisite or provide a Windows PowerShell 5.1-compatible token-generation example.

The first six items were graded medium and the last two low. No high-severity issue was retained. Physical home-player output and the user's HA deployment remain unverified. The six earlier standalone-server follow-ups also remain: authentication-scheme casing, upstream-ID error classification, library-output Range mapping coverage, foreign-HTTPS-host rejection coverage, PCM assertion diagnostics, and cancellation combined with transient file locks.
