---
module: core-playback
phase: 5
phase_title: Secondary Polish
step: 5 of 6
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
  - **`ViewModel` must sync state from service at init.** When a VM binds to an already-running foreground service, `_state` must query `controller.isPlaying` rather than defaulting to `Idle`. Otherwise Activity recreation (screen-off, config change) shows stale UI while audio continues.
  - **Service-mediated method calls before engine creation are silently dropped.** If the service creates the engine inside `start()`, any `snapGain()`/`setGain()` calls made before `start()` go to a null engine. Buffer pending values and apply them when the engine is created.
  - **`POST_NOTIFICATIONS` requires runtime grant on API 33+.** Declaring it in the manifest is not enough. Without runtime permission, `startForeground()` succeeds but the notification is invisible.
  - **External stop triggers must route through the VM's state machine.** Notification intents and audio focus loss that call `service.stop()` directly bypass the VM's fade-out orchestration and leave `_state` stale. Use a callback (`onStopRequested`) wired from the composable.
  <!-- Add more operational knowledge as learned through trial-and-error. -->

## Current Status

- **Phase** — 5: Secondary Polish — In progress (step 0 of 6, planning complete)
- **Focus** — Settings screen expansion: Texture, Stereo, Micro-drift, Fade durations, About, POST_NOTIFICATIONS
- **Blocked/Broken** — None

## Phase 1: Core Playback — Complete

5 steps, 15 unit tests (T1–T7, T6a–h), M1–M9 manual verification passed. 10 decisions closed (D-6–D-15). See DEVLOG.md §Phase 1.

## Phase 2: Color Engine — Complete

7 steps, 23 unit tests (T8–T20 + variants), M10–M20 manual verification passed. 5 decisions closed (D-16–D-20). See DEVLOG.md §Phase 2.

## Phase 3: Productization — Complete

6 steps, 13 unit tests (T21–T30, T25b, T31), M21–M30 manual verification passed. 6 decisions closed (D-21–D-25). See DEVLOG.md §Phase 3.

## Phase 4: Background Robustness — Complete

5 steps, 26 service tests + 11 new VM/service tests (T32–T36, T37 = build verification), M31–M39 manual verification passed. 5 decisions closed (D-26–D-30). See DEVLOG.md §Phase 4.

## Phase 5: Secondary Polish

**Regime:** Build (Steps 1–5) + Refine (Step 6)

Settings screen expansion with functional DSP controls for all deferred parameters, plus POST_NOTIFICATIONS fix.

**Scope:**
1. **Texture** — smooth ↔ grainy (zero-order hold decimation) — slider in Settings
2. **Stereo width** — mono ↔ subtle spread (first-order all-pass decorrelation) — checkbox/slider in Settings
3. **Micro-variation** — very slow spectral drift (slow LFO modulating Color offset) — depth slider in Settings
4. **Fade durations** — configurable fade-in/fade-out lengths (currently read-only, make interactive)
5. **About section** — app name, version, brief description
6. **POST_NOTIFICATIONS** — runtime permission dialog on API 33+

**DSP pipeline after Phase 5:**
```
NoiseSource.fill(buf)
  → SpectralShaper.process(buf, color + microDriftOffset)
  → TextureShaper.process(buf, texture)
  → GainSafety.process(buf, color)
  → masterGain multiply
  → StereoStage.processToStereo(mono, stereo, width)
  → AudioTrack.write
```

### Step 1: TextureShaper DSP (Build)
- `TextureShaper.kt` — variable-rate zero-order hold (sample decimation). At texture=0, passthrough. At texture=1, hold each sample for MAX_HOLD frames (grainy). Allocation-free, not thread-safe.
- `TextureShaper` inserts between SpectralShaper and GainSafety per ARCHITECTURE.md.
- Tests: T38 (passthrough at texture=0), T39 (stepped output at texture=1), T40 (allocation-free).
- Close D-31.

### Step 2: StereoStage DSP (Build)
- `StereoStage.kt` — first-order all-pass filter on R channel for phase-based decorrelation. Takes mono FloatArray + width parameter, outputs interleaved stereo ShortArray (replaces `floatMonoToInt16Stereo`). At width=0, identical channels (D-5). At width>0, subtle spatial spread.
- Tests: T41 (identical L/R at width=0), T42 (different L/R at width>0), T43 (allocation-free + bounded).
- Close D-32.

### Step 3: MicroDrift DSP (Build)
- `MicroDrift.kt` — slow LFO (0.02–0.1 Hz) producing a small Color offset. Render thread calls `nextBlock(framesPerWrite)` to get the current offset. Depth parameter [0,1] scales the maximum offset (e.g., ±0.05 at depth=1). Uses a smoothed triangle or sine wave backed by a ParameterSmoother for depth changes.
- Tests: T47 (depth=0 → offset always 0), T48 (depth>0 → offset drifts over time).
- Close D-33.

### Step 4: Engine + controller integration (Build)
- Wire TextureShaper, StereoStage, and MicroDrift into AudioEngine's render loop.
- Add `setTexture(Float)`, `setStereoWidth(Float)`, `setMicroDriftDepth(Float)` to `PlaybackController`.
- Add `setFadeInMs(Long)`, `setFadeOutMs(Long)` to `PlaybackController` (or VM-level, since fade is VM-orchestrated).
- Smoothers for texture, stereo width, micro-drift depth (all ParameterSmoother instances).
- Replace inline `floatMonoToInt16Stereo` with `StereoStage.processToStereo`.
- Tests: T44 (full pipeline at 3 Color/Texture combos, bounded output).
- Close D-34.

### Step 5: Settings UI + persistence + About + permission (Build)
- Expand Settings screen: Texture slider, Stereo width toggle/slider, Micro-drift depth slider, Fade-in/fade-out duration pickers.
- About section: app name, version string, one-line description.
- Expand `PrefsStore` with `texture`, `stereoWidth`, `microDriftDepth`, `fadeInMs`, `fadeOutMs`.
- `PlaybackViewModel` restores new prefs at init, forwards changes to controller.
- POST_NOTIFICATIONS runtime permission: `rememberLauncherForActivityResult(RequestPermission)` in MainActivity, triggered on first play.
- Tests: T45 (assembleDebug compiles), T46 (POST_NOTIFICATIONS requested on API 33+).
- Close D-35.

### Step 6: Perceptual tuning + verification (Refine)
- On-device verification M41–M55.
- Tune texture hold range, stereo width range, micro-drift rate/depth.

### Test Spec

| ID | Component | Assertion |
|----|-----------|-----------|
| T38 | TextureShaper | texture=0 is passthrough — output matches input |
| T39 | TextureShaper | texture=1 produces stepped output — adjacent samples often equal (>50% repeats) |
| T40 | TextureShaper | Allocation-free (heap delta < 256 KB) |
| T41 | StereoStage | width=0 → identical L/R channels |
| T42 | StereoStage | width>0 → different L/R channels (cross-channel diff > 0) |
| T43 | StereoStage | Allocation-free + all output samples finite and bounded |
| T47 | MicroDrift | depth=0 → offset is always 0.0 |
| T48 | MicroDrift | depth>0 → offset is non-constant after enough samples |
| T44 | AudioEngine | Full pipeline Texture+Stereo+Drift — bounded output at 3 combos |
| T45 | Build | `assembleDebug` with full Settings UI compiles |
| T46 | Permission | POST_NOTIFICATIONS requested on API 33+ |

### Manual Verification

| ID | Check |
|----|-------|
| M41 | Texture slider visible and functional in Settings |
| M42 | Texture=0 smooth, Texture=1 audibly grainier |
| M43 | Texture changes don't cause large loudness swings |
| M44 | Texture is orthogonal to Color |
| M45 | Stereo produces subtle spread on headphones |
| M46 | Stereo off = mono (identical channels) |
| M47 | Micro-drift depth>0 produces slow, subtle tonal wandering |
| M48 | Micro-drift depth=0 is inaudible (no drift) |
| M49 | Fade-in duration adjustable in Settings, audibly different |
| M50 | Fade-out duration adjustable in Settings, audibly different |
| M51 | POST_NOTIFICATIONS dialog on API 33+ |
| M52 | About section shows app name + version |
| M53 | All settings persist across app kill + relaunch |
| M54 | Timer + fade + texture + stereo + drift all work together |
| M55 | Extended session (15+ min) — no glitches |

### Decisions Queued

| ID | Question | Leaning |
|----|----------|---------|
| D-31 | Texture DSP approach | Zero-order hold — orthogonal to Color, cheap, distinctive |
| D-32 | Stereo decorrelation method | First-order all-pass on R channel — preserves spectral character |
| D-33 | Micro-drift mechanism | Slow LFO (0.02–0.1 Hz) modulating Color offset, depth-scaled |
| D-34 | Fade duration configurability | Picker: 0s / 1s / 2s / 5s / 10s for each direction |
| D-35 | POST_NOTIFICATIONS flow | Request on first play; denied = invisible notification, playback works |
