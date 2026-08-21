# OptiShare 2.0 — Clean Rebuild Plan

## Product goal
Build a SHAREit-class Android file sharing app with a clearer UX, true multi-file/batch transfer, category-aware browsing, visible connection state, and public Downloads storage.

## Compatibility
- Android 5.0+ (API 21) through the latest Android release.
- Runtime permission handling split by Android version.
- Wi-Fi Direct as the primary peer-to-peer transport when available.
- QR pairing as an optional pairing accelerator, not the data channel.
- Graceful capability checks and explicit user guidance instead of raw EPERM errors.

## User experience
### Home
- Large Send and Receive actions.
- Category shortcuts: Photos, Videos, Music, Apps/APKs, Documents, Other.
- Recent transfers and received files.
- Device name/avatar and connection indicator.

### Send flow
1. Choose one or multiple categories.
2. Multi-select unlimited files across multiple picker sessions.
3. Selection tray with count and total bytes.
4. Search screen with animated radar and nearby receiver cards.
5. QR pairing alternative.
6. Explicit states: Searching > Found > Connecting > Connected > Waiting for acceptance > Sending > Completed.
7. Batch progress plus per-file progress, speed and ETA.

### Receive flow
1. Receiver mode creates a session.
2. Large visible device identity plus optional QR.
3. Incoming batch sheet shows sender, file count, total size and category breakdown.
4. Accept / Decline.
5. Transfer screen shows current file, batch progress, speed and ETA.
6. Completion screen exposes the exact save location and Open Folder action.

## Storage organization
Received content is stored under Download/OptiShare with category folders:
- Download/OptiShare/Photos
- Download/OptiShare/Videos
- Download/OptiShare/Music
- Download/OptiShare/Apps
- Download/OptiShare/Documents
- Download/OptiShare/Other

Android 10+ uses MediaStore/Scoped Storage. Android 5-9 uses public Downloads with legacy storage permission when required.

## Transfer protocol v2
- One connection per session, not one connection per file.
- Batch manifest sent once: protocol version, session id, sender name, file count, total bytes.
- Per-file metadata: id, display name, MIME type, category, size, SHA-256.
- Stream files sequentially across the same socket.
- Sender waits for batch acceptance once, not per file.
- File completion acknowledgement and final batch acknowledgement.
- SHA-256 verification on receiver.
- Resume protocol is a later phase.

## Architecture
- ui/home
- ui/send
- ui/receive
- ui/transfer
- discovery
- transport
- protocol
- storage
- model
- util

Transport, protocol, file classification, and UI state must be separated. No single 50k-line Activity.

## Release/CI cleanup
- One active workflow only: build-optishare.yml.
- Remove old v0.x workflows from the clean branch.
- Remove prototype optical/audio/legacy activities from the clean branch.
- Version starts at 2.0.0 for the clean architecture.

## Delivery phases
1. Clean repository + API 21 compatibility baseline.
2. New polished navigation/UI shell and category browser.
3. True multi-file selection tray and category classifier.
4. Receiver session + discovery state machine.
5. Wi-Fi Direct transport abstraction and deterministic connection lifecycle.
6. Batch protocol v2 + accept/decline + multi-file transfer.
7. Category-aware public storage.
8. Transfer history, recent files and open-folder actions.
9. Device matrix testing Android 5/7/9/10/12/13/14/15+.
10. Performance tuning, crash handling and signed release.
