# Noise Machine

A minimal Android sleep-noise app that generates continuously tunable masking noise for long unattended playback.

## What It Does

Noise Machine generates real-time stereo noise shaped by a single **Color** slider — from bright white noise through balanced pink to deep brown. No prerecorded audio, no internet connection, no data collection. Just noise.

### Features

- **Color slider** — continuously shapes the spectral character from bright/airy to deep/soft
- **Sleep timer** — auto-stop with fade-out (15m / 30m / 1h / 2h)
- **Smooth fades** — configurable fade-in and fade-out durations
- **Background playback** — foreground service keeps audio playing with the screen off
- **Texture control** — smooth to grainy (Settings)
- **Stereo width** — mono to decorrelated stereo (Settings)
- **Micro drift** — subtle slow tonal wandering for a more organic feel (Settings)
- **No ads, no tracking, no network access**

## How It Works

The audio engine runs a real-time DSP pipeline on a dedicated high-priority thread:

```
White noise source
  → Spectral shaping (2 cascaded biquad shelving filters)
  → Texture (zero-order hold decimation)
  → DC blocking + gain compensation + hard clip
  → Master gain (fade envelope)
  → Stereo decorrelation (first-order all-pass)
  → AudioTrack output
```

All parameter changes go through exponential smoothers — no clicks, pops, or zipper noise. The render loop is allocation-free for glitch-free multi-hour playback.

## Building

Requires Android SDK with API 35 and JDK 17.

```bash
# Debug build
./gradlew assembleDebug

# Run tests (101 unit tests)
./gradlew testDebugUnitTest

# Signed release bundle (requires keystore.properties)
./gradlew bundleRelease
```

## Tech Stack

- Kotlin
- Jetpack Compose (Material 3)
- ViewModel + StateFlow
- Foreground Service with notification
- AudioTrack (16-bit PCM, 44100 Hz, stereo)
- No third-party dependencies beyond AndroidX

## Privacy

Noise Machine works entirely offline. No data collection, no analytics, no network access. All settings are stored locally via SharedPreferences.

[Privacy Policy](https://lestp.github.io/privacypolicy_noisemachine.html)

## License

Copyright 2026 The Moving Finger Studios. All rights reserved.
