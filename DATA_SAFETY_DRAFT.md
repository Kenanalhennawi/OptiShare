# OptiShare 3 — Google Play Data Safety Draft

This document is an engineering release-control draft. Play Console answers must be verified again against the exact production AAB and every SDK included in that build.

## Current product design
- No OptiShare account or developer login is required.
- No advertising SDK is included.
- No third-party analytics/tracking SDK is included.
- No developer-operated cloud relay is required for file transfer.
- Selected content transfers locally between the participating devices.
- Transfer history, device display name, prepared app packages, partial files and resume offsets remain local to the device.
- Installed-app sharing uses limited package visibility for launchable apps via manifest `<queries>`; the current design does **not** request `QUERY_ALL_PACKAGES`.

## Data processed locally

### Files and documents
Purpose: core app functionality — user-selected local transfer.
Handling: read locally and sent only to the peer/session selected by the user. Not intentionally uploaded to the developer.

### Photos, videos and audio
Purpose: in-app media browser, thumbnail/preview and local transfer.
Handling: queried from Android MediaStore after the appropriate permission and transferred only after explicit user selection.

### Installed application information
Purpose: optional Apps transfer category.
Data used: launchable app label, package name, icon and local APK/split APK source files required to prepare the user-selected app package.
Handling: processed locally. OptiShare uses targeted launcher-app visibility rather than broad package inventory permission in the current design. Selected apps can be prepared as `.apk` or `.apks` files for local transfer.

### Nearby device and connection information
Purpose: OptiShare receiver discovery, direct-link establishment, connection recovery and local history.
Examples: Wi-Fi Direct device information, advertised OptiShare DNS-SD service information, local session identifiers, peer display name.
Handling: local transfer functionality; not intentionally collected by the developer.

### Camera input
Purpose: optional QR pairing fallback.
Handling: used locally by the QR scanner. Camera frames are not intentionally uploaded to a developer service.

### App activity stored locally
Purpose: user-facing history and transfer recovery.
Examples: transfer direction, peer display name, file count, total bytes, success/interruption state, session/resume offsets.
Handling: stored in application-private preferences/files unless the user clears app data.

## Security processing
Application transfer frames are designed to use ephemeral ECDH key exchange, HKDF-SHA256 key derivation and AES-GCM authenticated encryption. Both phones require a matching security-code confirmation before transfer approval. Completed files are SHA-256 verified before final publication to the received-files location.

## Expected Play Console position for the no-cloud build
If the exact production binary continues to contain no telemetry, ads, remote crash reporting, developer backend or other data collection path, data that is processed only on-device for core functionality generally should be evaluated under Google Play's local-processing rules rather than assumed to be developer collection. The final Play form must follow the definitions shown in Play Console at submission time.

## Permission / policy review before submission
- Verify broad Photos/Videos/Audio access is still required by the in-app media-browser experience and complete any Play declaration required at submission time.
- Verify package visibility remains limited. Do not add `QUERY_ALL_PACKAGES` without a documented core-functionality justification and Play permission declaration.
- If APK installation is added later using `REQUEST_INSTALL_PACKAGES`, update the prominent disclosure, privacy policy and Play permission declaration before shipping it.
- Verify camera usage remains optional QR pairing only.
- Verify Nearby Wi-Fi / legacy location permissions are used only for nearby connection functionality.
- Verify foreground-service/notification usage is limited to active or recoverable transfers.

## Binary verification before every production release
- Inspect the dependency tree for analytics, advertising, telemetry and remote logging SDKs.
- Search the production source/resources for remote endpoints.
- Verify no debug telemetry or developer test endpoint is included.
- Verify Data Safety answers match the shipped feature set, including Apps sharing.
- Verify Privacy Policy, support URL and developer contact are live and current.
