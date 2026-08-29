# Google Play Data safety answers

This document describes the current OptiShare 2.2 behavior. Recheck each answer against the exact wording shown in Play Console before submission.

## Recommended answers

| Play Console topic | Current answer | Reason |
|---|---|---|
| Does the app collect or share required user data with the developer or third parties? | No | There is no developer backend, analytics, advertising, or tracking SDK. User-selected peer-to-peer transfers are initiated by the user and go to the device they select. |
| Is all user data encrypted in transit? | Yes, for the Android-only Play release | Every transfer route exposed in the Android-only release uses authenticated application-level encryption. Experimental PC/browser entry points are disabled in the shipping UI and are not started automatically. Re-evaluate this answer before enabling another platform route. |
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
- Experimental Windows/browser code is feature-disabled for the Android-only Play release and has no user-accessible entry point.
- Revert to the conservative **not all data is encrypted in transit** answer if an unencrypted compatibility route is exposed again.

## Permissions declaration notes

- Nearby Wi-Fi devices / location: local peer discovery; behavior depends on Android version.
- Notifications / foreground service: transfer status and pause/resume controls.
- File/media access: only content selected or received by the user, using platform storage APIs where available.
- Internet/network state: local-network discovery and direct transfer; no developer cloud.
- Camera: declare only if the shipping manifest includes a user-facing QR scanning feature.
