# OptiShare Production Release Gate

This document is a hard release gate for public Google Play builds. A release is **not** market-ready until every P0 item is verified on real devices and CI is green.

## P0 — Blockers

### Connection reliability
- [ ] Sender discovers a receiver without manual Wi-Fi settings on Samsung, Pixel, OnePlus, Xiaomi/Redmi, Oppo/Realme and Huawei/Honor test devices where Wi-Fi Direct is supported.
- [ ] Receiver remains in `Ready to receive` until a real peer connects; group creation alone never reports `Connected`.
- [ ] Search automatically retries Android `BUSY` conditions without requiring repeated user taps.
- [ ] A connection timeout cancels stale invitations and retries automatically.
- [ ] Stale Wi-Fi Direct groups are cleared before new sessions.
- [ ] QR fallback identifies the exact receiver and connects when normal discovery is slow.
- [ ] Connection state shown in the UI always matches actual transport state: Preparing → Searching/Ready → Connecting → Securing → Connected.
- [ ] No raw `EPERM`, numeric Wi-Fi Direct error, Java exception or stack trace is shown to users.

### Transfer engine
- [ ] 1, 10, 100 and 1000-file batches complete correctly.
- [ ] Empty files, Unicode/Arabic names, emoji names, long names and duplicate names are handled safely.
- [ ] 4 MB, 1 GB, 5 GB and >10 GB files complete where device storage permits.
- [ ] Files are SHA-256 verified before publishing to Downloads.
- [ ] Incomplete files remain private/partial and never appear as complete received files.
- [ ] Low-storage checks reject impossible transfers before wasting time/data.

### Auto Resume
- [ ] Forced disconnect at 10%, 33%, 79% and between files resumes from the last durable checkpoint.
- [ ] Completed files in a batch are not resent after reconnect.
- [ ] Screen-off/background transfer remains resumable.
- [ ] Activity/process recreation does not lose persistable source URI access when Android grants it.
- [ ] Receiver restart and sender recovery behavior is documented and tested.

### Security
- [ ] Ephemeral authenticated key exchange and AEAD encryption remain enabled for every transfer.
- [ ] Both devices must explicitly confirm matching security codes before file metadata/data is accepted.
- [ ] Incoming batch has independent Accept/Decline consent.
- [ ] Tampered encrypted frames fail closed.
- [ ] Filename/path sanitization prevents traversal and unsafe publication.
- [ ] No cloud relay, telemetry or advertising SDK is introduced without explicit privacy review.

### Android compatibility
- [ ] CI builds with compile/target API 36 and minSdk 21.
- [ ] Real/emulated smoke tests cover API 21, 23, 24, 28, 29, 30, 31, 33, 34, 35 and 36.
- [ ] Android 13+ Nearby Wi-Fi permission flow works.
- [ ] Android 12L and older location/location-service requirements are explained clearly.
- [ ] Android 10+ scoped storage / MediaStore path works.
- [ ] Android 5–9 public Downloads path and FileProvider sharing work.

## P1 — Product quality

### Send experience
- [ ] Photos, Videos and Music browse inside OptiShare.
- [ ] Video cards show real thumbnails and duration.
- [ ] Multi-select persists while moving between content categories.
- [ ] Selection summary shows item count and total size.
- [ ] Generic Files supports multi-select through SAF.
- [ ] Apps feature uses the smallest Play-policy-compatible package visibility; avoid `QUERY_ALL_PACKAGES` unless formally justified and declared.

### Receive experience
- [ ] Received Library lists completed files with thumbnail/icon, type, size and date.
- [ ] Open, Share and Delete actions work.
- [ ] Files are organized under `Download/OptiShare/{Photos,Videos,Music,Apps,Documents,Archives,Other}`.
- [ ] Completion screen includes a direct `Open received files` action.

### Transfer UX
- [ ] Clear connection status is visible at all times.
- [ ] Progress shows batch percentage, current file, transfer speed and useful resume/reconnect status.
- [ ] Cancel uses the transfer service stop action and cleans the intended session state.
- [ ] Interrupted sessions never silently disappear.
- [ ] Notifications return users to the current v3 product flow rather than a removed legacy UI.

### Navigation and identity
- [ ] Home includes Send, Receive, Received Library, History and Settings.
- [ ] Transfer History is persisted by the background engine, not only by an Activity.
- [ ] Device name is user-editable and recognizable on the other phone.
- [ ] No v1/v2 legacy Activity is reachable in the production build.

## P1 — Performance
- [ ] Gallery uses RecyclerView and asynchronous thumbnail loading; no full-library bitmap loading on the UI thread.
- [ ] Measure median discovery time, connection time and throughput on the OEM matrix.
- [ ] Memory remains stable with large media libraries and 1000-file selections.
- [ ] No ANR during hashing, manifest creation, transfer or received-library scans.
- [ ] Battery/thermal behavior is measured during sustained multi-GB transfer.

## P1 — Google Play
- [ ] Release AAB is signed and produced by CI.
- [ ] Play App Signing configuration is verified in Play Console.
- [ ] Data Safety form matches actual implementation.
- [ ] Public Privacy Policy URL is live and matches actual permissions/data handling.
- [ ] Permission declarations are submitted when required.
- [ ] Store listing avoids unverified speed/security superlatives.
- [ ] Screenshots and feature graphic show the actual production UI.
- [ ] Closed testing completed before production rollout.

## P2 — Competitive roadmap
- [ ] Optional same-LAN fallback when Wi-Fi Direct is unreliable (without making a router mandatory).
- [ ] Installed-app APK sharing with Play-policy review.
- [ ] Folder transfer.
- [ ] Search/albums in media picker.
- [ ] Light theme and full localization (English/Arabic first).
- [ ] Desktop companion (Windows first) for cross-platform differentiation.

## Release rule

A public version can be labeled **Production Candidate** only when:
1. CI unit tests + release lint + signed APK + AAB are green.
2. Every P0 item above is verified.
3. No open Critical/High security or data-loss defect remains.
4. Connection success rate and resume tests pass on the agreed OEM/device matrix.
