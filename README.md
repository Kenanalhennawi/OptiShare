# OptiShare v0.1

Offline Android optical file transfer prototype. It intentionally declares no INTERNET, Bluetooth, Wi-Fi, NFC, or nearby-device permissions.

## Protocol
- Metadata frame: `OT1|session|M|total|filename_b64url|size|sha256`
- Data frame: `OT1|session|D|index|crc32|data_b64url`
- Chunk size: 700 bytes
- Sender loops all frames every 260 ms
- Receiver deduplicates by chunk index, validates CRC32 per frame and SHA-256 for the final file
- Files are selected/saved through Android Storage Access Framework

## Prototype limits
- 20 MB in-memory cap
- QR optical throughput is intentionally conservative for camera reliability
- Receiver uses legacy Android Camera API to keep dependencies minimal

## Build
Open in Android Studio, install Android SDK 35, then Build > Build APK(s), or run:
`./gradlew assembleDebug`

Dependency: ZXing Core 3.5.3.
