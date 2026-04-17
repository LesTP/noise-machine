---
module: core-playback
phase: 3
phase_title: Productization
step: 3 of 6
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
  - **First build is slow.** Cold `.\gradlew.bat assembleDebug` takes ~4 min (wrapper bootstrap + dep download). Warm incremental builds should be well under 30 s with configuration cache.
  - **Gradle daemon idle-hangs the invoking shell.** After `BUILD SUCCESSFUL` on Windows, the foreground `gradlew.bat` process often sits for minutes waiting for daemon idle-shutdown. Trust the Gradle-reported build time, not the shell's wall-clock. Use `--no-daemon` for scripted builds or `.\gradlew.bat --stop` to kill daemons manually.
  - **`libandroidx.graphics.path.so` strip warning** is benign (missing NDK `strip` on Windows PATH); ignore it.
  - **Use the wrapper, not system Gradle.** System `gradle` is 9.4.0, incompatible with AGP 8.7. Always call `.\gradlew.bat` or `./gradlew`.
  - **`local.properties` is per-machine and gitignored.** If a fresh clone fails to find the SDK, write `sdk.dir=...` there.
  - **Config cache can mask stale state.** If a build behaves weirdly after a big change, try `--no-configuration-cache` once.
  - **`testDebugUnitTest` cold cost.** With config cache warm but no daemon, a clean unit-test run takes ~75 s (daemon startup + Kotlin compile). Subsequent runs reuse the daemon and are seconds.
  - **AudioFormat constant naming.** It's `AudioFormat.ENCODING_PCM_16BIT` (no underscore between `16` and `BIT`); `ENCODING_PCM_FLOAT` *does* have the underscore. Easy to get wrong by analogy.
  - **`val state by flow.collectAsStateWithLifecycle()` needs `import androidx.compose.runtime.getValue`.** Without the explicit `getValue` import, Kotlin can't resolve the `by`-delegate operator on `androidx.compose.runtime.State<T>` and the build fails with `has no method 'getValue(…)'`.
  - **`ViewModel.onCleared()` is not enough to stop audio without a foreground service.** The Activity can linger after `onStop()` without triggering `onCleared()`. Use `LifecycleEventEffect(ON_STOP)` to stop playback explicitly. Phase 4's foreground service replaces this.
  - **ParameterSmoother rate mismatch.** If calling `next()` per-buffer instead of per-sample, use `nextBlock(bufferSize)` to advance correctly. Calling `next()` once per buffer makes the effective time constant ~bufferSize× slower (~51 s instead of 50 ms at 1024-frame buffers).
  - **`@Volatile` on local variables.** Kotlin does not allow `@Volatile` on local variables — it's only valid on class properties. Use `AtomicBoolean` for cross-thread test state, or a regular `var` if the test is single-threaded with `Thread.join()`.
  - **IIR denormal stall (unconfirmed).** Audio stopped after ~10–15 min on emulator with HAL I/O errors (`pcm_writei failed: I/O error` from `ranchu` audio service). Initially suspected biquad denormal floats, but logcat shows the failure is in the emulator's virtual audio driver, not our app code. May be emulator-specific. Test on hardware device in Phase 4 before adding denormal protection.
  - **`StandardTestDispatcher` needs `runCurrent()` after `advanceTimeBy()`.** Without it, coroutine continuations that resume at the advanced time are queued but not dispatched, so state changes after `delay()` aren't visible.
  <!-- Add more operational knowledge as learned through trial-and-error. -->

## Current Status

- **Phase** — 3: Productization
- **Focus** — Step 3.3: TimerState + countdown coroutine + timer→fade-out integration
- **Blocked/Broken** — None

## Phase 1: Core Playback — Complete

5 steps, 15 unit tests (T1–T7, T6a–h), M1–M9 manual verification passed. 10 decisions closed (D-6–D-15). See DEVLOG.md §Phase 1.

## Phase 2: Color Engine — Complete

7 steps, 23 unit tests (T8–T20 + variants), M10–M20 manual verification passed. 5 decisions closed (D-16–D-20). Known issue: IIR denormal stall after ~10–15 min on emulator (deferred to Phase 4). See DEVLOG.md §Phase 2.

## Phase 3: Productization

**Regime:** Build
**Scope:** Timer, fade-in/fade-out, Settings skeleton, persistence.

**Known tension:** Without a foreground service (Phase 4), the timer cannot survive Activity stop (screen-off, Home press). Phase 3 delivers the timer logic; Phase 4 makes it survive backgrounding.

### Steps

| Step | Scope | Tests |
|------|-------|-------|
| **3.1** | Master gain smoother in AudioEngine + `PlaybackController.setGain()` + render-loop gain multiply | T21, T22 |
| **3.2** | PlaybackState expansion (FadingIn/FadingOut) + ViewModel fade-in on play, fade-out on stop | T23, T24, T25 |
| **3.3** | TimerState + countdown coroutine + timer→fade-out integration | T26, T27, T28 |
| **3.4** | Persistence via DataStore (Color + timer duration) | T29, T30 |
| **3.5** | Settings screen (fade duration controls) + timer chip on main screen + navigation | T31 |
| **3.6** | End-to-end wiring + manual on-device verification | M21–M30 |

### Test Spec

**Unit Tests:**

| Test | Verifies |
|------|----------|
| **T21** | Gain application: engine at gain=0.5 produces samples at ~half amplitude vs gain=1.0 (via FakeSink capture) |
| **T22** | Gain ramp convergence: gain smoother reaches target within 1% after 5τ |
| **T23** | Fade-in state sequence: `onPlayClicked()` → `FadingIn` → `Playing` (FakeController + TestDispatcher) |
| **T24** | Fade-out state sequence: `onStopClicked()` while Playing → `FadingOut` → `Idle` |
| **T25** | Fade-out completion: `controller.stop()` called only after gain reaches near-zero threshold (~0.001) |
| **T26** | Timer countdown: Armed(60000) ticks to Armed(59000) after 1s (TestDispatcher time advancement) |
| **T27** | Timer expiry: countdown reaches 0 → triggers fade-out → then `controller.stop()` |
| **T28** | Timer cancel: pressing stop while Armed cancels countdown coroutine, timer resets to Off |
| **T29** | Persistence: saved Color value restored on fresh ViewModel construction |
| **T30** | Persistence: saved timer duration restored on fresh ViewModel construction |
| **T31** | Settings screen build: `assembleDebug` succeeds with Settings composable wired |

**Manual Tests:**

| Test | Verifies |
|------|----------|
| **M21** | Play produces audible fade-in (gradual volume increase from silence) |
| **M22** | Stop produces audible fade-out (gradual decrease, then silence) |
| **M23** | Timer chip visible on main screen below Play/Stop |
| **M24** | Timer selection (15m/30m/1h/2h) arms countdown |
| **M25** | Timer countdown visible in chip (updating text) |
| **M26** | Timer expiry produces smooth fade-out then silence |
| **M27** | Color value persists across app kill + relaunch |
| **M28** | Timer selection persists across app kill + relaunch |
| **M29** | Settings screen accessible from main screen, shows fade duration options |
| **M30** | Settings changes (fade durations) take effect on next play/stop |

### Decisions to Queue

| ID | Question | Leaning |
|----|----------|---------|
| **D-21** | Fade mechanism: second `ParameterSmoother` for master gain in AudioEngine, applied post-GainSafety? | Yes — reuses proven smoother; gain ≤ 1.0 preserves clip guarantee |
| **D-22** | Timer architecture: separate `StateFlow<TimerState>` parallel to `PlaybackState`, or merge into one sealed hierarchy? | Separate — avoids state explosion, timer is orthogonal to play/stop |
| **D-23** | Persistence: `DataStore<Preferences>` vs `SharedPreferences`? | DataStore — modern, coroutine-native, no main-thread risk |
| **D-24** | Settings navigation: simple `var showSettings` state toggle vs `NavHost`? | State toggle — two screens, no deep linking needed, avoids extra dep |
| **D-25** | Fade default durations: fade-in 2s, fade-out 5s? | Reasonable defaults; configurable in Settings |

### New Dependencies

| Library | Purpose |
|---------|---------|
| `androidx.datastore:datastore-preferences` | Persist Color + timer values |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | Test timer countdown with TestDispatcher |
