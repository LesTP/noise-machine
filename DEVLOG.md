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
