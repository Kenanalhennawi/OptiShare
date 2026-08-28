# OptiShare Windows Companion

The Windows Companion sends files from a Windows PC to OptiShare's **Browser / PC Receive** mode over the same local network.

## Use

1. On Android, open OptiShare and choose **Receive → Browser / PC**.
2. Start Browser Receive and copy the one-time `http://...` address shown by the phone.
3. On Windows, double-click `Start-OptiShare-Companion.bat`.
4. Paste the address, add files, and choose **Send selected files**.
5. Approve each incoming file on the phone.

The browser/PC route is local HTTP protected by a short-lived one-time token and explicit phone approval. It is intentionally not described as OptiShare's app-to-app end-to-end encrypted mode. Android-to-Android transfers continue to use the encrypted OptiShare transport.

No installation or account is required. Windows PowerShell and .NET Framework components included with supported Windows versions are used by the launcher.
