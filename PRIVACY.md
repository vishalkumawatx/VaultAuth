# Privacy and permissions

Vault Auth processes setup keys, account labels, folders, preferences, and optional encrypted backups on-device. It has no registration server, sync backend, ads, or app-authored analytics. Internet/network-state permissions introduced transitively by ML Kit are explicitly removed during manifest merging.

## Permissions

- `CAMERA`: optional, requested only for QR enrollment. CameraX supplies frames to bundled ML Kit; the app does not save/upload them. Manual entry needs no camera.
- `USE_BIOMETRIC`: invokes Android authentication against a Keystore cipher. The app never receives biometric templates or the device PIN/password/pattern.
- AndroidX supplies a signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` for receiver isolation, not personal data access.
- SAF grants access to specifically selected backup documents; there is no broad storage permission. Providers may independently sync encrypted files.
- The launcher is exported for Android to start it, has no provisioning deep-link filter, and ignores incoming secret-bearing intents. Library components are private except AndroidX's profile receiver, protected by the platform `DUMP` permission.

No contacts, location, microphone, Internet, or network-state access is requested in the merged app.

## Retention and deletion

Setup keys are encrypted in the vault; Room stores non-secret metadata and DataStore stores preferences. Android backup/device-transfer excludes app data. User exports remain at their selected destinations.

Deleting an account removes it from future snapshots but not older backups, and does not disable 2FA at the service. Flash wear leveling and filesystem behavior prevent guaranteed physical erasure. Uninstall removes local app access and normally its Keystore entries; independently exported documents remain.

Copy is optional. Codes are sensitive-marked and the app attempts timed/background clearing of its own clipboard entry. Clipboard history/recipient apps may retain copies. Screenshots/recents are blocked where Android honors `FLAG_SECURE`. Malicious accessibility services, keyboards, or a compromised device can defeat these controls.

## Recovery

No support-side secret recovery or password reset exists. Keep service recovery codes and test a backup before relying on it. Losing the local key without a usable backup, or losing the backup password/recovery key, can permanently remove access through Vault Auth. Service-provided recovery is the remaining option.

This policy describes the present offline implementation. Any future cloud capability requires a separate threat model, consent flow, and revised policy.