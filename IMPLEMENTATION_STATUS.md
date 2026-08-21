# OptiShare 2 — Implementation Status

## Implemented in source
- API 21 minimum and API 36 compile/target baseline.
- One active OptiShare 2 CI workflow with tests, lint, debug APK, signed release APK, Play AAB and checksums.
- Device identity and editable local display name.
- Multi-file selection.
- In-app Photos and Videos browsing with RecyclerView-based multi-selection.
- Wi-Fi Direct discovery, connection states and receiver mode.
- Optional QR pairing accelerator.
- Foreground transfer service.
- Batch manifest containing stable file IDs, MIME/category, size and SHA-256.
- Ephemeral ECDH + HKDF-SHA256 + AES-256-GCM application-layer transfer encryption.
- Per-chunk authenticated frames and acknowledgements.
- File-completion and batch-completion acknowledgements.
- Receiver partial-file persistence and safe resume offsets.
- Sender pending-session persistence for process recreation.
- Automatic socket reconnect with bounded backoff.
- Verified-file markers so already-completed files are skipped after reconnect.
- SHA-256 verification before publishing received content.
- Category-aware output under Download/OptiShare.
- Incoming transfer approval notification with Accept / Decline actions.
- Local transfer history baseline.
- Privacy policy draft and Play Data Safety engineering draft.

## Release blockers that require validation or additional implementation
- GitHub Actions must be green after the latest source changes.
- Physical Android 5 through Android 16 compatibility matrix is not yet executed.
- Cross-OEM Wi-Fi Direct behavior must be verified on Samsung, Pixel, OnePlus, Xiaomi/Redmi, Oppo/Realme and other intended devices.
- If the entire Wi-Fi Direct group is destroyed (not just the TCP socket), automatic radio-level rediscovery/reconnection still needs a dedicated persistent discovery controller and device-matrix testing.
- In-app incoming request dialog should complement notification actions for the best foreground UX.
- Six-digit security code is displayed by the protocol but user-confirmation / QR-authenticated peer trust must be enforced before claiming MITM-resistant authenticated pairing.
- Installed-app browser/APK extraction is not part of the current Play-safe scope; current Apps flow is APK-file selection.
- Same-LAN fallback, folder transfer, internal Music/Documents browser, full albums/search, cached thumbnail loader, ETA, pause, full Arabic UI resources and accessibility pass remain product enhancements.
- Google Play privacy policy must be hosted at a public HTTPS URL and supplied with final developer contact details.
- Play Console permission declarations and Data Safety form must match the final AAB exactly.

## Release rule
Do not label a build Production/Stable merely because it compiles. A public release candidate requires green CI plus the device, disconnect, large-file, permission, storage, security and Play-policy gates in `PLAY_STORE_RELEASE_CHECKLIST.md`.
