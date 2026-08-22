# OptiShare 3

OptiShare is a privacy-focused, local, resumable Android file-transfer application designed for nearby device-to-device sharing without a cloud account or Internet connection.

This branch (`update/v3.0-product`) is the product rebuild that turns the proven OptiShare 2 transfer core into a consumer-facing application suitable for formal release testing.

## Product promise

- **Local-first:** selected content moves between nearby participating devices.
- **Private by design:** no OptiShare account, no developer cloud relay, no advertising SDK and no tracking SDK in the current build.
- **Authenticated encryption:** ephemeral key exchange, security-code confirmation and AES-GCM protected transfer frames.
- **Resumable:** interrupted sessions keep durable confirmed progress and negotiate a safe resume point instead of restarting from byte zero.
- **Multi-file:** one logical session can contain many files and content types.
- **Organized:** verified received content is published under `Download/OptiShare` by category.
- **Android 5+:** `minSdk 21`; the current Google Play target is API 36.

## OptiShare 3 experience

### Home
The v3 launcher is `V3Activity`. It provides:
- Send
- Receive
- Received Library
- Transfer History
- Settings / device identity
- last-transfer status

The legacy v2 launcher/transfer Activity has been removed from the v3 branch.

### Send
`V3TransferActivity` supports building one batch from:
- Photos — browsed inside OptiShare
- Videos — browsed inside OptiShare with real thumbnails, duration and in-app preview
- Music — browsed inside OptiShare
- Apps — launchable installed apps with limited Android package visibility
- Documents & files — Android Storage Access Framework multi-select

Installed-app sharing intentionally avoids `QUERY_ALL_PACKAGES`. The manifest declares targeted visibility for launchable applications. A selected monolithic app is prepared as `.apk`; split installations are packaged locally as `.apks` for transfer.

### Receive
The receiver advertises an OptiShare DNS-SD Wi-Fi Direct service and creates the receiver-owned direct group. Group creation means **Ready**, not **Connected**. A connection is reported only after Android signals that a real peer is connected and connection information is valid.

### Received Library
`ReceivedActivity` presents completed received content with:
- image/video previews
- type/category
- size and date
- Open
- Share
- Delete

Legacy `file://` content is exposed safely through `FileProvider` when an external app is opened.

## Connection architecture

`P2pConnectionCoordinator` owns Wi-Fi Direct connection state independently from the UI.

Primary discovery uses **Wi-Fi Direct DNS-SD** so senders preferentially see OptiShare receivers instead of arbitrary Wi-Fi Direct devices. On OEMs with incomplete DNS-SD support, OptiShare automatically falls back to generic Wi-Fi Direct peer discovery after a grace period.

Connection states exposed to the UI include:

```text
Preparing
  → Ready to receive / Searching
  → Devices found
  → Connecting
  → Securing connection
  → Connected
  → Retrying automatically (when recoverable)
  → Failed (when user action is required)
```

Reliability behavior includes:
- stale connection/group cleanup before a new session
- repeated service discovery
- compatibility peer-search fallback
- connection timeout
- stale invitation cancellation
- bounded automatic reconnect attempts
- QR pairing fallback for an exact receiver

## Transfer protocol

The foreground `TransferService` owns transfer work independently from the visible Activity.

Each batch has a stable session ID. Per-file metadata includes a stable file ID, display name, MIME type, category, size and SHA-256 digest.

Application frames after key exchange are authenticated and encrypted. The current security layer uses ephemeral ECDH, HKDF-SHA256 and AES-256-GCM. Both devices must explicitly confirm a matching security code. The receiving device separately accepts or declines the incoming batch.

The receiver persists durable byte offsets at checkpoint boundaries. On reconnect, both sides negotiate the lowest safe checkpoint and retransmit only data after that point.

A received file remains partial/private until its expected size and SHA-256 digest are verified. Only then is it published as a completed received file.

## Storage

Verified received content is organized under:

```text
Download/OptiShare/
├── Photos/
├── Videos/
├── Music/
├── Apps/
├── Documents/
├── Archives/
└── Other/
```

Android 10+ uses scoped-storage/MediaStore behavior. Android 5–9 use the legacy public Downloads path where the platform requires it.

Prepared app packages used by an active/resumable outgoing transfer are kept in application-private storage rather than volatile cache storage. Old prepared packages are periodically cleaned.

## Architecture

```text
com.kenan.optishare
├── connection/   Wi-Fi Direct DNS-SD, discovery, retry and connection state
├── device/       local user-facing device identity
├── history/      local transfer history
├── model/        transfer items and categories
├── protocol/     framing, batch manifest, acknowledgements and resume state
├── security/     ephemeral session cryptography
├── storage/      classification, media repository, received library and publishing
├── transfer/     foreground service, sender/receiver engine and recovery
└── ui/           media RecyclerView adapters
```

Major product Activities:

```text
V3Activity              Home dashboard
V3TransferActivity      Send / Receive / discovery / transfer state
MediaPickerActivity     Photos / Videos / Music multi-select
MediaPreviewActivity    In-app image/video preview
AppPickerActivity       Installed app packaging and selection
ReceivedActivity        Received Library
HistoryActivity         Transfer History
SettingsActivity        Device identity and privacy/settings
ApprovalActivity        Security-code and incoming-batch approval
```

## Build

Requirements:
- JDK 17
- Android SDK 36
- Gradle configuration from the repository workflow

Local quality build:

```bash
gradle --no-daemon clean testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleDebug
```

Google Play release artifacts:

```bash
gradle --no-daemon assembleRelease bundleRelease
```

GitHub Actions produces:
- debug APK
- signed release APK when signing secrets are available
- Google Play `.aab`
- release SHA-256 checksums
- unit-test reports
- lint reports

The release keystore is never committed. CI reconstructs it only from protected GitHub Actions secrets.

## Public release gate

OptiShare 3 is a **production candidate under active verification**, not a public-release claim.

A public release is blocked until the applicable P0/P1 criteria in `PRODUCTION_RELEASE_GATE.md` are verified, including real-device connection and forced-disconnect resume tests.

Also see:
- `PRODUCTION_RELEASE_GATE.md`
- `PLAY_STORE_RELEASE_CHECKLIST.md`
- `PRIVACY_POLICY.md`
- `DATA_SAFETY_DRAFT.md`
- `SECURITY.md`

## Google Play package visibility

The Apps picker uses Android's limited package-visibility mechanism for launchable applications. Broad `QUERY_ALL_PACKAGES` is intentionally not requested in the current implementation. Any future decision to broaden app visibility must receive a new privacy and Google Play policy review before release.

## Credits

Designed & developed by **Kenan Alhennawi**.
