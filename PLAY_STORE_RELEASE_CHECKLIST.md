# OptiShare 2 — Google Play Release Checklist

This file is a release gate, not a feature wish list. A checked item means the implementation exists in the clean branch. Physical-device and Play Console items remain unchecked until they are actually completed.

## Build and signing
- [x] `compileSdk 36`
- [x] `targetSdk 36`
- [x] `minSdk 21`
- [x] Java 17 + core-library desugaring baseline
- [x] Signed release APK from protected GitHub Actions secrets
- [x] Release AAB generated for Google Play
- [x] SHA-256 checksum artifact generated
- [x] Unit tests run before package artifacts
- [x] Android lint gate runs in CI
- [ ] Enable Google Play App Signing and preserve the upload key securely
- [ ] Increment `versionCode` for every Play upload
- [ ] Enable R8/minification only after final compatibility tests

## Core transfer functionality
- [x] True multi-file selection
- [x] In-app photo/video browser
- [x] Wi-Fi Direct discovery and pairing path
- [x] QR pairing accelerator
- [x] Foreground transfer service
- [x] Batch protocol: one logical session for multiple files
- [x] Chunk acknowledgements
- [x] 1 MiB durable resume checkpoints
- [x] Persistent receiver resume offsets
- [x] Persistent outgoing sender session metadata
- [x] Automatic socket reconnect and resume
- [x] Wi-Fi Direct peer/group recovery baseline for sender reconnect
- [x] SHA-256 file verification before publish
- [x] Category-aware public Downloads publishing
- [x] Incoming batch Accept / Decline approval
- [x] Application-layer authenticated encryption
- [x] Mandatory six-digit security-code confirmation before transfer data
- [ ] Foreground in-app approval sheet mirroring notification approval
- [ ] Validate Wi-Fi Direct group recreation on all target OEMs
- [ ] Same-LAN fallback transport
- [ ] Folder/directory transfer
- [ ] In-app Music browser
- [ ] Installed-app browser/APK extraction — only if Play policy scope is approved

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
- [ ] OEM: Google Pixel
- [ ] OEM: Samsung
- [ ] OEM: OnePlus
- [ ] OEM: Xiaomi/Redmi
- [ ] OEM: Oppo/Realme
- [ ] OEM: Honor/Huawei where Google Play distribution applies

## Reliability gates
- [ ] 0-byte file
- [ ] 1-byte file
- [ ] 4 MB transfer target under 10 seconds on normal modern devices
- [ ] 1 GB file
- [ ] 10+ GB file
- [ ] 1000-file batch
- [ ] Arabic filenames
- [ ] Emoji filenames
- [ ] Very long filenames
- [ ] Duplicate filenames
- [ ] Extensionless files
- [ ] Low-storage handling
- [ ] Screen-off transfer
- [ ] Activity recreation during transfer
- [ ] Force-kill UI while foreground service continues
- [ ] Sender process restart restores a persisted pending session
- [ ] Disconnect/reconnect at 10%, 33% and 79%
- [ ] Full Wi-Fi Direct group destruction/recreation during transfer
- [ ] Corrupt partial file recovery
- [ ] Receiver declines transfer
- [ ] Security code declined/mismatch path
- [ ] Approval timeout path
- [ ] Sender cancellation

## Security gates
- [x] Ephemeral ECDH session keys
- [x] HKDF-SHA256 derivation
- [x] AES-256-GCM authenticated frames
- [x] SHA-256 file integrity verification
- [x] Protocol length/count bounds
- [x] Mandatory matching six-digit human verification gate
- [x] Malformed offset/count/chunk unit-test coverage baseline
- [ ] Bind future QR auto-trust to a cryptographic fingerprint
- [ ] Broader malformed/fuzzed frame corpus
- [ ] Resource-exhaustion stress testing
- [ ] Dependency vulnerability review for release candidate
- [ ] Independent security review before public launch

## Performance gates
- [x] 1 MiB transfer checkpoint size to reduce ACK/fsync overhead
- [x] Buffered socket/file streams
- [x] Requested larger TCP send/receive buffers where supported
- [ ] Benchmark 4 MB, 100 MB, 1 GB and 10 GB on physical device pairs
- [ ] Record median/95th percentile throughput, not only peak speed
- [ ] Memory profile 1000-file selection and large galleries
- [ ] ANR profile during hashing and manifest preparation
- [ ] Move expensive manifest hashing to visible preparation state with cancellation if needed

## UX / accessibility
- [x] Search / Connecting / Connected / Reconnecting / Completed states
- [x] Public save path shown to user
- [x] Transfer history baseline
- [x] Editable OptiShare device name
- [x] RecyclerView photo/video gallery baseline
- [ ] ETA display
- [ ] Per-file and whole-batch progress simultaneously
- [ ] Pause / Resume button
- [ ] Full received-files browser inside OptiShare
- [ ] Albums and search in media picker
- [ ] Asynchronous thumbnail cache / memory profiling
- [ ] TalkBack labels and content descriptions
- [ ] Dynamic font-size testing
- [ ] Tablet / foldable layouts
- [x] Manifest RTL support enabled
- [ ] Complete Arabic resource strings
- [ ] Move remaining user-facing English strings from Java to resources

## Storage / permissions
- [x] Android 10+ scoped-storage/MediaStore publishing baseline
- [x] Android 5–9 legacy public Downloads path where required
- [x] Received category folders: Photos, Videos, Music, Apps, Documents, Archives, Other
- [x] Legacy `requestLegacyExternalStorage` application flag removed
- [x] Android 13+ image/video/audio media permissions declared by category
- [ ] Validate Android 14+ partial media access behavior
- [ ] Confirm Play Photos & Videos permission declaration with final shipped picker behavior
- [ ] Avoid `MANAGE_EXTERNAL_STORAGE`
- [ ] Avoid `QUERY_ALL_PACKAGES` unless installed-app sharing is explicitly shipped and approved

## Play Console / policy
- [x] Privacy-policy draft exists in repository
- [x] Data Safety draft exists in repository
- [x] Security model exists in repository
- [x] Production README replaces obsolete optical-prototype documentation
- [ ] Host privacy policy on a public HTTPS URL
- [ ] Replace privacy/security contact placeholders with production support email
- [ ] Complete Data Safety form from the **final binary behavior**
- [ ] Complete Photos and Videos permission declaration if broad media access remains
- [ ] Add support email and website
- [ ] Final adaptive launcher icon and branding assets
- [ ] Feature graphic
- [ ] Phone screenshots
- [ ] Tablet screenshots if tablet distribution is enabled
- [ ] Store title / short description / full description
- [ ] Content rating questionnaire
- [ ] Target audience declaration
- [ ] Ads declaration: No ads for current build
- [ ] Internal test track
- [ ] Closed test track
- [ ] Google Play pre-launch report reviewed with no blocking crash/ANR

## Production decision

Do **not** promote OptiShare to Google Play Production until all applicable physical-device, reliability, security, policy and pre-launch-report gates above are completed or deliberately removed from the public release scope.
