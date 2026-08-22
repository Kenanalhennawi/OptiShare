# OptiShare 2 Security Model

## Scope

OptiShare transfers user-selected files directly between nearby devices. The security goal is to protect file confidentiality and integrity on the local link and to prevent a nearby third party from being silently accepted as the intended peer.

Wi-Fi Direct security is treated as an additional layer, not as the only security boundary.

## Trust assumptions

- The Android OS and the user's unlocked device are trusted.
- A nearby network participant is **not** trusted.
- Wi-Fi Direct discovery names and device addresses are **not** cryptographic identities.
- A QR containing only a device address/name is a pairing accelerator, not sufficient authentication by itself.
- Selected source URIs may reference untrusted content and metadata.
- Received filenames, MIME types, sizes and manifests are attacker-controlled until authenticated and validated.

## Implemented protocol controls

### Ephemeral key agreement

Every socket session creates a new ephemeral elliptic-curve key pair. Peers derive a shared secret using ECDH on `secp256r1`, selected for the Android 5+ compatibility target. Session key material is expanded with HKDF-SHA256.

### Authenticated encryption

Application frames after the handshake use AES-256-GCM. The frame type is authenticated as associated data and each encrypted frame uses a fresh nonce. Raw TCP is therefore not relied on for confidentiality.

### Mandatory human peer verification

The ephemeral handshake derives the same six-digit security code on both devices. **Both users must explicitly confirm that the codes match before the manifest or file data is accepted.** Declining or timing out terminates that attempt as a security/user-decision failure rather than silently retrying it like an ordinary network outage.

A future QR-authenticated mode may bind a cryptographic fingerprint into the QR payload and remove manual code comparison only for deliberately scanned sessions.

### File integrity

Every file has a SHA-256 digest in the authenticated batch manifest. A receiver publishes the file to public Downloads only when:

1. the complete declared byte count exists;
2. the SHA-256 digest matches;
3. the verified file is published through the storage layer.

Failed integrity verification discards the corrupt partial state instead of exposing it as a completed download.

## Resume security

Resume is built on durable checkpoints, not on optimistic sender progress. A chunk ACK is issued only after the receiver has:

1. appended the chunk;
2. synchronized the file descriptor;
3. persisted the confirmed offset;
4. prepared the ACK.

The current checkpoint size is 1 MiB. After reconnect, the safe offset is aligned to a checkpoint boundary and is never greater than durable receiver state. Any unconfirmed tail is retransmitted.

Completed files are not retransmitted during a resumed batch. Session state is cleared only after verified batch completion or explicit cancellation.

## Input validation

The protocol rejects or bounds:

- invalid magic/version values;
- negative sizes or offsets;
- oversized encrypted frames;
- excessive manifest/resume entry counts;
- invalid checksum lengths;
- chunks larger than the configured checkpoint size;
- chunks that exceed declared file size;
- unknown file IDs;
- unexpected frame types;
- invalid acknowledgements;
- mismatched SHA-256 digests.

Filenames are sanitized before storage, and sender-provided paths are never used directly as filesystem destinations.

## Resource-exhaustion controls

Production requirements bound protocol frames, file counts, metadata lengths, socket timeouts, reconnect attempts and approval timeouts. Public-release testing must additionally cover low storage, 10+ GB files, 1000-file batches, malformed metadata and repeatedly interrupted sessions.

## Android component exposure

- `TransferService` is not exported.
- The launcher activity is exported only for the launcher intent filter.
- Transfer broadcasts are package-scoped.
- Android backups are disabled.
- Cleartext HTTP traffic is disabled.
- No backend service is required to relay transfer content.

## Privacy target

The intended public build contains no advertising, analytics, account or cloud-relay SDK by design. File content is processed locally for selection, encrypted transfer, integrity verification and storage.

## Remaining public-release security gates

- Validate security-code confirmation in both foreground and background flows across API 21–36.
- Bind future QR auto-trust to a cryptographic fingerprint, not a device address.
- Add malformed/fuzzed manifest and frame tests.
- Perform dependency review and Android lint for each release.
- Run an independent security review before public production.
- Add a production security-contact email before launch.

## Reporting security issues

Before public release, replace this section with the official private security contact. Sensitive vulnerability details should not be posted publicly before a remediation process and release channel are established.
