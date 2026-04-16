# Noise Machine — Decision Log

<!-- Record non-trivial design and implementation decisions here.
     Use the full template for genuine design forks with trade-offs.
     For reactive decisions during Refine work, a one-line note in
     the DEVLOG is sufficient — don't over-use this file.

     Once Closed, don't reopen unless new evidence appears. -->

D-1: Continuous spectral shaping over multi-source mixing
Date: 2026-04-16 | Status: Closed
Priority: Critical
Decision: Use one white-noise base generator with continuous spectral shaping rather than crossfading between separate white/pink/brown generators.
Rationale: Produces one coherent noise family; supports smooth slider transitions; matches the one-slider UX; more efficient; avoids the "fake crossfade between distinct personalities" feel. Alternative considered: three-source mix with crossfade — rejected because it makes Color feel discontinuous and is harder to tune perceptually.
Revisit if: perceptual tuning proves unachievable with real-time IIR cascades and FFT-domain shaping becomes necessary.

D-2: Color slider drives multiple internal parameters
Date: 2026-04-16 | Status: Closed
Priority: Critical
Decision: The single visible Color slider maps to a coordinated vector of internal parameters (spectral tilt amount, HF attenuation, LF containment, output-level compensation, smoothing behavior).
Rationale: Keeps the UI minimal while avoiding the trap of a simple filter sweep, which would feel muffled at one end and sterile at the other. Alternative considered: expose tilt, HF, LF as separate sliders — rejected because it violates the minimal sleep-first UX.
Revisit if: users report the slider feels ambiguous, non-monotonic, or unintuitive.

D-3: Native media volume only — no in-app volume slider
Date: 2026-04-16 | Status: Closed
Priority: Important
Decision: Volume is controlled exclusively via the phone's hardware / system media volume controls. The app may apply an internal safety ceiling but exposes no visible gain control.
Rationale: Less UI clutter; users already understand hardware buttons; avoids redundant controls; aligns with the minimal sleep-first philosophy. Alternative considered: dedicated in-app volume slider — rejected as visual noise on a bedside-use main screen.
Revisit if: usability feedback indicates users cannot reach desired listening levels via system volume alone.

D-4: Foreground service for playback
Date: 2026-04-16 | Status: Closed
Priority: Critical
Decision: Playback runs in an Android Foreground Service with a persistent notification.
Rationale: Required for reliable long-session playback with screen off and during backgrounding; makes the app behave like a legitimate long-running audio app rather than an in-activity demo. Alternative considered: bound-service or activity-scoped playback — rejected because it cannot reliably survive backgrounding.
Revisit if: Android lifecycle APIs change in a way that offers a more appropriate primitive.

D-5: Restrained stereo in V1
Date: 2026-04-16 | Status: Closed
Priority: Nice-to-have
Decision: V1 stereo output is either identical or only very slightly decorrelated between channels. Width / motion / decorrelation controls are deferred to a later phase.
Rationale: Aggressive stereo width or motion is distracting and attention-grabbing — the opposite of what a sleep app should feel like. Alternative considered: prominent stereo width slider — rejected as misaligned with the calm/appliance product feel.
Revisit if: user feedback consistently asks for a subtle-width option that can be exposed without compromising the calm default.
