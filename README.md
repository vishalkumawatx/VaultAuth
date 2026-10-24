# Vault Auth

Native Android authenticator built with Kotlin, Jetpack Compose, and Material 3. This is a build-verified implementation, **not a security-audited production release**. Use disposable test accounts until device validation and independent review are complete.

## Build and run

1. Open this project root in Android Studio with support for Android Gradle Plugin 9.3.3.
2. Install Android SDK Platform 37, compatible Build Tools, and Platform Tools in SDK Manager. `local.properties` contains the local SDK path and must not be committed.
3. Use JDK 25 for the existing Gradle daemon configuration. The checked-in daemon criteria can provision it through Foojay. Source bytecode targets Java 17. Gradle 9.5.0 and its distribution checksum are pinned in the wrapper.
4. Sync Gradle. Select an Android 11 / API 30 or newer device with a PIN, password, or pattern. Strong biometrics are optional; a secure screen lock is required.
5. Run the `app` configuration. To debug, place breakpoints in `MainActivity`, `VaultViewModel`, or `LocalVaultRepository`, then use Android Studio Debug.

Windows commands from the project root:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:assembleRelease
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Install with Android Studio or `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`. Configure signing outside source control before distribution. Release debugging is disabled and R8/resource optimization is enabled. Do not use a debug APK for personal credentials.

Minimum API 30 is deliberate: the key policy uses per-operation strong-biometric OR device-credential authentication on that platform baseline. Do not lower it without redesigning/testing authentication.

## Implemented workflows

- Onboarding; cryptographically authenticated unlock; default background locking; 30/60/300-second inactivity locking.
- Offline TOTP using java-otp: SHA1/SHA256/SHA512, 6/7/8 digits, 1-86400-second periods. Standard SHA1 HOTP with persisted counters and explicit advancement. RFC 4226/6238 vectors and locale-independent ASCII digits.
- CameraX and bundled ML Kit QR enrollment, manual Base32 entry, standard `otpauth://totp/` and `otpauth://hotp/` URIs, explicit save confirmation.
- Search, favorites, named folders, editing, delete confirmation, hidden codes, per-card countdowns, and fresh-code copy.
- Charcoal/emerald dark and light themes, bundled Manrope interface typography and JetBrains Mono codes, responsive forms, short navigation transitions, animated list placement, and Reduce motion. Locking removes account content immediately, without exit animation.
- AES-256-GCM local vault in `noBackupFilesDir`, Android Keystore wrapping key, separate Room metadata-only index.
- DataStore non-secret preferences, `FLAG_SECURE`, sensitive clipboard marking and 30-second/background clearing of the app's own entry.
- Optional encrypted SAF backup/restore, fresh authentication, verified restore confirmation, duplicate skipping, generated recovery keys.
- In-app security explanations, Android biometric/date-time settings links, About, privacy policy.

Enrollment remains on its input screen after confirmation so another account can be added. Use Back to return home. Secret inputs deliberately are not restored after process recreation or default background locking; enter or scan them again.

## Unlock troubleshooting

Unlock errors stay on the lock screen with a reference code. `AUTH_EXPIRED` or `OPERATION_LOST` means retry authentication; it does not establish key loss. `STORAGE` means check device storage and retry. `UNKNOWN` means the failure could not be classified, not that a backup is required. Only confirmed missing/invalidated keys or validation failures display recovery guidance. Never uninstall or clear app storage as an unlock troubleshooting step.

The authenticated cipher returned by Android finishes the operation; AES-GCM associated data is supplied after authentication. Existing wrapper bytes and key aliases are unchanged by this update.

## Backup and recovery

Open **Settings > Backup & restore**. Choose a unique passphrase of at least 16 characters, preferably six or more randomly chosen words, or generate the 256-bit recovery key. Record it separately and confirm it. Authenticate again and export a new `.vaultauth` document within 30 seconds.

To restore, select a Vault Auth v1 backup. The file picker backgrounds and locks the app by default. Unlock and return to Backup & restore; the encrypted selection is retained in memory. Enter the original password, authenticate again, and choose **Verify & review restore**. Confirm the counts. Existing IDs or case-insensitive issuer/account pairs are skipped, never overwritten.

Only the documented Vault Auth encrypted format is supported for bulk import. Standard single-account TOTP and HOTP QR codes are supported. Google Authenticator migration QR/protobuf, Aegis/2FAS exports, Steam's proprietary codes, and other proprietary transfer formats are **not** supported. SMS/email codes, passkeys/FIDO2/security keys, and Microsoft/Duo/service-specific push approvals cannot be generated from an OTP seed and remain managed by their provider.

Version 1.1 reads legacy v1 TOTP snapshots and writes v2 snapshots with explicit OTP type/counter. The encrypted envelope and key aliases remain unchanged. Room upgrades its metadata index from v1 to v2 without dropping accounts. Old app versions reject new snapshots rather than interpreting HOTP as TOTP. Do not downgrade after using this version.

HOTP counters range from 0 to 9223372036854775807. Use the initial counter supplied by the service. Copy does not advance it; **Next code > Advance counter** persists the next counter before publishing it. Do not use the same HOTP account on multiple devices. A backup contains a historical counter; after recovery, service-side resynchronization may be necessary. Duplicate imports preserve the existing counter.

Device unlock supports Android class-3 (strong) biometrics when available, plus a direct PIN/pattern/password path. Weak face unlock is not accepted as a substitute for the Keystore authentication policy. The device must have a secure screen lock.

There is no server or cloud synchronization, and network permissions are removed from the merged manifest. A user-selected document provider may independently sync the **encrypted** backup.

**Lost device + no usable backup, or lost backup password/key = no recovery through this app.** Keep service recovery codes. A generated recovery key is an alternative backup password, not a second recovery mechanism. Generating a key without exporting does not back up the vault. New exports do not revoke old files.

## Source map

Complete source is in the workspace; no core-security pseudocode or release authentication bypass is included. Kotlin paths below are under `app/src/main/java/com/example/authanticator/`.

| Area | Files |
| --- | --- |
| Entry and dependency graph | `MainActivity.kt`, `VaultApplication.kt` |
| OTP/enrollment | `totp/Otp.kt`, `domain/Enrollment.kt` |
| Security | `security/Aead.kt`, `security/DeviceKey.kt`, `security/ClipboardGuard.kt` |
| Persistence | `data/MetadataDatabase.kt`, `data/VaultCodec.kt`, `data/VaultRepository.kt` |
| UI/state/navigation/theme | `ui/VaultApp.kt`, `ui/VaultViewModel.kt`, `ui/VaultTheme.kt` |
| Settings | `settings/Preferences.kt` |
| QR | `scanner/ScannerScreen.kt` |
| Backup | `backup/BackupCodec.kt`, `backup/BackupScreen.kt` |

Build configuration: `build.gradle.kts`, `settings.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`. Android configuration: `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/data_extraction_rules.xml`. Branding: `app/src/main/res/drawable/vault_mark.xml`, `app/src/main/res/values/vault_theme.xml`, `app/src/main/res/values/strings.xml`. Room schemas: `app/schemas/`.

Local tests: `app/src/test/java/com/example/authanticator/OtpTest.kt`, `SecurityTest.kt`, `BackupTest.kt`. Device tests: `app/src/androidTest/java/com/example/authanticator/VaultStorageTest.kt`, `VaultUiTest.kt`.

An application-owned container and ViewModel factory provide dependency injection instead of Hilt. The repository interface separates UI from storage. Room indexes non-secret metadata; the authenticated snapshot is the source of truth and rebuilds that index on unlock. CPU-heavy operations use background dispatchers and persistence uses IO. Coroutines/StateFlow drive immutable UI state. Navigation uses Navigation Compose. A future sync adapter needs a separately reviewed encrypted transport design; the plaintext repository snapshot must never be connected directly to a network client.

## Validation and release limitations

See [TESTING.md](TESTING.md), [SECURITY.md](SECURITY.md), and [PRIVACY.md](PRIVACY.md).

JVM tests, debug compilation, optimized release compilation, and Android lint have passed. Instrumentation sources compile. No connected device or configured emulator was available, so **instrumentation, live authentication, physical-camera behavior, screenshots, accessibility/font-scale checks, and SAF end-to-end validation have not run**.

Remaining release gates: independent security review, current dependency-advisory review, real-device performance/visual testing, signing, UI localization, adaptive/monochrome launcher variants, and transactional key rotation. Lint dependency-update and unused-starter-resource warnings are not suppressed. No automatic reset/deletion path is offered for corrupt vaults or invalidated keys. The app is not claimed to be unhackable or production-certified.

## Typography and motion

Manrope and JetBrains Mono variable TTF fonts are bundled from Google Fonts, under the SIL Open Font License. Licenses are packaged in `app/src/main/assets/licenses/` and accessible through About > Font licenses. There are no runtime font requests. Android's system animation duration scale applies; the in-app Reduce motion setting additionally disables custom navigation/list transitions and countdown interpolation. Code digits are never crossfaded between old and new values.