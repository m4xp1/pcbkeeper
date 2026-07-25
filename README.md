# PrivateCard Backup Keeper

A small Android 11 foreground service for safely archiving `PrivateCardBackup.pc` from PrivateCard's app-specific external-storage directory.

## Automatic backup safety model

1. Wait until the source file is stable across multiple scans and at least ten seconds.
2. Copy it to internal `Documents` through a temporary file.
3. Flush and verify size plus SHA-256.
4. Copy the verified internal file to removable-SD `Documents` through SAF.
5. Re-open and verify the SD copy.
6. Re-check the source hash, then delete the source.
7. Delete the `Pictures` directory recursively.

Transaction state is persisted after every destructive boundary so a process or device restart resumes the same operation without creating a new numbered backup.

## Manual USB flash-drive backup

The **Backup to flash drive** button opens Android's system folder picker. Select `Documents` on the connected USB drive.

The one-shot foreground service then:

1. Enumerates regular `*.pc` files in internal `Documents`.
2. Hashes every source with SHA-256.
3. Re-opens and hashes same-named files already present on the USB drive.
4. Skips files whose size and SHA-256 match.
5. Copies missing files through deterministic hidden `.part` files.
6. Flushes, re-opens, and verifies every new USB copy before renaming it to the final name.
7. Re-checks the source before finalizing the destination.
8. Never overwrites a same-named USB file whose checksum differs; it reports a conflict instead.

The USB operation never deletes or modifies files in internal `Documents`.

## Android 11 setup

Android 11 blocks ordinary apps from directly accessing another app's `Android/data` directory. On first launch, grant the source tree through the system folder picker, then grant `Documents` on the removable SD card. The app targets API 29 only to keep Android 11's system picker capable of granting this tree; it is not intended for Google Play distribution.
