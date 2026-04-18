# Autonomous Loop Learnings

<!-- Template for capturing methodology observations during autonomous development.
     Fill sections as patterns emerge. This document is for post-project analysis
     and methodology improvement — not for the worker or orchestrator to read during execution.

     Update after each batch of iterations or at phase boundaries. -->

Observations from running the autonomous development loop on Noise Machine. Captures infrastructure bugs, agent behavior patterns, framework observations, cost analysis, and human interventions for post-project writeup.

---

## Infrastructure Bugs

<!-- Bugs in the loop infrastructure (run-iteration.sh, parsers, tooling) — not in project code. -->

| # | Iter | Issue | Fix | Impact |
|---|------|-------|-----|--------|
| 1 | codexbot 109 | Codex worker OOM: `~/.codex/logs_1.sqlite` (97 MB on disk) causes Codex CLI to allocate 13+ GB RAM at startup. Linear growth at ~1.5 GB/30s until container cgroup limit hit. Three consecutive OOM kills crashed the entire TG bot service. | (1) Deleted `logs_1.sqlite` — Codex recreates fresh. (2) Added 20 MB size guard to `run-iteration.sh` — auto-deletes before each run. (3) Bumped container memory 12→14 GB. (4) Installed memory watchdog (cron, Telegram alert at 80%). (5) Installed `memprofile.sh` for diagnostics. | Three crashes, ~1 hour debugging. Root cause: Codex CLI deserializes full session history into memory — 97 MB on disk inflates ~130x. Guard in `run-iteration.sh` prevents recurrence. Cross-project: affects any project using Codex workers on the same container. |

## Agent Behavior Patterns

<!-- Recurring agent behaviors — both good and bad. Track frequency and mitigations. -->

| # | Pattern | Frequency | Iterations | Mitigation |
|---|---------|-----------|------------|------------|
| 1 | | | | |

## Framework Observations

### What worked well
<!-- Patterns from the governance framework that produced good outcomes. -->

### What didn't work
<!-- Framework patterns that caused friction, wasted work, or needed workarounds. -->

### Surprises
<!-- Unexpected outcomes — positive or negative. -->

## Cost Analysis

### Per iteration
| Iter | Type | Module | Cost | Turns | Duration | Notes |
|------|------|--------|------|-------|----------|-------|
| 1 | | | | | | |

### Per module
| Module | Iterations | Total Cost | Lines of Code | Tests | Cost/Line |
|--------|-----------|------------|---------------|-------|-----------|
| | | | | | |

### By activity type
| Activity | Avg Cost | Avg Turns | Avg Duration |
|----------|----------|-----------|--------------|
| Planning | | | |
| Coding | | | |
| Review/Complete | | | |

### Cost scaling observations
<!-- Notes on what drives cost up or down across modules. -->

## Human Interventions

<!-- Every time a human had to step in during autonomous execution. -->

| # | Iter | What | Why | Could it be automated? |
|---|------|------|-----|----------------------|
| 1 | | | | |

## Efficiency Analysis

### Token overhead sources — loop iterations
<!-- Sources of wasted tokens within worker iterations. -->
| Source | Turns wasted/iter | Est. cost/iter | Fix |
|--------|-------------------|----------------|-----|
| | | | |

### Token overhead sources — orchestrator session
<!-- Sources of token waste in the orchestrator session. -->
| Source | Tokens consumed | Fix |
|--------|----------------|-----|
| | | |

### Implemented fixes
<!-- Track which efficiency improvements have been applied. -->
- [ ] Fix description

---

*Last updated: 2026-04-17*
