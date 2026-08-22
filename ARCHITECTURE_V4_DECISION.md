# OptiShare 4 — Market Architecture Decision

## Executive decision

OptiShare 4 is a clean product rebuild. OptiShare 3 remains a transport/reference baseline and is not the architectural base for the public-market application.

## Product goal

A premium Android local-sharing product which is easy enough for non-technical users, reliable across OEMs, resumable, privacy-first, and measurable against Quick Share / SHAREit class expectations.

## Core technical decisions

### UI
- Kotlin
- Jetpack Compose
- Material 3 primitives with a custom OptiShare design system
- State-driven navigation; no giant activity-driven UI
- RTL and accessibility designed in, not patched later

### Architecture
- Clean Architecture
- Presentation -> Domain -> Data/Transport boundaries
- Unidirectional UI state
- Coroutines + Flow
- Lifecycle-aware state holders
- Dependency inversion around transport and storage

### Transport strategy
A TransportOrchestrator ranks available transports at runtime.

1. Nearby Connections — primary on devices with compatible Google Play services.
   - offline discovery/advertising
   - endpoint verification
   - platform-managed radio selection/upgrades
   - suitable for fast first-contact UX
2. Wi-Fi Aware — performance path on supported Android 8+ devices.
   - service publish/subscribe
   - infrastructure-independent peer data path
3. Wi-Fi Direct — universal Android fallback, including older Android releases.
   - DNS-SD OptiShare service discovery first
   - peer discovery compatibility fallback
4. QR — identity/bootstrap fallback only; never file transport.

No single OEM-specific radio behavior is allowed to be a single point of failure.

### Transfer protocol
- Preserve resumable logical-session semantics from the proven engine
- stable session ID and file IDs
- chunked streaming
- explicit durable checkpoint acknowledgement
- final SHA-256 verification
- no final publication before integrity verification
- versioned wire protocol and capability negotiation

### Security
- transport-layer security is not treated as sufficient by itself
- application session authentication and identity verification remain mandatory
- ephemeral key agreement
- authenticated encryption
- six-digit user-verifiable pairing code
- incoming batch approval
- bounded framing / metadata validation

### Storage
- MediaStore/scoped storage on modern Android
- legacy guarded implementation for old supported devices
- Received Library backed by indexed received metadata
- app-prepared payloads retained until terminal session state

### Quality gates
A release is blocked unless:
- debug and release compile
- unit tests pass
- Android lint passes
- signed release APK and AAB build
- transport instrumentation tests pass where automatable
- physical-device connection matrix passes
- resume matrix passes
- no Critical/High security finding remains open
- no P0/P1 product defect remains open

## GitHub's role

GitHub is source control, code review and CI/CD only. It is not the development environment.

Normal engineering workflow:
1. Android Studio local development
2. local test/lint/benchmark
3. emulator/device test
4. commit
5. GitHub CI independent verification
6. internal APK/AAB distribution
7. closed Play test
8. production rollout

## Compatibility

Target market build: Android 5.0+ where technically supportable by the fallback stack. Modern transports are capability-gated at runtime.

## Non-goals

- no cloud relay required for core file transfer
- no mandatory account
- no advertising/analytics SDK introduced merely for monetization
- no QUERY_ALL_PACKAGES unless a future reviewed Play-policy decision explicitly justifies it

## Success metrics

We will measure rather than claim:
- median discovery time
- P95 connection time
- successful first-attempt connection rate by OEM/API
- sustained MB/s by payload size
- reconnect/resume success rate
- crash-free sessions
- ANR rate
- transfer integrity failures
- battery cost per GB
