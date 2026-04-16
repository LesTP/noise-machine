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
