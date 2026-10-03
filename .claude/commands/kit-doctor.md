---
description: Inspect android-kit health, template conflicts, local scope and resolved Sidequest routes without changing the project
---
Run this inspection inline. Do not install/update plugins, run imported hooks, or repair anything unless requested.

1. Read `.claude/kit/state.json` and `.claude/kit/conflicts.json`. Report pending merges from `.claude/kit/incoming/`; do not silently replace customized files.
2. Run `bash .claude/kit/detect-stack.sh`. Confirm unknown module/variant/screenshot fields with targeted build-file reads. Do not read secrets or `local.properties`.
3. Read `.claude/settings.local.json` and shared settings carefully; report enabled plugin IDs, duplicate hook handlers, main model/effort, and whether gateway wiring is local, inherited or absent. Never print tokens or environment values. `claude plugin list --json` shows installed versions.
4. Use `sidequest models` and board config to report the **resolved** model/effort for the board, including fallback and board-local overrides. A model listed in a catalog is not proof that a request will authenticate. Do not dispatch paid test tasks.
5. Check `git ls-files -- .claude/settings.local.json .claude-plugin-config.json local.properties '*.jks' '*.keystore'`. Report tracked sensitive paths without opening them. Excludes do not untrack files.
6. Compare the board integration branch with the intended working branch, not automatically with origin's default. Confirm clean/dirty status, JDK/ANDROID_HOME, and LSP executables. For ignored kit files, confirm any verify helper exists in the executor checkout before using it.
7. Return a short PASS/WARN/BLOCKED table and specific next actions. State which checks could not run.
