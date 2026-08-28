# OptiShare Windows Companion

The release EXE is a native self-contained .NET 8 Windows application. It does not launch
PowerShell, CMD, batch files, or a separate server window. Android discovery, sending, receiving,
clipboard transfer, progress, SHA-256 verification, and tray mode run inside the EXE.

For Windows → Android, open **Receive → Browser receive** on Android. The phone is discovered
automatically on the same Wi-Fi network; no IP address or browser URL needs to be pasted.

The Windows Companion supports local transfers in both directions:

- **Windows -> Android** uses Android's Browser / PC Receive mode.
- **Android -> Windows** uses automatic local PC discovery, a short-lived receiver token, Windows approval, and SHA-256 verification before the file is published.

## Start

Double-click `Start-OptiShare-Companion.bat`. Keep OptiShare running or minimized to the system tray. The Companion starts a local receiver automatically.

On first use, Windows Firewall may ask whether PowerShell can communicate on the network. Allow it on **Private networks** so Android can discover the PC.

## Android -> Windows

1. Keep the Windows Companion running.
2. In Android OptiShare, select files/folders/text and choose **Find receiving device**.
3. The Windows PC should appear automatically in Nearby devices as **Windows Companion**.
4. Tap **Send here**.
5. Approve the incoming batch on Windows.
6. Verified files are saved under `Downloads\OptiShare`, preserving safe relative folder paths when available.

The PC local route uses a process-scoped random token and SHA-256 verification. It is a local-network route and is intentionally not described as Android app-to-app E2E encryption.

## Windows -> Android

1. On Android choose **Receive -> Browser / PC** and start Browser Receive.
2. Copy the one-time `http://...` address shown by Android.
3. Paste it into the Windows Companion.
4. Add files or drag and drop them onto the Companion window.
5. Choose **Send selected files to Android**.
6. Approve each file on Android.

The Browser / PC route is local HTTP protected by a short-lived one-time token and explicit phone approval. Android-to-Android transfers continue to use OptiShare's authenticated encrypted transport.

## Tray mode

Minimizing or closing the Companion window hides it to the system tray rather than stopping the receiver. Use the tray icon to reopen OptiShare or choose **Exit** to stop the local receiver completely.

No account or cloud service is required.
