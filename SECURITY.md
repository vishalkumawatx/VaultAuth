# Security architecture

## Threat model

Protect persisted TOTP/HOTP secrets against filesystem-only disclosure and unauthorized app use on a normally functioning Android device. Authenticate local/backup ciphertext before interpreting secrets. No guarantee is made against a compromised OS, privileged instrumentation, malicious IME/accessibility service, an already-unlocked session, physical coercion, or someone who knows the device credential. Root/integrity heuristics are not implemented.

No network permission survives manifest merging. Camera frames are processed by bundled ML Kit without app persistence. There are no app-authored secret/OTP logs or analytics calls. Vendor libraries still require independent review; removing Internet permission blocks transmission but does not prove the absence of internal diagnostic events.

## Key lifecycle

1. First unlock preparation generates a 256-bit AES Keystore wrapping key, alias `vault-auth.wrapper.v1`. It requires per-operation strong-biometric OR device-credential authentication, with authentication timeout zero on API 30+.
2. StrongBox is requested when advertised; `StrongBoxUnavailableException` falls back to standard Android Keystore. Standard Keystore may be TEE-backed or software-backed. The app does not claim identical hardware protection on every device.
3. A random 256-bit data-encryption key (DEK) is generated with `SecureRandom`. AndroidX Biometric receives the Keystore cipher through `CryptoObject`; only successful authentication completes wrapping/unwrapping. Cancellation clears the pending DEK without writing a wrapper. An unused alias can remain after cancelled setup and be reused.
4. `device-key.v1` contains a 12-byte IV, 32-byte encrypted DEK, and 16-byte GCM tag. Associated data is UTF-8 `VaultAuth/device-key/v1`. The cipher provider supplies a fresh IV. AtomicFile writes the wrapper after authentication.
5. Unlock unwraps the DEK and authenticates/decrypts the vault. Session secrets and DEK are held in memory. `vault.v1` uses AES-256-GCM, fresh 96-bit provider-generated IVs, a 128-bit tag, associated data `VaultAuth/accounts/v1`, and a 4 MiB envelope bound.
6. Default background locking and mandatory inactivity locking wipe owned secret/key buffers, clear UI codes and enrollment/decrypted-restore staging, and require another cryptographic unlock. Background locking is configurable; screenshot/recents protection remains enabled.
7. Biometric enrollment changes may leave the key usable through credentials or invalidate it depending on platform policy. Removing/resetting the secure lock, platform invalidation, app-data clearing, and uninstall can make the wrapper unrecoverable. Missing keys are never regenerated when encrypted records exist; corrupted records are never automatically deleted.

**Key rotation is not implemented.** Versioned aliases/formats allow a future migration, but crash-safe rotation requires separate design/testing. A new export password does not rotate the local key or revoke older exports. Recover onto a clean installation using a previously verified backup, or use service recovery codes. Do not clear existing data before understanding the loss.

## Persistence and atomicity

The encrypted snapshot contains secrets and metadata and is the source of truth. AtomicFile preserves the previous snapshot on unsuccessful writes. Room `metadata.db` contains only ID, issuer, account, algorithm, digits, period, favorite, and folder. It never contains secrets, OTPs, or provisioning URIs. The index is transactionally refreshed after a vault commit and rebuilt on unlock. If index refresh fails after commit, the app locks and explicitly reports that the encrypted vault was saved but the index needs rebuilding.

Vault and Room files live in `noBackupFilesDir`. DataStore stores non-secret preferences. Cloud backup is disabled and cloud/device-transfer exclusions cover credential/device-protected root/file/database/preference and external domains. Metadata/preferences are not protected against arbitrary app-data access.

Synchronized repository operations prevent concurrent mutation/key disposal. IO/CPU work runs off the main thread. Locking can briefly wait for an in-flight repository operation; real-device latency tests remain necessary. The 1,000-account limit bounds snapshot costs.

## Backup v1 format

Extension `.vaultauth`, MIME `application/octet-stream`, maximum 4 MiB, integers big-endian.

| Offset | Length | Meaning |
| --- | --- | --- |
| 0 | 5 | ASCII `VAULT` |
| 5 | 1 | Version `0x01` |
| 6 | 4 | PBKDF2 count, exactly 600000 |
| 10 | 16 | Fresh random salt |
| 26 | 12 | Fresh AES-GCM IV |
| 38 | Variable | Encrypted UTF-8 JSON plus 16-byte GCM tag |

The entire 26-byte header is authenticated as associated data. PBKDF2-HMAC-SHA256 derives a 256-bit AES key. Unknown versions, unexpected KDF counts, wrong passwords, malformed/oversize files, and invalid tags are rejected. Fixed KDF settings prevent malicious work factors. PBKDF2 is platform-compatible but not memory-hard: strong passwords are still necessary against offline attacks. Benchmark 600,000 iterations across supported hardware before release.

Authenticated plaintext example, using a published test seed:

```json
{"version":2,"accounts":[{"id":"550e8400-e29b-41d4-a716-446655440000","issuer":"Example","account":"test@example.invalid","algorithm":"SHA1","digits":6,"period":30,"favorite":false,"folder":"","otpType":"TOTP","counter":0,"secret":"GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"}]}
```

JSON exists only inside encryption or transient memory. Import validates UUID uniqueness, OTP parameters, Base32, names, folders, and count bounds before confirmation. ID or trimmed case-insensitive issuer/account matches are skipped, preserving existing accounts and reporting counts. A different secret under the same name is a conflict, never overwritten.

Export/restore verification requires fresh cryptographic authentication with a one-operation/30-second application approval window. The window is not itself hardware-enforced export authorization. Restore commits only after explicit confirmation. Background locking clears decrypted restore staging. The SAF transition retains only encrypted bytes; process death requires selecting/exporting again. Providers do not universally offer atomic writes: failed exports can leave incomplete encrypted documents, which restore rejects. CreateDocument requests a new document; providers control destination semantics.

A recovery key is 32 random bytes encoded as 52 unpadded Base32 characters. It serves as the export password through the same KDF and is neither stored nor escrowed. It is not a second mechanism for passphrase-protected exports. Lost password/key means no backup decryption; lost local key plus no usable backup means no app-side recovery.

## OTP compatibility and migration

Snapshot schema v2 is independent of the unchanged v1 encrypted-envelope header. V1 snapshots without type/counter fields decode as TOTP; ambiguous v1 records containing those fields are rejected. V2 requires explicit `otpType` and integer `counter`. The Room v1-to-v2 migration adds matching metadata columns without dropping accounts. A legacy app rejects schema v2. Cryptographic key aliases and associated-data strings remain unchanged.

TOTP supports SHA1/SHA256/SHA512, 6-8 digits, and periods 1-86400 seconds. Standard HOTP uses SHA1 and a non-negative signed-64-bit counter. Event counters never advance on display or copy; explicit advancement is atomic with the encrypted snapshot and cannot overflow. Restoring duplicates preserves existing counters, but restoring an old backup on a new installation may need service-side resynchronization. No service synchronization or HOTP rollback prevention is claimed. Use HOTP on a single device.

Both output types force ASCII digits regardless of Android locale. The optional provisioning `image` query parameter is discarded, never downloaded. Unsupported crypto parameters, mixed period/counter semantics, malformed counters, proprietary QR schemes, and unsupported OTP lengths are rejected. SMS, passkeys, FIDO/security keys, and provider push approvals are outside the OTP protocol and not impersonated by this app.

The direct device-credential button restricts the Android prompt to `DEVICE_CREDENTIAL`; the wrapping key still requires per-operation authentication. Weak biometrics are not enabled. UI transitions are confined to the unlocked navigation tree; locking destroys it immediately.

## Memory and disclosure

Owned byte/character arrays are cleared where practical. Immutable Compose/JSON/Base32 strings, JCA key copies, input methods, and coroutine cancellation boundaries prevent guaranteed JVM erasure. Secrets never enter saved-state handles, navigation arguments, DataStore, or ordinary databases. Default background locking intentionally discards unsaved secret fields, including during rotation.

Clipboard entries are sensitive-marked and tagged with a random label; only the app's current entry is cleared. Copy computes a current code. OS clipboard restrictions/history and other apps can retain data. Privacy mode hides rendered codes but adds no encryption boundary. `FLAG_SECURE` is not a guarantee on compromised devices.

## Before release

- Execute TESTING.md's device matrix, particularly key invalidation, authentication cancellation, process death, and interrupted writes.
- Independently review authentication, key lifetimes, nonces, parsing, backup derivation, and vendor behavior.
- Audit resolved dependencies against current advisories and licenses; pinned versions/passing lint are not CVE scanning.
- Fuzz URI/Base32/JSON/backup parsing; stress large vaults, slow storage, and low-memory devices.
- Review rollback threats. No monotonic anti-rollback state is implemented for local snapshots or old valid backups.
- Design/test transactional rotation before claiming a complete key-management lifecycle.
- Configure protected release signing and validate the optimized offline APK on real devices.