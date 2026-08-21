# OptiShare 2 — Google Play Release Checklist

## Build and signing
- [x] `compileSdk 36`
- [x] `targetSdk 36`
- [x] `minSdk 21`
- [x] Signed release APK from protected GitHub Actions secrets
- [x] Release AAB generated for Google Play
- [x] SHA-256 checksum artifact generated
- [ ] Enable Google Play App Signing and preserve the upload key securely
- [ ] Increment `versionCode` for every Play upload
- [ ] Enable R8/minification only after the final compatibility test matrix passes

## Core functionality
- [x] Multi-file selection
- [x] In-app photo/video browser
- [x] Wi-Fi Direct discovery and pairing path
- [x] QR pairing accelerator
- [x] Foreground transfer service
- [x] Batch protocol
- [x] Chunk acknowledgements
- [x] Persistent receiver resume offsets
- [x] Automatic socket reconnect and resume
- [x] SHA-256 file verification
- [x] Category-aware Downloads publishing
- [x] Application-layer authenticated encryption
- [ ] Full Wi-Fi Direct group recreation after the radio/group itself is destroyed
- [ ] Sender process-death session reconstruction from persistent metadata
- [ ] Explicit incoming batch Accept / Decline confirmation instead of receive-mode auto-consent
- [ ] Installed-app browser and APK extraction, if shipped
- [ ] Same-LAN fallback transport
- [ ] Folder transfer

## Compatibility gates
- [ ] Physical-device test: Android 5 / API 21
- [ ] Android 6 / API 23
- [ ] Android 7 / API 24
- [ ] Android 9 / API 28
- [ ] Android 10 / API 29
- [ ] Android 11 / API 30
- [ ] Android 12 / API 31/32
- [ ] Android 13 / API 33
- [ ] Android 14 / API 34
- [ ] Android 15 / API 35
- [ ] Android 16 / API 36
- [ ] OEM tests: Google Pixel
- [ ] Samsung
- [ ] OnePlus
- [ ] Xiaomi/Redmi
- [ ] Oppo/Realme
- [ ] Honor/Huawei where Google Play distribution is applicable

## Reliability tests
- [ ] 0-byte file
- [ ] 1-byte file
- [ ] 4 MB file target under 10 seconds on normal modern devices
- [ ] 1 GB file
- [ ] 10+ GB file
- [ ] 1000-file batch
- [ ] Arabic filenames
- [ ] Emoji filenames
- [ ] Long filenames
- [ ] Duplicate filenames
- [ ] Extensionless files
- [ ] Low-storage handling
- [ ] Screen-off transfer
- [ ] Activity recreation during transfer
- [ ] Force-kill UI while foreground service continues
- [ ] Disconnect/reconnect at 10%, 33%, and 79%
- [ ] Corrupt partial file recovery
- [ ] Receiver declines transfer
- [ ] Sender cancellation

## Security gates
- [x] Ephemeral ECDH session keys
- [x] HKDF-SHA256 derivation
- [x] AES-256-GCM authenticated frames
- [x] SHA-256 file integrity verification
- [x] Protocol length/count bounds
- [ ] Require user confirmation of the matching six-digit security code, or authenticate the peer via QR fingerprint, before sending sensitive data
- [ ] Threat-model review for MITM, replay, malicious filenames, oversized metadata, and resource exhaustion
- [ ] Run Android lint and dependency review on every release
- [ ] Independent security review before public launch

## UX / accessibility
- [x] Visible Search / Connecting / Connected / Reconnecting / Completed states
- [x] Public save path shown to the user
- [x] Transfer history baseline
- [ ] ETA display
- [ ] Per-file + whole-batch progress simultaneously
- [ ] Pause / Resume control
- [ ] Full received-files browser inside OptiShare
- [ ] Albums and search in media picker
- [ ] Cached/asynchronous thumbnails
- [ ] TalkBack labels and content descriptions
- [ ] Dynamic font-size testing
- [ ] Tablet / foldable layouts
- [ ] RTL and Arabic strings
- [ ] English strings moved out of Java into resources

## Play Console / policy
- [x] Privacy policy draft exists in repository
- [ ] Host the privacy policy on a public HTTPS URL
- [ ] Replace privacy-policy contact placeholder with production support email
- [ ] Complete Data Safety form from the final binary behavior
- [ ] Complete Photos and Videos permission declaration if broad media access remains in the release
- [ ] If `QUERY_ALL_PACKAGES` is ever added, complete the package visibility declaration and verify eligibility
- [ ] Add support email and website
- [ ] App icon, feature graphic, phone screenshots, tablet screenshots where required
- [ ] Store listing: title, short description, full description
- [ ] Content rating questionnaire
- [ ] Target audience declaration
- [ ] Ads declaration: No ads for the current build
- [ ] Closed/internal testing track before production
- [ ] Pre-launch report reviewed with no blocking crashes/ANRs

## Release decision
Do not promote to Production until every unchecked item that applies to the planned public feature set is either completed or explicitly removed from release scope.
