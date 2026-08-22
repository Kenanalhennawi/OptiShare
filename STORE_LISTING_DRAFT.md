# OptiShare — Google Play Store Listing Draft

## App name
OptiShare

## Short description
Private, fast local file sharing with multi-file transfer and automatic resume.

## Full description
OptiShare is a local device-to-device file transfer app built for speed, privacy and reliability.

Send photos, videos, music, APK files, documents and other files directly to a nearby Android device without uploading your files to a developer-operated cloud service.

### Highlights
- Multi-file transfers in one batch
- Automatic resume after an interrupted connection
- Private local transfer — no account required
- Encrypted OptiShare application protocol
- In-app photo and video selection
- QR-assisted pairing
- Visible Search, Connecting, Connected, Reconnecting and Completed states
- Received files organized by type under Download/OptiShare
- File integrity verification before completed files are published
- Background transfer service for long transfers
- Transfer history stored locally

### Organized received files
OptiShare automatically separates received content into folders such as Photos, Videos, Music, Apps, Documents, Archives and Other.

### Privacy-focused design
The current OptiShare build does not require an account, does not include advertising, and does not include third-party analytics/tracking SDKs. Selected files are intended to travel directly between the nearby devices participating in the transfer.

### Automatic resume
If the transfer socket is interrupted, confirmed progress is preserved and OptiShare can continue from a verified chunk instead of restarting the entire file. Completed files within the same batch are not retransmitted.

## Important wording gate
Do not publish claims such as “fully MITM-proof” or “military-grade security.” Before launch, peer authentication/security-code confirmation must be completed and independently reviewed. Use factual descriptions of the implemented encryption instead.

## Suggested screenshot sequence
1. Home — Send / Receive and content categories
2. In-app Photos multi-select
3. Nearby devices discovery
4. Secure incoming batch request
5. Transfer progress with speed and resume state
6. Completed transfer and categorized save location
7. Recent transfer history

## Suggested feature graphic message
FAST • PRIVATE • RESUMABLE
Local file sharing without cloud upload

## Store metadata still required
- Public privacy-policy HTTPS URL
- Support email
- Developer website
- Final screenshots from production UI
- Final feature graphic
- Content rating
- Target audience declaration
- Data Safety form
- Permission declarations matching the production AAB
