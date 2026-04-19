---
module: core-playback
phase: 5
phase_title: Secondary Polish
step: complete
mode: Review
blocked: null
regime: Build
review_done: true
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
  - **`ViewModel` must sync state from service at init.** When a VM binds to an already-running foreground service, `_state` must query `controller.isPlaying` rather than defaulting to `Idle`. Otherwise Activity recreation (screen-off, config change) shows stale UI while audio continues.
  - **Service-mediated method calls before engine creation are silently dropped.** If the service creates the engine inside `start()`, any `snapGain()`/`setGain()` calls made before `start()` go to a null engine. Buffer pending values and apply them when the engine is created.
  - **`POST_NOTIFICATIONS` requires runtime grant on API 33+.** Declaring it in the manifest is not enough. Without runtime permission, `startForeground()` succeeds but the notification is invisible.
  - **External stop triggers must route through the VM's state machine.** Notification intents and audio focus loss that call `service.stop()` directly bypass the VM's fade-out orchestration and leave `_state` stale. Use a callback (`onStopRequested`) wired from the composable.
  - **DSP params must be re-applied after engine recreation.** ViewModel.init sets params while engine is null (no-op). After `controller.start()` creates a fresh engine, call `applyCurrentParams()` to push color/texture/stereoWidth/microDriftDepth to the new engine.
  - **Android notification `setSmallIcon` must use a monochrome drawable.** Adaptive launcher icons render as solid blobs in the status bar. Create a separate `res/drawable/ic_notification` (white-on-transparent) at all density buckets.
  <!-- Add more operational knowledge as learned through trial-and-error. -->

## Current Status

- **Phase** — 5: Secondary Polish — Complete
- **Focus** — Phase completion
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
