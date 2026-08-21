# OptiShare 2

OptiShare is a private, local, resumable Android file-transfer application designed for fast device-to-device sharing without a cloud account or Internet connection.

## Product principles

- **Local-first:** files move directly between nearby devices.
- **Private:** no account, no cloud relay, no ads, and no tracking SDKs in the current build.
- **Resumable:** interrupted batches keep confirmed progress and resume instead of restarting from byte zero.
- **Organized:** verified received files are published under `Download/OptiShare` by category.
- **Multi-file:** one encrypted logical session can contain many files.
- **Android 5+:** `minSdk 21`, while the current Play target is API 36.

## Current transport

Wi-Fi Direct is the primary Android-to-Android transport. QR is a pairing accelerator only; file data never travels through the QR code.

Typical flow:

1. Receiver opens **Receive**.
2. Sender selects photos, videos or files.
3. Sender discovers the receiver or scans the receiver QR.
4. Android establishes the Wi-Fi Direct link.
5. OptiShare opens an encrypted application session over the direct link.
6. Receiver accepts or declines the incoming batch.
7. Files stream sequentially with chunk acknowledgements, resume checkpoints and final SHA-256 verification.

## Transfer protocol v2

Each batch has a stable session ID. Per-file metadata includes a stable file ID, display name, MIME type, category, size and SHA-256 digest.

Application frames after the key exchange are authenticated and encrypted. The current security layer uses ephemeral ECDH, HKDF-SHA256 and AES-256-GCM. The receiver persists safe confirmed byte offsets. On reconnect, both sides negotiate the lowest safe checkpoint and retransmit only data after that point.

A received file is first written as partial session state. It is published to public Downloads only after its full size and SHA-256 digest are verified.

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

Android 10+ uses scoped-storage/MediaStore behavior. Older Android versions use the legacy public Downloads path only where the platform requires it.

## Architecture

The clean branch separates major responsibilities:

```text
com.kenan.optishare
├── device/       device identity
├── history/      local transfer history
├── model/        transfer models
├── protocol/     framing, manifest, resume state
├── security/     ephemeral session cryptography
├── storage/      classification, media browser, verified publishing
├── transfer/     foreground service, sender/receiver engine
└── ui/           reusable UI adapters
```

`V2Activity` currently coordinates navigation and Wi-Fi Direct discovery. Further extraction of discovery/transport state is tracked in the release checklist.

## Build

Requirements:

- JDK 17
- Android SDK 36
- Gradle configured by the repository workflow

Local debug build:

```bash
gradle --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

Google Play release artifact:

```bash
gradle --no-daemon bundleRelease
```

GitHub Actions builds:

- debug APK
- signed release APK when signing secrets are available
- Google Play `.aab`
- SHA-256 release checksums

The release keystore is never committed to this repository. CI reconstructs it only from protected GitHub Actions secrets.

## Release status

OptiShare 2 is still a **production candidate under active hardening**, not a finished public Play release. See:

- `PLAY_STORE_RELEASE_CHECKLIST.md`
- `OPTISHARE_2_ROADMAP.md`
- `PRIVACY_POLICY.md`
- `DATA_SAFETY_DRAFT.md`

A production release is blocked until applicable compatibility, security, reliability and Google Play policy gates are completed.

## Privacy

The intended public release does not require an account and does not upload transferred files to an OptiShare server. Android permissions are requested only for the local features that need them, such as nearby-device discovery or user-selected media access.

## Credits

Designed & developed by **Kenan Alhennawi**.
