---
description: Keep changed code within the CRAP ceiling
priority: 85
---
- Before calling a code change done, run `node "/home/dev/.claude/plugins/cache/eigenwise-toolshed/quartermaster/0.11.10/bin/quartermaster.js" crap --json` from that executor worktree. It uses that worktree's `.claude/quartermaster/crap.json` and the Base ref. Get the shared Gradle lane first.
- Keep each function you add or modify strictly below 6. Unchanged legacy functions are excluded relative to `git merge-base HEAD Base`.
- Exit 0 passes. Exit 1 fails the ceiling. Exit 2 is UNVERIFIED: the measurement or a prerequisite is missing, failed or stale. Exit 2 is never a pass.
- Read exit 1 entry by entry. On akadoze-2.0 the gate is branch-wide against Base, so it lists the 2.0 offenders that predate the gate (352 at bdb5dcd). A change fails only when a `failures` entry is a function its own diff adds or modifies. Report the other entries as a count, and don't refactor unrelated functions to clear them.
- Never bypass the gate, raise its fixed ceiling, install prerequisites without approval, or reuse coverage for changed bytes. Quartermaster is setup-only: consume its native measurement, not a tracked substitute.
