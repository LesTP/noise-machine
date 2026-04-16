---
name: phase-complete
description: Phase completion checklist — run after review issues are resolved
---

Execute the phase completion protocol:

1. Run phase-level tests and confirm they pass
2. Update DEVLOG with phase completion entry
3. DEVLOG learning review — scan this phase's entries for trial-and-error patterns (multiple attempts to resolve something). Extract prescriptive one-liner summaries and propose additions to DEVPLAN Gotchas. Show me before writing.
4. Scan DEVLOG for Contract Changes markers. List affected upstream documents and flag what needs propagation.
5. Log review — review iteration logs (summary.log, transcripts) for this phase. Identify patterns: repeated tool failures, wasted turns, behavioral issues. Promote findings to DEVPLAN Gotchas.
6. DEVPLAN cleanup — reduce completed phase to a one-line summary with DEVLOG reference. Deduplicate between DEVPLAN and DEVLOG, keeping DEVPLAN minimal. If DEVLOG exceeds ~500 lines, archive completed module entries to `DEVLOG_archive.md`.
7. Update the current module's Status in ARCHITECTURE.md's Implementation Sequence table. Format: "Phase N complete" after each phase, or "Complete" if this was the module's final phase. Reset DEVPLAN frontmatter for the next phase (or clear it if this was the module's final phase).
8. Present summary of everything done and everything needing confirmation.

**If autonomous:**
Commit. Exit with ESCALATE — human audits before next phase begins.

**If not autonomous** (default):
Do not commit. Wait for my explicit confirmation.
