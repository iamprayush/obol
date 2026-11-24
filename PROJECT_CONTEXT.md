# Obol — Project Context & Engineering Handoff

> **Purpose:** Everything about Obol that isn't obvious from reading source files alone — product decisions and *why* they changed during live dogfooding, design/voice rules, hard-won Android 15 / Direct Boot / MediaPipe / typography / Drive SAF gotchas, and how to build, test, and resume work cleanly in a fresh session.

---

## 1. What Obol Is

**Obol** (`dev.prayush.obol`) is a personal, single-user, zero-server native Android commitment alarm clock (Kotlin + Jetpack Compose + Room + DataStore + CameraX + ML Kit + MediaPipe).

**Core loop:**
1. Alarm rings on your main phone.
2. You get **5 free minutes** (`300s`).
3. After 5 minutes, a live meter charges **₹20/minute** continuously (₹1 every 3 seconds). **Snooze silences the chime for 5 minutes, not the meter.**
4. The meter stops only when the front camera verifies **10 seconds of brushing your teeth** (face + toothbrush near mouth + brushing motion), or after a 60-second fallback unlock where you type a sentence for a ₹20 override fee.
5. Charges accumulate onto a running tab until unpaid debt reaches **₹300**, at which point you donate the total to charity via **Oppia Donorbox** (Chrome Custom Tab) or **Indian UPI** (`upi://pay`).

**Two overriding product rules:**
1. **The honest morning is free.** Getting up within 5 minutes costs ₹0.
2. **No charge the app cannot justify.** Every rupee is derived deterministically from an append-only, timestamped event log and shown in an itemized bill. If the OS prevents an alarm from firing, the morning cancels for ₹0.

---

## 2. Key Product Decisions & Why They Were Made

Several older design docs in `docs/design/` (`USER-FLOW.md`, `DESIGN-SPEC.md`) reflect the initial draft before live dogfooding. **Where those docs conflict with this file or the code, the code and this file are authoritative.**

| Area | Current Behavior | Why We Changed / Decided This |
| :--- | :--- | :--- |
| **Target device** | Runs on your **main phone** (Pixel 10), not a spare bedside phone. | Dedicated-phone setup was impractical; main-phone reality drove Direct Boot hardening, lock-screen handling, and instant pause. |
| **Wake proof** | **10 seconds** of live camera-verified brushing (`BRUSH_TARGET_MS = 10_000L`). No QR code. No 20-minute stay-up check. | QR codes were rejected early ("easy to scan and go back to sleep"). Original 2-minute brushing + 20-minute post-brush face check were cut during live dogfooding: once you're standing at the sink brushing for 10 verified seconds, you're already up. Legacy `StayUp*` events remain in `MorningEvent` strictly for replaying old event logs. |
| **Rates & caps** | **₹20/min**, **₹600 morning cap**, **₹6,000 monthly cap**, **₹300 running tab** (`MIN_DONATION_THRESHOLD_PAISE`). | Started at ₹10/min (₹300/day, ₹3,000/mo). Live Donorbox testing hit Oppia's **\$3 (~₹260) minimum donation floor**, blocking small ₹25 overruns from checking out. Raising the rate alone couldn't prevent small 1–2 minute overruns from falling under \$3, so unpaid balances `< ₹300` now roll onto a Home tab without stripping tomorrow's 5 free minutes. Once combined unpaid hits `≥ ₹300`, checkout triggers and `markPaidThrough` settles all contributing mornings atomically. |
| **Debt penalty** | Deferring an unpaid balance `≥ ₹300` removes tomorrow's 5 free minutes (`hadPriorDebt = true`). Sub-₹300 tabs do **not** strip free minutes. | Avoids punishing the user for a balance the payment rail wouldn't let them pay yet, while preserving teeth once the tab is payable. |
| **Trial week** | **Removed completely.** Real money counts from Day 1. | Live dogfooding showed a pretend week diluted the commitment device. Legacy `isTrialDay` column remains in `morning_headers` for schema continuity but is ignored during billing. |
| **Pause & alarm edits** | **Both apply immediately** (12-hour pause delay and 24-hour alarm-weaken lock removed). | Needed Pause to act as an immediate kill switch outside active ringing, and alarm edits shouldn't block legitimate schedule changes. (`PaymentDestinationState` still keeps its 24-hour change delay once set.) |
| **Nights off (grace nights)** | 3/month, declarable `≥ 2h` before alarm, with **Undo night off** anytime before the alarm fires. | Undo was added after an accidental tap; moving/disabling an alarm or pausing automatically refunds any orphaned future grace night (`pruneOrphanedFutureGraceNights`). |
| **History & rate changes** | Infinite local retention (never pruned); Room v2 snapshots `ratePaisePerMin`, `morningCapPaise`, `monthlyCapPaise` per morning. | Before Room v2, replaying past event logs against `ObolConstants` retroactively doubled historical charges when rates rose from ₹10 to ₹20/min. History also supports an **All time** toggle with total spend, avg/worst wake-up time, and month-labeled rows. |
| **Alarm sound** | 3 synthesized PCM chimes (`Marimba`, `Singing bowl`, `Electric piano`) + Pixel's native `RingtoneManager` picker with live preview. | Default tone felt too limited; system ringtones can lose read access during Direct Boot or across phone restores, so `AlarmAudioController` silently falls back to `Marimba` PCM if a system URI cannot be opened. |
| **Google Drive backup** | Single-file SAF JSON (`obol-backup.json`) via `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT` — **no GCP OAuth / Drive REST API**. | Direct Drive REST API v3 on a personal Gmail requires a GCP project + Android OAuth Client ID; leaving OAuth in "Testing" mode causes Google to expire authorization every **7 days**, while publishing requires Brand Verification + a public privacy policy URL. SAF needs zero GCP setup, zero `INTERNET` permission (the Pixel Drive app handles upload/offline retries and 30-day revision history), and restores cleanly onto any future phone regardless of APK signing key. |

---

## 3. Visual Design & Copy System

Preserving Obol's visual identity is a hard requirement for any future UI changes:

- **Voice:** Minimal, deadpan, cheeky-villain — polite on the surface, dryly enjoying taking your money. **No mascot, no loud ad-banner graphics, no cringe jokes, and never put jokes in button labels.** At most one dry line per screen.
- **Sunrise cinema:** Ink-navy calm sky (`#10152B`) with one flowy element — a soft tangerine sun blob holding the rupee figure, rising over a curved horizon while the sky heats continuously from navy to apricot as `displayPaise / effectiveCapPaise` grows.
- **Typography:** Giant **Fraunces light-italic** (`opsz`, `wght`, `SOFT=100`, `WONK=1`) for hero numerals/times; **Instrument Sans** for labels and body copy (chained to **Inter** for the `₹` glyph).
- **Layout:** Fixed `360×800` proportional coordinate system (`ObolScreenFrame` / `ObolLayoutScale`), plain cream primary pills, thin outlined secondary pills, and bottom sheets styled like `AlarmSoundSheet` and `BackupSheet` (navy surface, cream ink, tangerine active/error highlights).

---

## 4. Architecture & Non-Negotiable Invariants

### Module Layout
- **`:domain`** (`domain/src/main/kotlin/dev/prayush/obol/domain/`) — Pure Kotlin JVM module with **zero Android imports**:
  - `model/Paise.kt` — `@JvmInline value class Paise(val value: Long)` (all money arithmetic).
  - `model/ObolConstants.kt` — Rates, caps, timers, override sentence.
  - `model/MorningModels.kt` — `MorningHeader`, `MorningEvent` sealed hierarchy, `DerivedMorning`, `BillRow`.
  - `engine/MorningDerivation.kt` — Pure deterministic event-log reducer + next wake-deadline calculator.
  - `policy/SchedulePolicies.kt` — `AlarmSchedulePolicy`, `PauseState`, `PaymentDestinationState`, `GraceNightPolicy`, `AlarmSoundChoice`.
  - `time/ObolClock.kt` — Injected `ObolClock` (`now`, `zone`, `bootCount`, `elapsedMs`) + `deltaMs` piecewise monotonic math.
- **`:app`** (`app/src/main/kotlin/dev/prayush/obol/`) — Android shell (`minSdk = 31`, `targetSdk = 35`):
  - `data/` — `ObolDatabase` (Room v2), `ObolPreferences` (DataStore), `ObolRepository` (reactive `AppSnapshot` + mutex-serialized mutations + `reconcile`), `ObolBackup` (`ObolBackupCodec`, `ContentResolverBackupUriIo`, `ObolBackupManager`).
  - `alarm/` — `AlarmScheduler` (`setAlarmClock`), `AlarmReceiver`, `BootAndTimeReceiver`, `AlarmRingingService`, `AlarmAudioController` (PCM synth + volume floor enforcement).
  - `vision/` — `CameraViewfinder`, `RealVisionFrameDetector` (ML Kit Face + MediaPipe `efficientdet_lite0.tflite`), `CrossingCoordinator` (sliding-window brushing verifier).
  - `payment/` — `PaymentController` (UPI URI builder/parser + Oppia Donorbox Custom Tab + sheets).
  - `design/` — `ObolPalette`, `ObolTypography`, `ObolSkyCanvas`, `ObolComponents`.
  - `ui/` — `MainActivity`, `ObolViewModel`, `ObolCopy` (pure screen-model mapper + 25 canonical presets), `HomeScreens`, `MorningScreens`, `BrushingScreens`, `AfterScreens`, `DaylightScreens`, `SetupScreens`.
  - `src/debug/` vs `src/release/` — Debug build exposes a `DBG` drawer for clock/vision simulation; release build strips it completely (no-op `DebugDrawerOverlay` + `BuildConfig.DEBUG` guards on `ObolViewModel` debug hooks).

### Non-Negotiable Invariants
1. **Money is `Paise(Long)` only** — never use `Float` or `Double` for currency.
2. **Injected `ObolClock` everywhere** — never call `System.currentTimeMillis()` or `Instant.now()` inside domain/repository logic.
3. **Event-sourced morning truth** — `MorningDerivation.evaluate` derives every phase, charge, unpaid balance, and bill line from `morning_events`. Within the same `bootCount`, elapsed time uses monotonic `elapsedMs` (defeating manual wall-clock rewind); across reboots, it bridges using wall-clock `at`.
4. **Zero stored photos** — camera frames are analyzed in memory and closed immediately.
5. **No `INTERNET` permission** — Donorbox opens in Chrome Custom Tabs and Drive backup delegates upload/sync to Android's SAF Google Drive provider.

---

## 5. Hard-Won Technical Gotchas (Do Not Re-Break)

### A. Android 15 (`targetSdk = 35`) & Direct Boot (`user_de`)
Obol is `directBootAware="true"` so alarms still fire after an overnight reboot before the user enters their PIN.
1. **Device-Protected storage for Room + DataStore:** Both `obol.db` and `obol_prefs.preferences_pb` live under `createDeviceProtectedStorageContext()`. **Never** use `androidx.datastore.preferences.preferencesDataStoreFile(name)` — internally it calls `applicationContext.filesDir`, escaping back into locked Credential-Encrypted (`/data/user/0/`) storage and crashing during Direct Boot. Keep `ObolPreferences.resolveDeviceProtectedDataStoreFile`.
2. **Android 15 FGS boot restriction:** Android 15 blocks starting `mediaPlayback` foreground services directly from `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED`. `AlarmRingingService` declares `foregroundServiceType="systemExempted|mediaPlayback"` and `BootAndTimeReceiver` uses `AlarmScheduler.scheduleImmediateBootRecovery()` (`setAlarmClock` +1s) to enter under `REASON_ALARM_MANAGER`.
3. **ML Kit Direct Boot init crash:** `MlKitInitProvider` has `directBootAware=false`, so it is skipped on `LOCKED_BOOT_COMPLETED`. `RealVisionFrameDetector.ensureMlKitInitializedForDirectBoot` catches `IllegalStateException` and calls `MlKit.initialize` with a `ContextWrapper(dpContext)` whose `getApplicationContext()` returns `this`.
4. **SAF + Direct Boot boundary:** Google Drive's `DocumentsProvider` lives in CE storage and is unavailable while locked. `ObolBackupManager` always checks `UserManager.isUserUnlocked` first; locked-boot mutations set `backupDirty = true` in DE DataStore and flush automatically on next unlock (`BootAndTimeReceiver` / `MainActivity.onResume`).

### B. Google Drive SAF Overwrite Trap
- Google Drive's `DocumentsProvider` (`com.google.android.apps.docs.storage`) does **not** support folder selection (`ACTION_OPEN_DOCUMENT_TREE`), and `openFileDescriptor(uri, "wt")` throws `FileNotFoundException: Unsupported mode: wt`.
- `ContentResolverBackupUriIo.writeBytes` explicitly opens **`"rwt"`** first (with `"wt"` and `"w"` fallbacks), writes UTF-8 JSON, syncs the descriptor, and immediately reads back + validates `ObolBackupCodec.decodeAndValidate` before recording success.
- Persistable Drive URI permissions can occasionally be revoked if Drive app data resets; `SecurityException` marks `lastBackupError` so Settings highlights `Needs reconnect` without disrupting alarms.

### C. Compose Typography & Italic Clipping
1. **Missing `₹` glyph in Instrument Sans:** `instrument_sans.ttf` has no `U+20B9` (`₹`) in its `cmap`, and Compose `FontListFontFamily` does not perform per-glyph fallback across matching font weights. `ObolTypography` chains `instrument_sans.ttf` → `inter_fallback.ttf` via `android.graphics.Typeface.CustomFallbackBuilder` and passes explicit `FontVariationAxis` (`wght`, `opsz`, `SOFT`, `WONK`), plus `RupeeSansText` applies an `AnnotatedString` span fallback for Robolectric.
2. **Fraunces italic overhang slicing:** Negative letter tracking (`-0.035em` / `-0.03em`) slices off the right edge of trailing italic glyphs (`l`, `0`, `2`) when Compose measures at advance width. `ObolHeroText` and `AlarmsScreen` prevent this with a single-pass `measurable.maxIntrinsicWidth(Constraints.Infinity)` + `paddedWidth = naturalWidth + bleedPx * 2` + `TextAlign.Center` + `placeRelative(-bleedPx, ...)`. **Never call `measurable.measure()` twice inside a Compose `layout` modifier** (it mutates the shared `Placeable`).

### D. Vision Pipeline (ML Kit + MediaPipe)
1. **Single upright bitmap space:** MediaPipe `ObjectDetector` (`efficientdet_lite0.tflite`, COCO label `toothbrush`) throws `UnsupportedOperationException` on `YUV_420_888` `MediaImage` and emits unrotated coordinates whereas ML Kit emits rotated coordinates. `CameraViewfinder` converts `ImageProxy` once into an upright rotated `ARGB_8888` `Bitmap` (`rotationDegrees = 0`) so both detectors share one coordinate frame behind `VisionFrameDetector`.
2. **ABI caveat on `x86_64` emulators:** `com.google.mediapipe:tasks-vision:0.10.18` ships `arm64-v8a`, `armeabi-v7a`, and `x86` native `.so` libraries — **no `x86_64`**. On `arm64-v8a` physical phones (like Pixel 10), native TFLite runs normally; on `x86_64` emulators, detection degrades gracefully to the ₹20 typed override without crashing.

---

## 6. Build, Test, Database, & Signing Guide

### Environment Prerequisites
- **JDK 21** (or JDK 17; `jvmTarget = "17"`, tested with Temurin JDK 21)
- **Android SDK 35** (`platforms;android-35`, `build-tools;35.0.0`, `platform-tools`)
- **Gradle 8.11.1** (included via `./gradlew`)
- Create a gitignored `local.properties` at the repo root:
  ```properties
  sdk.dir=/path/to/your/android-sdk
  ```
- If your shell injects incompatible JVM flags via `_JAVA_OPTIONS` (e.g. `-XX:UseAVX=2`), run `unset _JAVA_OPTIONS` before `./gradlew`.

### Verification Commands
```bash
# 1. Fast pure-Kotlin domain tests (money, event replay, policies)
./gradlew :domain:test

# 2. Record/update Roborazzi golden screenshots (run FIRST when adding/changing UI screens)
./gradlew :app:recordRoborazziDebug

# 3. Full verification: domain tests + debug & release unit tests + lint + release APK
./gradlew :domain:test check assembleRelease

# 4. In-place install on connected phone
adb install -r app/build/outputs/apk/release/app-release.apk
```
*(After the first Gradle dependency sync, add `--offline` for fast local runs.)*

### APK Signing & Upgrade Safety
- `app/build.gradle.kts` currently signs `release` with `signingConfigs.getByName("debug")` (`~/.android/debug.keystore`) so rebuilds from the same workstation upgrade in-place via `adb install -r` without wiping permissions, Room DB, or alarm state.
- **If you move to a new machine or switch to a dedicated release keystore:**
  1. In Obol on your phone, open **Settings → Google Drive backup → Back up now** and verify `obol-backup.json` is synced.
  2. Uninstall the old APK (`adb uninstall dev.prayush.obol`).
  3. Install the newly signed APK.
  4. Tap **Restore from backup** on the Welcome screen and pick `obol-backup.json` from Google Drive — Obol restores your entire history, alarms, and settings, then walks you through granting the 4 Android permissions.

### Room Schema Versions
- **v1** (`app/schemas/dev.prayush.obol.data.ObolDatabase/1.json`, hash `cbac7124b86cc02bdb61ffd3925da1c9`): initial 4 tables (`morning_headers`, `morning_events`, `alarm_slots`, `grace_nights`).
- **v2** (`app/schemas/dev.prayush.obol.data.ObolDatabase/2.json`, hash `da650f4a525e49c0511e6aaab87856fd`): `MIGRATION_1_2` adds `ratePaisePerMin`, `morningCapPaise`, `monthlyCapPaise` to `morning_headers` with `NOT NULL DEFAULT` values (`2000`, `60000`, `600000`).
- **Rule:** Never add `fallbackToDestructiveMigration()`. Always write an explicit `Migration` and add a real SQLite upgrade test in `ObolBackupAndMigrationTest.kt`.

---

## 7. Known Loose Ends & Good Next Steps
1. **First live Drive write test:** The JSON backup/restore pipeline, `"rwt"` fallback, corruption rejection, and Direct Boot locking are verified in unit tests (`ObolBackupAndMigrationTest`). On the phone, open **Settings → Google Drive backup → Save to Google Drive…**, create `obol-backup.json`, and tap **Back up now** once to confirm live `"rwt"` overwrite with Pixel's Drive app.
2. **Dedicated release signing key:** Whenever convenient, generate a personal release keystore (kept outside git) and wire `signing.properties` so future updates don't depend on `~/.android/debug.keystore`.
3. **Backup JSON schema evolution:** `ObolBackupCodec` strictly enforces `schemaVersion == 1`. If you add new fields to `ObolBackupDocument` later, give them backwards-compatible defaults or bump `CURRENT_SCHEMA_VERSION` with an upgrader so older Drive backups always remain restorable.
