# Validation and device checklist

## Automated coverage

- `OtpTest`: all 18 RFC 6238 algorithm/timestamp vectors, all 10 RFC 4226 vectors through HOTP and TOTP, ASCII codes under an Arabic locale, extended periods, canonical Base32/padding, URL encoding, and malformed/mixed/overflowing counter parameters.
- `SecurityTest`: GCM nonce uniqueness, ciphertext plaintext-absence, tamper/wrong-key/wrong-AAD rejection, vault encoding, duplicate/malformed snapshot rejection, owned-buffer clearing.
- `BackupTest`: round-trip, unique output, wrong password, changed salt/nonce/ciphertext, truncation, password-length policy, unsupported version, malicious KDF count.
- `VaultStorageTest` (Android): persistence across lock/unlock, HOTP advancement and duplicate-import counter preservation, Room v1-to-v2 migration, tampered-file preservation, missing-key non-regeneration, authentication-bound key policy, merged permissions, and generated mock QR decoding.
- `VaultUiTest` (Android/Compose): manual enrollment/search/copy/delete, HOTP advance/cancel, theme/reduced-motion/lock/unlock, persistent unlock errors, and backup round-trip. Mock-account screenshots cover dark/light/200% font scaling. System file pickers and physical biometrics are not bypassed in release code.

JVM tests have run successfully. Android test sources compile but have **not executed**: no connected device or configured AVD was available. The key-policy test skips when no secure lock exists; a skip is not successful authentication coverage.

Run instrumentation on an isolated test device/emulator. Tests now use isolated metadata, encrypted snapshots, and DataStore preferences with published mock seeds; no live vault preferences are changed. Mock-only screenshots are written under the test app's external-files `ui-validation` directory. Real app screens remain protected by FLAG_SECURE.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

Reports: `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/lint-results-debug.html`, and after device execution `app/build/reports/androidTests/connected/`. Release output is unsigned. Dependency-update and unused-resource lint notices are not suppressed.

## Mandatory pre-release device matrix

Cover API 30 and API 37, a credential-only emulator, a physical class-3 biometric device, and hardware with/without StrongBox. Test airplane mode and verify the merged APK lacks Internet permission.

1. **Authentication:** first setup cancellation/retry, strong fingerprint/face, PIN/password/pattern fallback, biometric lockout, fallback cancellation, secure-lock removal, and key invalidation. Failed authentication must not expose codes or overwrite files.
2. **Enrollment:** QR/manual/URI, absent issuer, encoded colon/plus/Unicode labels, invalid QR, TOTP and HOTP, duplicate names, Base32 errors, algorithms/digits and custom periods. Reject missing/negative/overflow counters, SHA2 HOTP, and mixed counter/period fields. Cross-check disposable service accounts.
3. **Camera:** grant/deny/permanent denial/revocation, missing/front-only camera, rotation, repeated navigation, offline model readiness. Inspect app files/logcat for unintended frame/URI persistence.
4. **Clock:** timezone changes, clock jumps both ways, exact rollover, suspend/resume, expired displayed codes, fresh-code copy. The app cannot independently verify time accuracy and must not claim it does.
5. **Lifecycle:** background every sensitive screen, screen off, recents, process death, rotation, each inactivity setting, repeated unlock. Verify code/key/staging clearing and absence of secret saved state. Unsaved input is deliberately discarded by default locking.
6. **Accounts:** 0/1/100/1000 accounts; search, favorites, folder edits/conflicts, deletion cancellation, privacy visibility, clipboard expiration and replacement ownership.
7. **Backup:** local/external providers, picker cancellation, authentication expiry/cancellation, wrong password, generated-key recovery onto a fresh installation, mixed/duplicate-only restores, malformed/tampered/oversize files, low disk, provider failures, and process interruption around vault/index commits. No silent account loss; incomplete exports must fail validation.
8. **Accessibility/visuals:** phones/tablets/landscape, smallest width, large display settings, 200% font scale, TalkBack, switch access, contrast, touch targets, IME coverage, keyboard navigation, dark/light, empty/error/busy states. Native visual review/screenshots have not run here.
9. **Release:** signed optimized non-debuggable APK, dependency/advisory/license review, log/crash secret absence, backup exclusions, exported components, native-library compatibility including page sizes.

## Known gaps

Live biometric/credential prompt UI, actual OS key invalidation, SAF providers, accessibility, localization, and process-kill/low-storage fault injection require the matrix above. Tests do not establish guaranteed memory erasure, flash deletion, anti-rollback, or compromised-device resistance. No independent penetration test or current CVE scan has been completed.