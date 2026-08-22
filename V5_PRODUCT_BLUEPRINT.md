# OptiShare 5 — Product Blueprint

## Product promise
OptiShare is a private, local-first, high-speed device-to-device sharing product. The user should be able to open the app, choose Send or Receive, discover a nearby device, approve the peer, and move content with clear progress and recovery when connectivity changes.

## Non-negotiable product qualities
- No account required for nearby transfer.
- No cloud dependency for local transfer.
- Stable application identity: `com.kenan.optishare`.
- Android 6.0+ baseline (`minSdk 23`) for the core product.
- Modern Android 13–16 permission behavior without breaking older devices.
- Explicit peer approval before incoming content is accepted.
- Encrypted session protocol and verified received files.
- Resumable sessions after transient disconnects.
- Foreground transfer service for long-running transfers.
- Useful transfer history and received-file management.
- Release builds use shrinking/minification and the production signing configuration.

## Experience architecture

### Home
The home screen is the product identity, not a debug menu. It exposes two dominant actions: Send and Receive. Secondary actions are Received, History, and Settings. A recent-transfer card provides continuity.

### Send
1. Choose photos/videos, files, apps, or other supported content.
2. Review the transfer queue before discovery.
3. Discover nearby OptiShare peers.
4. Select a peer and display a human-verifiable identity/approval step.
5. Transfer with aggregate and per-item progress, current speed, transferred bytes, and remaining work.
6. Recover transparently from short connection drops; expose a deliberate retry/resume path for longer failures.
7. Show a meaningful completion summary.

### Receive
1. Enter visible/ready state with clear privacy explanation.
2. Show the requesting peer and transfer summary before accepting.
3. Accept or reject explicitly.
4. Receive into safe app-managed staging storage.
5. Verify completion before publishing the file as received.
6. Make completed content easy to open/share from the Received screen.

## Engineering boundaries

### UI layer
Activities/screens own rendering, navigation, permission explanation, and user intent. They must not own socket/protocol implementation.

### Transfer orchestration
`TransferEngine` and `TransferService` own session lifecycle, progress, cancellation, foreground execution, and recovery.

### Connectivity
`P2pConnectionCoordinator` owns discovery/connection transport behavior. UI receives state instead of manipulating Wi-Fi APIs directly.

### Protocol
`SessionWire`, `BatchManifest`, and resumable protocol classes own the wire contract. Protocol messages are versioned and validated before use.

### Security
`CryptoSession` owns cryptographic session material. Incoming content is staged and validated before becoming visible as complete. Never trust a remote filename as a filesystem path.

### Storage
Storage repositories own MediaStore/app storage integration and safe filename handling. UI never constructs arbitrary destination paths.

## Compatibility strategy
Android 6–12 use legacy storage/location permission paths only where required by platform APIs. Android 13+ uses scoped media/nearby permissions. Features unavailable on old platform versions degrade individually; the whole application must not crash because a newer permission/API is absent.

## Quality gates
A release candidate is not accepted unless all of these pass:
- clean build
- unit tests
- debug lint
- release lint
- debug APK assembly
- signed release APK/AAB assembly when signing secrets are present
- install/launch smoke test
- Send → Receive transfer on two physical devices
- permission tests on Android 6/8/10/12/13/14/15/16 targets where available
- interrupted-transfer resume test
- rejection/cancellation test
- filenames with spaces, Unicode, duplicate names, and long names
- zero-byte and large-file tests
- orientation/process recreation does not corrupt a transfer

## Delivery phases
### Phase 1 — Foundation
Preserve the proven V3 transfer/security/storage implementation, establish V5 build identity, harden release configuration, and document architecture.

### Phase 2 — Design system and navigation
Replace ad-hoc programmatic styling with reusable product components/resources while preserving a recognizable OptiShare identity.

### Phase 3 — Permission and discovery state machine
Centralize runtime permission decisions and discovery readiness so Send/Receive never fail with an unexplained “no permission” state.

### Phase 4 — Transfer experience
Queue review, peer approval, rich progress, cancellation, resume/retry, completion summary, and background/foreground continuity.

### Phase 5 — Reliability and security
Protocol validation, storage hardening, adversarial input tests, lifecycle tests, performance work, and release artifact verification.

### Phase 6 — Store-grade polish
Accessibility, RTL, localization-ready resources, adaptive icon/splash, screenshots/listing, privacy/data-safety review, and release checklist.
