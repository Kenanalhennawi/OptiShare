# OptiShare Android/Windows secure protocol v2

Status: cryptographic framing implemented with shared Java/.NET golden vectors; transport integration follows behind v1 compatibility.

## Goals

- Encrypt file bytes, metadata, clipboard text, hashes, approvals, and completion records.
- Authenticate record direction and ordering.
- Show the same six-digit SAS on Android and Windows before accepting a first session.
- Preserve v1 only as an explicitly identified compatibility path during migration; never silently downgrade after a v2 handshake begins.

## Handshake transcript

1. Client sends ASCII magic `OPTISHARE-PC-2\n`.
2. Client sends a bounded X.509 SubjectPublicKeyInfo P-256 ephemeral public key and a 32-byte random salt.
3. Server sends a bounded X.509 SubjectPublicKeyInfo P-256 ephemeral public key.
4. Both derive ECDH shared secret, then HKDF-SHA-256 with info `OptiShare-PC-v2/session` and 32-byte output.
5. Both compute SAS from SHA-256 over magic, length-prefixed client key, length-prefixed server key, and salt. The first 24 bits modulo 1,000,000 form the zero-padded six-digit code.
6. Both users confirm the matching SAS. Accept/decline is sent only inside an encrypted record.

The public-key order is role-bound, preventing reflection and role confusion. Capability negotiation selects v2 before transfer; a failure after v2 selection terminates the connection instead of falling back to plaintext.

## Encrypted record

Each record is length-prefixed by the transport and is bounded to 1,048,640 bytes:

| Field | Size |
|---|---:|
| Nonce length | 1 byte, exactly 12 |
| Random GCM nonce | 12 bytes |
| Ciphertext | variable |
| GCM tag | 16 bytes |

AES-256-GCM AAD is ASCII `client:<sequence>` or `server:<sequence>`. Sequence numbers start at zero independently in each direction and increase exactly once per record. Any authentication, direction, ordering, length, or sequence failure terminates the session.

## Golden vector

- Shared secret: bytes `00..1f`
- Salt: bytes `20..3f`
- Sequence/direction: `client:0`
- Nonce: bytes `00..0b`
- Plaintext: UTF-8 `OptiShare secure frame`
- Key: `cf2407d9e2499ed91b23511130092e5e85c7a380ef8523014c0b3d47b4db1456`
- Record: `0c000102030405060708090a0baaa7610c5497f76554f3b997dba5295820aac345202c08125c8ad35c4d9fd38d3fa0f9ff1fa9`

Android unit tests and the Windows executable startup self-test must both match this vector.
