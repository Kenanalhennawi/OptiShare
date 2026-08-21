# OptiShare 2 — Google Play Data Safety Draft

This document is an engineering draft. The Play Console answers must be re-verified against the exact production AAB and every SDK included in that build.

## Current design assumptions
- No developer account/login is required.
- No advertising SDK is included.
- No third-party analytics or tracking SDK is included.
- Selected files transfer directly between nearby devices over a local connection.
- No developer-operated cloud server is required for transfer.
- Device name, transfer history, partial transfer data, and resume offsets are stored locally on the device.

## Data categories processed locally
### Files and documents
Purpose: app functionality — user-selected local transfer.
Handling: read locally and sent to the peer selected by the user. Not intentionally uploaded to the developer.

### Photos and videos
Purpose: in-app media browser and local transfer.
Handling: read locally after permission and sent only when selected by the user.

### Device / connection information
Purpose: nearby discovery, connection establishment, transfer reliability and local history.
Examples: local device display name, nearby Wi-Fi Direct peer information, local transfer session identifier.
Handling: local app functionality; not intended for developer collection.

### App activity stored locally
Purpose: transfer history and resume state.
Examples: direction, peer display name, progress offsets, completion state.
Handling: stored locally in app preferences/private files.

## Encryption
Application transfer frames are designed to use ephemeral ECDH key exchange, HKDF-SHA256 key derivation and AES-GCM authenticated encryption. Files are SHA-256 verified before final publication on the receiving device.

## Expected Play Console position for the current no-cloud build
If the production binary contains no telemetry, ads, remote logging, crash collection or developer backend, local-only processing generally should not be declared as developer collection merely because the app accesses the data to provide its core feature. However, the final form must be answered according to Google Play's current definitions and the exact shipped behavior.

## Re-check before submission
- Verify the dependency tree contains no analytics/ads SDKs.
- Verify there are no remote endpoints in application code/resources.
- Verify crash reporting is absent or, if later added, update this document and the Play form.
- Verify broad Photos/Videos permission usage matches the submitted Play permission declaration.
- Verify any future installed-app browser/package visibility behavior is disclosed as required.
- Verify support/privacy URLs and contact information are final.
