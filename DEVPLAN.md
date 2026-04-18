---
module: core-playback
phase: 4
phase_title: Background Robustness
step: 5 of 5
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
  - **`NativePaint.color` collides with Compose parameter names.** Inside a composable with a `color` parameter, `NativePaint().apply { color = ... }` resolves to the composable's parameter. Use `setColor(value)` instead.
  - **`ViewModel.onCleared()` is `protected` in the base class.** Removing the explicit `public` makes it inaccessible to tests. Keep `public override` if tests call `onCleared()` directly.
  - **`rememberSaveable` ignores initial value after first composition.** On config change or process death, `rememberSaveable` restores the saved value, not the parameter. Compute initial values from ViewModel state at composition time.
  <!-- Add more operational knowledge as learned through trial-and-error. -->

## Current Status

- **Phase** — 3: Productization — Complete (review_done: true)
- **Focus** — Phase 4: Background Robustness (next)
- **Blocked/Broken** — None

## Phase 1: Core Playback — Complete

5 steps, 15 unit tests (T1–T7, T6a–h), M1–M9 manual verification passed. 10 decisions closed (D-6–D-15). See DEVLOG.md §Phase 1.

## Phase 2: Color Engine — Complete

7 steps, 23 unit tests (T8–T20 + variants), M10–M20 manual verification passed. 5 decisions closed (D-16–D-20). Known issue: IIR denormal stall after ~10–15 min on emulator (deferred to Phase 4). See DEVLOG.md §Phase 2.

## Phase 3: Productization — Complete

6 steps, 13 unit tests (T21–T30, T25b, T31), M21–M30 manual verification passed. 6 decisions closed (D-21–D-25). See DEVLOG.md §Phase 3.

## Phase 4: Background Robustness

**Regime:** Build
**Scope:** Foreground service owns AudioEngine lifecycle, persistent notification with stop action, timer survives backgrounding, audio focus, task-removed cleanup.

**Motivation:** Without a foreground service, `LifecycleEventEffect(ON_STOP)` kills audio on Home press or screen-off. Phase 4 replaces this with a service that owns `AudioEngine` lifecycle, making the app usable as a sleep app.

### Steps

| Step | Scope | Tests |
|------|-------|-------|
| **4.1** | `PlaybackService` skeleton: foreground service + notification channel + `startForeground()` + `FOREGROUND_SERVICE`/`POST_NOTIFICATIONS` permissions + `foregroundServiceType="mediaPlayback"`. Engine starts/stops via service intents. No ViewModel wiring yet. | T32, T33 |
| **4.2** | Service ↔ ViewModel binding: VM binds to service via `Binder`, delegates `start/stop/setColor/setGain/snapGain`. Remove `LifecycleEventEffect(ON_STOP)` stop behavior. Factory updated. | T34 |
| **4.3** | Timer migration: move countdown coroutine from `viewModelScope` to service scope. VM observes timer state from service via binding. | T35 |
| **4.4** | Notification stop action + audio focus + `onTaskRemoved`. Plain notification with stop button. `AudioFocusRequest(AUDIOFOCUS_GAIN)` on start, release on stop. `onTaskRemoved` calls `stopSelf()`. | T36, T37 |
| **4.5** | End-to-end manual verification: screen-off, Home press, task kill, timer while backgrounded, re-open state sync, multi-hour run. | M31–M40 |

### Test Spec

**Unit / Integration Tests:**

| Test | Verifies |
|------|----------|
| **T32** | Service starts foreground with notification |
| **T33** | Service stops engine on `stopSelf()` / `onDestroy()` |
| **T34** | ViewModel delegates play/stop to bound service |
| **T35** | Timer countdown continues when ViewModel is cleared (service-scoped coroutine) |
| **T36** | Notification stop action triggers service stop + fade-out |
| **T37** | `assembleDebug` succeeds with permission declaration + runtime check |

**Manual Tests:**

| Test | Verifies |
|------|----------|
| **M31** | Audio continues after pressing Home |
| **M32** | Audio continues after screen off |
| **M33** | Notification visible with stop action |
| **M34** | Notification Stop triggers fade-out + silence |
| **M35** | Timer countdown continues after backgrounding |
| **M36** | Timer expiry while backgrounded triggers fade-out + silence |
| **M37** | Swiping app from recents stops audio (`onTaskRemoved`) |
| **M38** | Re-opening app while playing shows correct UI state |
| **M39** | Color slider changes apply while backgrounded (via notification return) |
| **M40** | Multi-hour uninterrupted playback (≥2h) without glitches |

### Decisions

| ID | Question | Status |
|----|----------|--------|
| **D-26** | Service binding: `Binder` vs intent-only? | **Closed** — Binder. VM needs synchronous access to `isPlaying`, `setColor()`, `setGain()`. |
| **D-27** | Notification style: `MediaStyle` vs plain? | **Closed** — Plain notification with stop button. No media session needed; minimal design. |
| **D-28** | Timer ownership: service vs VM? | **Closed** — Service. Timer must survive Activity destruction. |
| **D-29** | `onTaskRemoved`: stop or keep playing? | **Closed** — Stop. Swiping from recents = explicit dismissal. |
| **D-30** | Audio focus handling? | **Closed** — Yes. `AUDIOFOCUS_GAIN` on start, release on stop. Standard audio app behavior. |

### New Dependencies

| Library | Purpose |
|---------|---------|
| *(none)* | Foreground service, notification, binding are all Android SDK |

### Known Risks

- **Robolectric service testing** — `startForeground()` may not work under Robolectric. Strategy: keep service thin (Android glue only), extract orchestration logic into JVM-testable classes, rely on M31–M40 for service lifecycle.
- **Emulator denormal stall** — Phase 2 known issue (audio stops after ~10–15 min). M40 multi-hour test will confirm.
- **Android 14+ foreground service type** — requires `android:foregroundServiceType="mediaPlayback"` in manifest.
