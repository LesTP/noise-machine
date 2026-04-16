---
name: phase-review
description: End-of-phase code review before completion
---

Review all code from the current phase.

Priority #1: Preserve existing functionality
Priority #2: Simplify and reduce code

Check for:
- Dead code or unused imports
- Architecture drift from the spec
- Opportunities to simplify
- Anything that should be split into a separate commit

Present findings organized as:
- Must fix (correctness, architecture violations)
- Should fix (simplification, cleanup)
- Optional (style, minor improvements)

After review is complete, update DEVPLAN frontmatter:
review_done: true

**If autonomous:**
Apply must-fix and should-fix items. Log skip decisions for optional
items to DECISIONS.md. Exit.

**If not autonomous** (default):
Do not implement. Wait for my direction on what to fix.
