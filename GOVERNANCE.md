# Governance

Development process for asynchronous, multi-session collaboration with a stateless partner (AI or otherwise). Governing constraint: **minimize wasted work when each session starts cold.**

Documentation is the source of truth — do not rely on prior conversations. If something is ambiguous, **ask** (don't guess). Prioritize clarity over speed.

This process works identically for human-driven and autonomous development. Where behavior diverges, both paths are noted inline. The switch is the `autonomous: true` environment variable, passed in the loop prompt for autonomous execution.

---

## Concepts

### Work Regimes

Work falls along a spectrum based on **evaluability** — who can assess whether the output is correct.

**Build (AI-evaluable):** Correctness verifiable by tests, type checks, or objective criteria.
- Tests and acceptance criteria specified **before** implementation
- Large autonomous work chunks (full phases)
- Human reviews asynchronously after completion
- Decisions are architectural and durable

Examples: data models, algorithms, parsers, API contracts, integration wiring, build config.

**Refine (human-evaluable):** Correctness requires human perception or subjective judgment.
- Goals and constraints specified upfront; steps emerge iteratively
- Small increments shown to human frequently
- Human evaluates each increment synchronously
- Decisions are reactive and may reverse

Feedback loop: Show → React → Triage (fix now / fix later / needs decision) → Adjust → Repeat

Examples: visual design, interaction feel, audio quality, layout, naming, copy.

**Explore (decision-evaluable):** Goal is to make a decision, not produce shipping code.
- Output: a closed decision (using the decision template)
- Method: prototype alternatives, compare, evaluate
- Time-boxed (one session or explicit limit)

Examples: technology selection, A/B comparisons, architecture alternatives.

**Identifying the regime:** Ask "Can the implementer verify this is correct without showing it to someone?"
- Yes → Build. Specify deeply: functions, test cases, step-by-step plan.
- No → Refine. Specify goals and constraints only. Do NOT pre-specify values that depend on perception.
- Need to decide first → Explore. Time-box it, produce a decision, then Build or Refine.

Most features pass through multiple regimes: Explore → Build → Refine. Plan for the transitions.

### Work Modes

Each session operates in one mode at a time:

**1. Discuss (no code changes)**
- Every iteration **starts** here
- Determine scope, identify the work regime, specify accordingly
- Prioritize simplest solutions; check if existing code can be reused/extended
- Preserve existing architecture unless there's a clear reason to change it
- If context is missing, ask before proceeding
- **Ends with** a DEVPLAN update

**2. Code / Debug**
- **Code:** implement the plan from the discuss session
- **Debug:** propose a testable hypothesis first, then make changes
- Switching between code and debug within a session is expected

**3. Review**
- Goal: improve existing code, not write new features
- **Priority #1:** preserve existing functionality
- **Priority #2:** simplify and reduce code
- Confirm architecture alignment (no drift from spec)

---

## Workflow

### Entry to Implementation

Implementation begins when Discovery and Architecture are complete.

**Prerequisites:**
- PROJECT.md exists (scope, audience, constraints, success criteria)
- ARCHITECTURE.md exists (component map, data flow, implementation sequence)
- For multi-module projects: ARCH_[module].md exists for each module

**First steps:**
1. Load PROJECT.md and ARCHITECTURE.md
2. Pick the first module from the implementation sequence
3. Create its DEVPLAN (see Documentation Formats below)
4. Enter Discuss mode

**Shortcut:** `/cold-start` to re-establish context at the start of any session.

### Phase Lifecycle

#### Planning (Discuss mode)

1. Determine scope and specific outcomes
2. Identify work regime (Build / Refine / Explore)
3. Specify accordingly:
   - **Build:** break into smallest testable steps; create test specs
   - **Refine:** define goals, constraints, and first item to show; skip detailed step plans
   - **Explore:** define the decision to be made and time box
4. Update DEVPLAN

**Refine phase structure:**

| Stage | Focus | Content |
|-------|-------|---------|
| First | Goals & constraints | What "good" looks like, hard limits |
| Middle | Feedback loops | Iterative show→adjust cycles (count unknown upfront) |
| Last | Stabilization | Lock decisions, write tests for final state, document |

For Refine phases, plan a **time budget**, not a step count.

If this is the first phase of a module, update the module's Status in ARCHITECTURE.md's Implementation Sequence table to "In progress".

**Supervised:** Present the plan for human review before updating DEVPLAN.
**Autonomous:** Update DEVPLAN directly. Log scope decisions to DECISIONS.md. Commit and exit.

**Shortcut:** `/phase-plan`

#### Step Execution

1. **Discuss:** specific changes, files affected, decisions needed
2. **Code/Debug**
3. **Verify:** run tests (Build) or show to human (Refine)
4. **Confirm:**
   - **Supervised:** human explicitly approves before proceeding. Invoking `/step-done` constitutes confirmation.
   - **Autonomous:** agent proceeds and logs decisions to DECISIONS.md.
5. **Update DEVLOG** (see Documentation Formats for entry format)
6. **Commit** — one commit per logical unit (see Commit Rules below)

**Shortcut:** `/step-done`

#### Phase Review

Review all code from the current phase.

**Priority #1:** Preserve existing functionality.
**Priority #2:** Simplify and reduce code.

Check for:
- Dead code or unused imports
- Architecture drift from the spec
- Opportunities to simplify
- Anything that should be split into a separate commit

Organize findings as:
- **Must fix** (correctness, architecture violations)
- **Should fix** (simplification, cleanup)
- **Optional** (style, minor improvements)

**Supervised:** Present findings. Wait for human direction on what to fix.
**Autonomous:** Apply must-fix and should-fix items. Log skip decisions for optional items to DECISIONS.md.

**Shortcut:** `/phase-review`

#### Phase Completion

1. Run phase-level tests (Build) or human sign-off (Refine)
2. Apply remaining review fixes (each as a normal step)
3. **DEVLOG learning review** — scan this phase's DEVLOG entries for trial-and-error patterns (anything that took multiple attempts). Extract prescriptive one-liners and promote to DEVPLAN Cold Start Summary's Gotchas field.
4. **Log review** — review iteration logs (summary.log, transcripts) for this phase. Identify patterns: repeated tool failures, wasted turns, behavioral issues. Promote findings to DEVPLAN Gotchas.
5. **Contract scan** — scan DEVLOG for Contract Changes markers. List affected upstream documents and propagate (see Contract Changes under Rules).
6. **DEVPLAN cleanup** — reduce completed phase to a one-line summary with DEVLOG reference. If DEVLOG exceeds ~500 lines, archive completed module entries to `DEVLOG_archive.md`.
7. Update module Status in ARCHITECTURE.md's Implementation Sequence table. Format: "Phase N complete" after each phase, or "Complete" if this was the module's final phase. Reset DEVPLAN frontmatter for the next phase (or clear it if module is done).
8. Present summary of everything done and everything needing confirmation.

**Supervised:** Do not commit. Wait for explicit human confirmation.
**Autonomous:** Commit. Exit with ESCALATE — human audits before next phase begins.

**Shortcut:** `/phase-review` then `/phase-complete`

---

## Rules

### Commits

**Confirmation:** Do not commit until human explicitly confirms. "Tests pass" is necessary but not sufficient — especially for Refine work, documentation, and cross-cutting changes. Under autonomous execution, the agent commits per step without waiting; decisions are logged to DECISIONS.md for asynchronous human audit.

**Commit vs. amend:** Default to NEW commit. Only amend when human explicitly says "amend."

**Cadence:** One commit per logical unit, not per session. If a session covers visual design + data changes + API cleanup, those are three separate commits.

### Scope

**Scope declaration:** At the start of a Refine session, list the discrete work items.

**Scope expansion:** When scope grows mid-session, acknowledge it explicitly. Add to the list (do now) or defer. Log additions in the DEVLOG. Don't silently absorb new work. Under autonomous execution, scope expansion beyond the defined phase is a hard stop — exit with ESCALATE.

### Error Escalation

1. Diagnose and apply a targeted fix
2. Same error recurs — try a fundamentally different approach
3. Still failing — question assumptions, search for solutions, reconsider the plan
4. After three failures — stop and ask the human for guidance

Under autonomous execution, three consecutive failures on the same problem is a hard stop — exit with ESCALATE.

### Contract Changes

**Contract-change markers:** When a DEVLOG entry modifies a shared contract, include a `### Contract Changes` section listing affected documents and specific contracts modified. If no shared contracts were modified, omit the section.

**Propagation rules:**
- **Immediate** (same session): Changes that modify a cross-module API signature or type. Test: "Would a cold-start session on another module produce incorrect code by reading the current ARCH doc?" If yes, propagate now.
- **Phase boundary** (batched): All other contract changes. At phase completion, scan DEVLOG's Contract Changes markers and update listed documents.

Under autonomous execution, contract changes that would affect other modules are a hard stop — the agent flags them in DECISIONS.md, exits with ESCALATE, and the human decides propagation.

**Cross-cutting tracks** should declare their upstream document scope in the DEVPLAN Cold Start Summary.

**Upstream revision protocol:**

*Scope changes (PROJECT.md):* Follow the revision protocol in PROJECT.md. Flexible scope changes can proceed inline. Core scope changes require pausing implementation and assessing impact against ARCHITECTURE.md.

*Architecture changes (ARCHITECTURE.md, ARCH files):* If a module boundary needs to move or a contract was fundamentally wrong:
1. Pause implementation on affected modules
2. Update ARCHITECTURE.md and affected ARCH files
3. Re-run the stability check
4. Adjust implementation sequence if needed
5. Record the change as a decision (D-#) in the affected DEVLOG
6. Resume implementation

### Session Habits

**Re-read before deciding.** Before any significant decision or direction change, re-read the DEVPLAN. Long sessions cause context drift.

**Don't re-read what you just wrote.** If you just created or modified a file, its contents are still in context. Only re-read when starting a new session or when the file may have been modified by another step.

---

## Documentation Formats

Every module maintains two files:

| File | Purpose | Update Timing |
|------|---------|---------------|
| **DEVPLAN.md** | Cold start context, roadmap, phase breakdown, test specs | Before each iteration |
| **DEVLOG.md** | What actually happened — changes, issues, lessons | After each iteration |

### DEVPLAN Structure

**Machine-Readable Status Block** (YAML frontmatter at the very top):
```yaml
---
module: RENDERING_UI
phase: 3b
phase_title: Hit-test math
step: 2 of 5
mode: Discuss | Code | Debug | Review
blocked: null
regime: Build | Refine | Explore
review_done: false
---
```

This block mirrors the Cold Start Summary and Current Status below it. Update both together. The frontmatter is the parse target for tooling; the prose sections remain the authoritative reference for cold-start sessions.

**Cold Start Summary** (stable — update on major shifts):
- **What this is** — one-sentence scope
- **Key constraints** — non-obvious technical limits
- **Gotchas** — things that cause silent failures, and operational knowledge learned through trial-and-error (commands, workarounds, patterns that aren't obvious from the code)

**Current Status** (volatile — update after each step):
- **Phase** — e.g., "3b — Hit-test math"
- **Focus** — what's being built right now
- **Blocked/Broken** — anything preventing progress

**Cleanup rule:** When a phase completes, reduce its section to a one-line summary with a DEVLOG reference. The DEVPLAN should get *shorter* as work progresses.

**Environment info** (build commands, shell workarounds, platform quirks) belongs in the DEVPLAN Cold Start Summary's Gotchas field, not in this document.

### DEVLOG Entry Format

Each step entry opens with a structured header followed by free-form prose:
```markdown
### Step [N]: [short title]
- **Mode:** Code | Debug | Review | Discuss
- **Outcome:** complete | partial | blocked
- **Contract changes:** none | [list of affected documents]

[Free-form prose: what was done, decisions made, issues encountered]
```

**Archival rule:** When DEVLOG exceeds ~500 lines, move completed module entries to `DEVLOG_archive.md` during phase completion cleanup. Add a boundary marker: `<!-- Entries above archived from Module N, YYYY-MM-DD -->`. The active DEVLOG should contain only the current and most recently completed module.

### Decision Log

```
D-#: [Title]
Date: YYYY-MM-DD | Status: Open | Closed
Priority: Critical | Important | Nice-to-have
Decision:
Rationale:
Revisit if:
```

Once **Closed**, don't reopen unless new evidence appears. For reactive decisions during Refine work, a one-line "changed X because Y" in the DEVLOG is sufficient. Use the full template only for genuine design forks with trade-offs.

---

## Patterns

### Cross-Module Integration

Before integrating modules A and B:
1. **Type compatibility** — verify A's output types match B's input types
2. **Boundary tests** — feed A's actual outputs into B's actual functions
3. **Bridge logic** — document any adapter/conversion needed

No module imports from the integration/orchestration layer. Subsystems do not import from each other except for shared types from upstream dependencies.

**Shortcut:** `/integration-check`

### Sub-Track Pattern

When cross-cutting work grows beyond a few DEVLOG entries, spin it off into its own DEVPLAN/DEVLOG pair within the parent module directory.

**When to create a sub-track:**
- The work has its own cold-start context distinct from the parent
- It spans multiple sessions or design passes
- It touches files across multiple modules
- It has its own decision space

**Naming:** `DEVPLAN_<TOPIC>.md` / `DEVLOG_<TOPIC>.md`. Decision IDs use a topic prefix (e.g., `SB-D1` for sidebar).

**Lifecycle:** When complete, update the parent DEVPLAN's Current Status. Leave sub-track files as historical reference.

### Structured Feedback Logging

When iterating visually (show → react → adjust), log each cycle in the DEVLOG. Failed attempts are especially valuable:

```
1. [Observation] Transport row sticks out past other elements
   Hypothesis: flex-wrap causing wrap when scrollbar appears
   Fix: removed flex-wrap
   Result: ✗ — scrollbar still steals layout width
2. [Same issue]
   Hypothesis: scrollbar-gutter: stable reserves constant space
   Fix: added scrollbar-gutter: stable
   Result: ✗ — scrollbar always visible, user rejected
3. [Root cause found] Native scrollbar steals 15px; rigid min-widths overflow
   Fix: thin 6px custom scrollbar + flex-shrink on tempo group
   Result: ✓ — resolved
```

---

## Automation

When using autonomous AI execution, follow the Automation Boundary protocol (ref/AUTOMATION.md) for work unit sizing, checkpoint frequency, and escalation criteria. Governance rules still apply; the automation protocol defines how they are satisfied without a human present at every step.

All autonomous hard stops are summarized here for reference:
- Three consecutive failures on the same problem
- Work regime shifts to Refine or Explore
- Scope needs to expand beyond the defined phase
- Contract change would affect other modules
- Phase completion (human audits before next phase)
- All modules complete
