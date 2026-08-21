# OptiShare 2 Security Model

## Security goals
OptiShare is designed to transfer user-selected files directly between nearby devices without requiring a developer-operated cloud relay. The application protocol must protect file confidentiality and integrity even though the underlying local transport is not itself treated as trusted.

## Implemented controls
- Ephemeral per-session elliptic-curve Diffie-Hellman key exchange (`secp256r1`) for Android 5+ compatibility.
- HKDF-SHA256 key derivation.
- AES-256-GCM authenticated encryption for application protocol frames.
- Fresh random 96-bit GCM IV for each encrypted frame.
- Frame type included as authenticated additional data.
- Bounded frame sizes, manifest entry counts, checksum lengths and offsets.
- SHA-256 hash for every transferred file.
- Receiver publishes a file to Downloads only after complete-size and SHA-256 verification.
- Partial files stay in private app storage with `.optishare-part` semantics and are not exposed as completed downloads.
- Durable chunk acknowledgement after receiver persistence.
- Completed-file and completed-batch acknowledgements.
- Resume negotiation uses confirmed offsets and verified-file markers.
- Filenames are sanitized before local publication.
- Application components are not exported unless required for the launcher.
- Cleartext HTTP traffic is disabled; transfer sockets use the encrypted OptiShare application protocol.

## Pair authentication limitation before public release
Encryption alone does not authenticate that the nearby peer is the intended person if an active attacker can interpose during the initial key exchange. OptiShare derives and displays a six-digit session security code, but the current build must not be marketed as fully MITM-resistant until one of these release gates is completed:

1. Require both users to confirm that the displayed security codes match before the first encrypted file frame is accepted; or
2. Bind the ephemeral session to a QR-authenticated long-term/device public-key fingerprint.

This is a public-release blocker for strong peer-authentication claims.

## Threats considered
- Passive local-network eavesdropping.
- Active modification of file chunks or metadata.
- Replay or malformed protocol frames.
- Path traversal / malicious filenames.
- Oversized counts and lengths intended to exhaust memory/storage.
- Interrupted transfers and inconsistent partial files.
- Connection loss after some files in a batch have already completed.
- Duplicate output filenames.

## Additional release testing required
- Independent review of protocol framing and handshake.
- Malformed/fuzzed manifest/chunk tests.
- Very large file and very large batch resource-exhaustion tests.
- Forced disconnects during encrypted transfers.
- Security-code / peer-authentication UX completion.
- Dependency vulnerability review before each release.

## Reporting security issues
Before public release, replace this section with the official private security contact address. Do not request public disclosure of sensitive vulnerability details until a remediation process and release channel are established.
