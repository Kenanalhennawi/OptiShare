# OptiShare protocol baseline and 2.3 migration

This document is the clean-room interoperability baseline. Quick Share and SHAREit artifacts are
behavioral references only; no decompiled source, resources, identifiers, or wire formats are used.

## Current transports (2.2)

| Route | Discovery | Wire protocol | Security | Resume |
|---|---|---|---|---|
| Android ↔ Android LAN/P2P | NSD `_optishare._tcp.` or verified P2P | `OS2P` v6 + authenticated `OSX/2` capabilities | ECDH identity handshake + AES-GCM frames + six-digit SAS/pinned identity | Durable per-file chunk checkpoints |
| Android → Windows | UDP `49891` | `OPTISHARE-PC-1` TCP `49890` | Rotating receiver token + SHA-256 final verification | Not yet |
| Windows → Android browser route | UDP `49894` | HTTP upload `49889` | Rotating 128-bit token + explicit phone approval | Not yet |

The two Windows routes are intentionally marked transitional. They must migrate behind capability
negotiation to the same authenticated encrypted session engine before OptiShare 2.3 RC.

## Canonical transfer state machine

`IDLE → DISCOVERING → NEGOTIATING → AUTHENTICATING → AWAITING_APPROVAL → TRANSFERRING`

From `TRANSFERRING`, a session can move to:

- `PAUSED`, then `RECONNECTING` or `TRANSFERRING`;
- `RECONNECTING`, then `NEGOTIATING`, `TRANSFERRING`, or `FAILED`;
- `VERIFYING`, then `COMPLETED`, `PARTIAL`, or `FAILED`;
- `CANCELLED` after an explicit local/remote user decision.

`COMPLETED`, `PARTIAL`, `FAILED`, and `CANCELLED` are terminal. A retry creates a new attempt that
references the previous session/checkpoints; it never mutates a terminal attempt back to active.

## Invariants

1. Discovery never starts a connection. The user chooses **Send here** or **Speed test**.
2. Metadata is bounded before allocation and paths are normalized before filesystem access.
3. Unauthenticated metadata must not contain the stable device identity in the 2.3 protocol.
4. A file is published only after size and digest verification; partial files remain non-public.
5. A transport failure keeps confirmed checkpoints. A per-file content failure does not cancel the batch.
6. Capability negotiation and its selected result are included in the authenticated transcript.
7. A downgrade, replay, unknown mandatory capability, or identity mismatch fails closed.

## Implemented `OSX/2` capability envelope

The first authenticated control message now carries bounded numeric capability IDs, not class names:

- protocol major/minor and minimum compatible minor;
- transport: LAN TCP, P2P TCP, local-only hotspot, optional QUIC;
- AEAD: AES-256-GCM, optional ChaCha20-Poly1305;
- hash: SHA-256, optional BLAKE3;
- maximum chunk, stream count, file count, metadata bytes, and folder depth;
- resume, per-file retry, clipboard, folder manifest, and atomic publish flags.

Unknown bit values and unknown mandatory capabilities abort negotiation. The selected values are the
intersection of both peers and local safety limits, then committed with both ordered offers into a SHA-256
transcript. Both peers compare the same AEAD-protected confirmation before any manifest or benchmark data.

## Interoperability gates

- Java golden vectors lock big-endian metadata and discovery parsing used by the native Windows receiver.
- Android tests lock manifest/chunk/resume bounds and authenticated frame types.
- Windows CI compiles a self-contained native EXE and rejects any PowerShell runtime dependency.
- The next migration step adds the same golden vectors to .NET and a loopback Android/.NET transcript test.
