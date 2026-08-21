# OptiShare 2.0 — Clean Rebuild Plan

## Product goal
Build a SHAREit-class Android file sharing app with a clearer UX, true multi-file/batch transfer, category-aware browsing, visible connection state, public Downloads storage, and automatic resumable transfers.

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
6. Explicit states: Searching > Found > Connecting > Connected > Waiting for acceptance > Sending > Reconnecting > Resuming > Completed.
7. Batch progress plus per-file progress, speed and ETA.
8. If the direct link drops, automatically reconnect and continue from the last confirmed byte instead of restarting the batch.

### Receive flow
1. Receiver mode creates a session.
2. Large visible device identity plus optional QR.
3. Incoming batch sheet shows sender, file count, total size and category breakdown.
4. Accept / Decline.
5. Transfer screen shows current file, batch progress, speed and ETA.
6. During interruptions, keep partial files and display Reconnecting/Resuming rather than failing the whole transfer.
7. Completion screen exposes the exact save location and Open Folder action.

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
- One logical session per batch, not one session per file.
- Batch manifest sent once: protocol version, session id, sender name, file count, total bytes.
- Per-file metadata: stable file id, display name, MIME type, category, size, SHA-256.
- Stream files sequentially across the same socket while the connection is healthy.
- Sender waits for batch acceptance once, not per file.
- Receiver confirms durable byte offsets at chunk boundaries.
- Confirmed offsets are persisted locally with the session id.
- On reconnect, sender and receiver exchange resume state and restart from the lowest mutually confirmed offset.
- Resume offsets are aligned to a safe chunk boundary so a partially written chunk is retransmitted instead of trusted.
- Completed files are never retransmitted during a resumed batch.
- File completion acknowledgement and final batch acknowledgement.
- SHA-256 verification on receiver before marking a file complete.
- Partial state is cleared only after final verified completion or explicit user cancellation.

## Reliability rules
- Connection loss must not discard already verified progress.
- App process restart must preserve resumable session state.
- Socket timeout, Wi-Fi Direct BUSY, peer disappearance, and temporary permission/state failures are recoverable states where possible.
- Automatic retry uses bounded backoff; UI always shows what state the app is in.
- No raw exceptions such as EPERM should be shown directly to users.
- Corrupt or mismatched chunks/files are retransmitted and never silently accepted.
- CI must run protocol unit tests before producing APK artifacts.

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

Transport, protocol, file classification, resume state, and UI state must be separated. No single 50k-line Activity.

## Release/CI cleanup
- One active workflow only: build-optishare.yml on update/v2.0-clean.
- Legacy workflows are removed from main and legacy branches; historical GitHub Action run records may remain visible as history.
- Remove prototype optical/audio/legacy activities from the clean branch.
- Version starts at 2.0.0 for the clean architecture.

## Delivery phases
1. Clean repository + API 21 compatibility baseline.
2. New polished navigation/UI shell and category browser.
3. True multi-file selection tray and category classifier.
4. Receiver session + discovery state machine.
5. Wi-Fi Direct transport abstraction and deterministic connection lifecycle.
6. Batch protocol v2 + accept/decline + true multi-file transfer.
7. Persistent auto-resume + reconnect state machine + partial-file recovery.
8. Category-aware public storage.
9. Transfer history, recent files and open-folder actions.
10. Device matrix testing Android 5/7/9/10/12/13/14/15+ including forced disconnect/reconnect tests.
11. Performance tuning, crash handling and signed release.
