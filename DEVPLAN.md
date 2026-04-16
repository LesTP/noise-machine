---
module: core-playback
phase: 1
phase_title: Core Playback
step: 0 of 0
mode: Discuss
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
- **Gotchas** — <!-- Add operational knowledge learned through trial-and-error here.
  This is the most valuable section for autonomous agents. Examples:
  - Build commands and their quirks
  - Shell workarounds for specific environments
  - Patterns that aren't obvious from the code
  - Common failure modes and how to avoid them -->

## Current Status

- **Phase** — Not started
- **Focus** — Initial setup
- **Blocked/Broken** — None

## Phase 1: Core Playback

<!-- Break into steps during the Phase Plan action. Example:

### Steps
1. [ ] Step description
2. [ ] Step description
3. [ ] Step description

### Test Spec (Build regime)
- Test: [what to verify]
- Expected: [observable outcome]

-->

<!-- As phases complete, reduce them to one-line summaries:
## Phase 1: Setup — Complete (see DEVLOG 2026-04-01)
-->
