# Google Play Data safety answers

This document describes the current OptiShare 2.2 behavior. Recheck each answer against the exact wording shown in Play Console before submission.

## Recommended answers

| Play Console topic | Current answer | Reason |
|---|---|---|
| Does the app collect or share required user data with the developer or third parties? | No | There is no developer backend, analytics, advertising, or tracking SDK. User-selected peer-to-peer transfers are initiated by the user and go to the device they select. |
| Is all user data encrypted in transit? | No, for the current release | Android-to-Android sessions use application-level encryption, but the optional Browser Receive compatibility route uses a token-protected local HTTP session. Do not claim universal encryption until that route is upgraded or removed from the Play build. |
| Can users request deletion of collected data? | Not applicable to developer-held data | The developer does not hold user data or accounts. Users can delete received files, local history/trusted-device records, or clear app storage. |
| Does the app contain ads? | No | No advertising SDK or ad content. |
| Is an account required? | No | Transfers and trusted devices are local. |
| Is the app primarily directed to children? | No | General-purpose file-transfer utility. |

## Data handled locally or in a user-directed transfer

- User-selected files, clipboard text, links, file names, MIME types, and sizes.
- Nearby device name, local IP address, capability information, and trust fingerprint.
- Local transfer history, resume checkpoints, progress, and integrity hashes.

The receiving peer necessarily receives the content and metadata selected for transfer. None of this is transmitted to OptiShare Labs or used for advertising, analytics, profiling, or marketing.

## Security-form notes

- Android-to-Android: authenticated key agreement, application-level encrypted frames, integrity verification.
- Android-to-Windows native companion: local direct connection; verify the release behavior before selecting universal encryption.
- Browser Receive: local HTTP with an unguessable session path/token; it prevents casual unsolicited upload but is not equivalent to encrypted transport.
- Therefore the conservative and accurate Play answer is currently **not all data is encrypted in transit**.

## Permissions declaration notes

- Nearby Wi-Fi devices / location: local peer discovery; behavior depends on Android version.
- Notifications / foreground service: transfer status and pause/resume controls.
- File/media access: only content selected or received by the user, using platform storage APIs where available.
- Internet/network state: local-network discovery and direct transfer; no developer cloud.
- Camera: declare only if the shipping manifest includes a user-facing QR scanning feature.
