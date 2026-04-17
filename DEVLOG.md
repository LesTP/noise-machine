# Noise Machine — Development Log

<!-- Chronological record of what happened during development.
     Each step gets a structured entry. This is the audit trail.

     Archival rule: When this file exceeds ~500 lines, move completed
     module entries to DEVLOG_archive.md during phase completion cleanup.
     Add a boundary marker: <!-- Entries above archived from Module N, YYYY-MM-DD --> -->

## Module 1: core-playback

### Phase 1: Core Playback

### Phase Plan: Core Playback
- **Mode:** Discuss
- **Outcome:** complete
- **Contract changes:** none

Broke Phase 1 into 5 steps covering the Build regime:
1. Android project scaffold (Gradle, Compose, min API 26)
2. NoiseSource (allocation-free white-noise generator + unit tests)
3. AudioEngine (AudioTrack 16-bit PCM 44100Hz stereo, render thread, start/stop)
4. Compose UI + ViewModel (Play/Stop button, PlaybackState)
5. End-to-end wiring and integration tests

Test spec defined: 7 tests covering NoiseSource statistics, allocation-freedom, value range, AudioEngine lifecycle, rapid toggle safety, ViewModel state transitions, and build verification.

Provisional decisions queued: min API 26 (D-6), sample rate 44100 Hz (D-7), buffer size 2× minimum (D-8) — to be confirmed during execution.

### Step 1: Android project scaffold
- **Mode:** Code
- **Outcome:** complete — T7 passed (`assembleDebug` BUILD SUCCESSFUL in 3m 58s)
- **Contract changes:** none

Wrote the Gradle scaffold (settings, root + app build scripts, `gradle.properties`, `gradle/libs.versions.toml` version catalog), generated the Gradle wrapper pinned to 8.10.2, and authored a minimal Compose-based `MainActivity` that renders "Noise Machine" centered on a Material 3 `Scaffold`. `AndroidManifest.xml`, `strings.xml`, `themes.xml`, and empty `backup_rules.xml` / `data_extraction_rules.xml` round out the app module. `local.properties` (gitignored) points at the local Android SDK; `.gitignore` expanded to cover `.gradle/`, `build/`, `.idea/`, `*.iml`, `local.properties`, and related Android/Gradle ephemera.

Two decisions closed during this step — see DECISIONS.md:
- **D-6** — min API 26; compileSdk + targetSdk 34; Java 17.
- **D-9** — toolchain pins: AGP 8.7.3, Gradle 8.10.2, Kotlin 2.0.21, Compose BOM 2024.10.01, AndroidX Core-KTX 1.13.1, Activity-Compose 1.9.3, Lifecycle-Runtime-KTX 2.8.7, Material 3 (from BOM), JUnit 4.13.2.

Minor notes / gotchas:
- First `assembleDebug` took 3m 58s (cold wrapper bootstrap + dependency download + first compilation); subsequent configuration-cache-enabled builds should be much faster.
- Benign warning during packaging: `Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so` — expected on Windows without NDK `strip` on PATH; does not affect the debug APK.
- `org.gradle.daemon` idle shutdown on Windows can appear to "hang" the foreground shell after BUILD SUCCESSFUL; actual build time is the number reported by Gradle, not the wall-clock of the invoking shell.

### Step 2: NoiseSource
- **Mode:** Code
- **Outcome:** complete — T1, T2, T3 passed (`testDebugUnitTest` BUILD SUCCESSFUL in 1m 17s; 3 tests, 0 failures, 0 errors)
- **Contract changes:** none

Implemented `app/src/main/java/com/noisemachine/app/audio/NoiseSource.kt` as an allocation-free uniform-`[-1.0f, 1.0f)` white-noise generator backed by `java.util.SplittableRandom`. The hot path is a single `while (i < length)` loop that writes `(rng.nextDouble() * 2.0 - 1.0).toFloat()` into the caller-supplied `FloatArray` — no per-sample object creation, no boxing, no synchronized access (SplittableRandom is single-threaded by design, which matches the per-render-thread ownership model called out in CLAUDE.md).

Unit tests in `app/src/test/java/com/noisemachine/app/audio/NoiseSourceTest.kt` cover the three Phase-1 NoiseSource criteria from the DEVPLAN test spec:
- **T1** — 10k-sample buffer with seed `0xC0FFEE`: empirical `mean ∈ [-0.05, 0.05]`, `variance ∈ [0.30, 0.36]` (theoretical variance for uniform `[-1, 1]` is `1/3 ≈ 0.3333`).
- **T2** — allocation-free hot loop: warm up 2,000 fills, GC, snapshot heap, run 10,000 × 1,024-sample fills (~10M samples), GC, assert heap delta `< 256 KB`. A per-sample allocation would balloon the delta into the MB range.
- **T3** — every sample of a 50,000-sample buffer is in `[-1.0, 1.0]` (asserted via raw indexed loop to avoid iterator boxing in the assertion).

One decision closed during this step:
- **D-10** — Noise RNG: use `java.util.SplittableRandom` (allocation-free `nextDouble()`, no synchronized seed update, available since API 24 — well below our minSdk 26). Rejected `java.util.Random` (synchronized CAS on every call) and a hand-rolled `XorShift64` (not needed once SplittableRandom met the allocation-freedom bar). See DECISIONS.md.

Operational note added to DEVPLAN Cold Start gotchas: cold `:app:testDebugUnitTest` is ~75 s (daemon spin-up + Kotlin compile); warm runs are seconds.
