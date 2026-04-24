---
module: core-playback
phase: 6
phase_title: Play Store Readiness
step: 4
mode: Code
blocked: null
regime: Build
review_done: false
---

# Noise Machine — Development Plan

<!-- This file is the primary state document for autonomous iteration.
     Workers read it on every cold start to determine what to do next.
     Keep it concise — the DEVPLAN should get SHORTER as work progresses. -->

## Cold Start Summary

<!-- Stable section — update on major shifts, not every step. -->

- **What this is** — Minimal Android sleep-noise app with one Color slider backed by a real-time stereo noise engine (white-noise base + continuous spectral shaping + foreground-service playback).
- **Key constraints** —
  - Android (Kotlin, Jetpack Compose, ViewModel, Foreground Service, AudioTrack).
  - Real-time synthesis only — no prerecorded audio assets.
  - Audio render loop must be allocation-free; no GC pressure during playback.
  - Must survive screen-off, backgrounding, and many-hour sessions without artifacts.
  - No in-app volume slider; native media volume only.
  - Parameter changes must go through smoothing — no zipper noise, no clicks/pops.
- **Gotchas** —
  - **Use the wrapper, not system Gradle.** System `gradle` may be incompatible with AGP. Always call `.\gradlew.bat` or `./gradlew`.
  - **Config cache can mask stale state.** If a build behaves weirdly after a big change, try `--no-configuration-cache` once.
  - **ParameterSmoother rate mismatch.** If calling `next()` per-buffer instead of per-sample, use `nextBlock(bufferSize)` to advance correctly. Per-buffer `next()` makes the time constant ~bufferSize× slower.
  - **`val state by flow.collectAsStateWithLifecycle()` needs `import androidx.compose.runtime.getValue`.** Without it, Kotlin can't resolve the `by`-delegate and the build fails.
  - **`StandardTestDispatcher` needs `runCurrent()` after `advanceTimeBy()`.** Without it, coroutine continuations at the advanced time are queued but not dispatched.
  - **Service-mediated method calls before engine creation are silently dropped.** The engine is created inside `start()`. Any `setX()` calls before that go to null. DSP params must be re-applied after `controller.start()` via `applyCurrentParams()`.
  - **`POST_NOTIFICATIONS` requires runtime grant on API 33+.** Declaring it in the manifest is not enough. Without runtime permission, `startForeground()` succeeds but the notification is invisible.
  - **Android notification `setSmallIcon` must use a monochrome drawable.** Adaptive launcher icons render as solid blobs in the status bar. Use a separate `res/drawable/ic_notification` (white-on-transparent).
  <!-- Add more operational knowledge as learned through trial-and-error. -->

## Current Status

- **Phase** — 6: Play Store Readiness — In Progress
- **Focus** — Build config, signing, and asset prep for Google Play submission
- **Blocked/Broken** — None

## Phase 1: Core Playback — Complete

5 steps, 15 unit tests (T1–T7, T6a–h), M1–M9 manual verification passed. 10 decisions closed (D-6–D-15). See DEVLOG.md §Phase 1.

## Phase 2: Color Engine — Complete

7 steps, 23 unit tests (T8–T20 + variants), M10–M20 manual verification passed. 5 decisions closed (D-16–D-20). See DEVLOG.md §Phase 2.

## Phase 3: Productization — Complete

6 steps, 13 unit tests (T21–T30, T25b, T31), M21–M30 manual verification passed. 6 decisions closed (D-21–D-25). See DEVLOG.md §Phase 3.

## Phase 4: Background Robustness — Complete

5 steps, 26 service tests + 11 new VM/service tests (T32–T36, T37 = build verification), M31–M39 manual verification passed. 5 decisions closed (D-26–D-30). See DEVLOG.md §Phase 4.

## Phase 5: Secondary Polish — Complete

6 steps + review fixes, 22 new unit tests (T38–T48 + variants), M41–M55 manual verification passed. 5 decisions closed (D-31–D-35), D-5/D-33 updated. See DEVLOG.md §Phase 5.

## Phase 6: Play Store Readiness

**Goal** — Prepare the app for Google Play Store submission: update SDK targets to meet current policy, enable release optimizations, configure signing, and add missing icon assets.

### Step 1 — Bump compileSdk and targetSdk to 35 — Complete
- Updated `compileSdk = 35` and `targetSdk = 35` in `app/build.gradle.kts`.
- AGP 8.7.3 supports compileSdk 35 — no version bump needed.
- Full test suite: 101 tests, 0 failures.
- **M56**: Pending on-device verification (build compiles and tests pass).

### Step 2 — Enable R8 minification and resource shrinking — Complete
- Set `isMinifyEnabled = true` and `isShrinkResources = true` in `app/build.gradle.kts` release block.
- No ProGuard rules needed — app uses no reflection, serialization, or dynamic class loading.
- `assembleRelease` builds successfully with R8 (`minifyReleaseWithR8`) and resource shrinking (`shrinkReleaseRes`).
- `lintVitalRelease` passed with no issues.
- Release unit tests: 101 tests, 0 failures.
- **M57**: Pending on-device release build verification.

### Step 3 — Add round icon variant — Complete
- Created `mipmap-anydpi-v26/ic_launcher_round.xml` (adaptive icon matching `ic_launcher.xml`).
- Added `android:roundIcon="@mipmap/ic_launcher_round"` to `<application>` in `AndroidManifest.xml`.
- Debug build compiles successfully.
- **M58**: Pending visual verification on circle-mask launchers (Pixel, Samsung OneUI).

### Step 4 — Configure release signing
- Generate release keystore via `keytool` (RSA 2048-bit, 10000-day validity).
- Add `signingConfigs` block in `app/build.gradle.kts` reading credentials from `gradle.properties`.
- Add keystore path and credentials to local `gradle.properties` (not committed to version control).
- Verify `.gitignore` excludes `*.jks` and `local.properties`.
- Build signed release AAB.
- **M59**: `.\gradlew.bat bundleRelease` produces a signed AAB. `jarsigner -verify` confirms valid signature.

### Step 5 — Create privacy policy and store listing assets
- Write a minimal privacy policy (no data collection, no network, local prefs only) and host at a public URL.
- Generate 512×512 hi-res icon PNG from existing launcher foreground.
- Prepare 1024×500 feature graphic.
- Capture 2+ phone screenshots (main screen playing, settings screen).
- Draft short description (≤80 chars) and full description (≤4000 chars).
- **M60**: All Play Console required assets are ready for upload.

### Step 6 — Final release validation
- Run full test suite one last time.
- Build final signed release AAB.
- Install on physical device via `bundletool`.
- Execute full manual test pass: play, stop, all timer durations, color sweep, texture/drift/stereo extremes, overnight stability, audio focus interruption, notification-denied mode, task-removal behavior.
- **M61**: Release AAB passes all checks and is ready for Play Console upload.
