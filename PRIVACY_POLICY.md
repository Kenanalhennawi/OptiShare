# OptiShare Privacy Policy

_Last updated: 22 August 2026_

OptiShare is designed for direct local file transfer between nearby devices. The application does not require an OptiShare account and does not require transferred files to be uploaded to a developer-operated cloud service.

## Data processed on the device
OptiShare may access user-selected files, photos, videos, audio, launchable installed applications, nearby Wi-Fi devices, camera input for optional QR pairing, and local network state only when required for features the user chooses to use. This information is processed locally to browse/select content, discover a receiving device, establish and recover a direct connection, preview selected media, and transfer the selected content.

## File and media contents
Files and media selected for transfer are sent directly to the participating receiving device over the local transfer connection. OptiShare does not intentionally upload transferred file content to a developer-operated server.

## Installed application sharing
When the user opens the Apps category, OptiShare may display launchable applications visible through Android's limited package-visibility mechanism. The current production design does not request broad `QUERY_ALL_PACKAGES` visibility. For an app explicitly selected by the user, OptiShare may read the local APK and split APK files needed to prepare an `.apk` or `.apks` transfer package. Prepared packages are stored locally and may be retained temporarily to support interrupted-transfer resume.

## Nearby discovery
OptiShare uses Android nearby/Wi-Fi Direct capabilities. Receivers can advertise an OptiShare-local DNS-SD service and senders can search for nearby OptiShare receivers. A compatibility peer-search fallback may be used on devices whose Wi-Fi Direct service-discovery implementation is limited. Nearby device information is used for the local connection and is not intentionally sent to the developer.

## Encryption and integrity
OptiShare uses an application transfer protocol designed around ephemeral elliptic-curve key exchange, HKDF-SHA256 key derivation and AES-GCM authenticated encryption. Both devices are required to confirm a matching security code before the transfer is approved. Received files are SHA-256 verified before being published as completed files.

## Accounts, advertising, analytics and tracking
The current OptiShare 3 application does not require user registration and does not include advertising or third-party analytics/tracking SDKs. If this changes in a future release, this policy and the Google Play Data Safety declaration must be updated before that release is distributed.

## Permissions
- Nearby Wi-Fi devices: nearby OptiShare discovery and Wi-Fi Direct connection on newer Android versions.
- Location / location service on older Android versions: required by older Android Wi-Fi Direct discovery APIs. OptiShare does not use this permission to upload the user's location.
- Photos and videos: in-app gallery, thumbnails/previews and user-selected transfer.
- Audio: in-app music browser and user-selected transfer.
- Files/storage on older Android versions: selecting, receiving and organizing content where legacy Android storage APIs require it.
- Camera: optional QR pairing fallback.
- Notifications / foreground service: keeping an active or recoverable transfer visible and running while the application is not in the foreground.

## Local history and resume state
OptiShare may store the user-defined device display name, recent transfer history, source URI references, partial received data, prepared app packages, session metadata, and confirmed resume offsets locally on the device. This local state is used to show history and allow interrupted transfers to continue rather than restarting from zero.

## Received files
Completed received content is organized locally under the OptiShare received-files location, normally within `Download/OptiShare` and category subfolders such as Photos, Videos, Music, Apps, Documents, Archives and Other. The application can provide local Open, Share and Delete actions for completed received files.

## Data sharing
OptiShare does not sell personal data. User-selected content is shared with the participating receiving peer only as part of the transfer operation initiated or accepted by the user.

## Security
No software can guarantee absolute security. OptiShare is designed to reduce risk with authenticated encryption, per-session ephemeral key material, explicit peer/security-code confirmation, bounded protocol framing, integrity verification, filename/path validation, resumable durable checkpoints and a local-transfer architecture.

## Children
OptiShare is a general-purpose file transfer utility and is not specifically directed at children.

## Changes
This policy may be updated when application behavior, permissions, SDKs, distribution requirements or data practices change. The Google Play listing should always link to the currently published version of this policy.

## Contact
Before public Google Play release, replace this section with the official developer support email and the public privacy-policy website URL used in Google Play Console.
