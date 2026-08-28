# OptiShare 2.2 Release Candidate

OptiShare 2.2 focuses on fast, private and resilient local transfer.

Highlights include encrypted resumable app-to-app transfers, live progress/speed/ETA, transfer history, trusted devices, SmartRoute, pause/resume, multi-file queueing, secure folder structure transfer, text/clipboard sharing, QR and same-Wi-Fi fallbacks, Local-Only Hotspot fallback on supported Android versions, Browser/PC Receive with short-lived token and phone approval, and a Windows Companion for Browser/PC Receive.

The Browser/PC route is intentionally identified as local HTTP rather than app-to-app end-to-end encryption. Android app-to-app sessions continue to use OptiShare's authenticated encrypted transport.

Release artifacts are produced only by `.github/workflows/build-v22.yml`, which runs tests/lint, validates the release keystore, builds APK/AAB, and verifies the APK signer certificate before publishing artifacts.
