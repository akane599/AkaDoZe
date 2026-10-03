<!-- android-kit -->
# CLAUDE.md — AkaDoZe

AkaDoZe (EnforceDoze fork): forces Android Doze right after screen-off and disables motion sensing, via root or Shizuku, for battery-conscious power users.
Android, developed on Ubuntu from the CLI. **If the STACK block still has `<placeholders>`, run `/bootstrap` first.**

<!-- STACK:BEGIN (filled by /bootstrap) -->
- Origin: fork of farfromrefug/EnforceDoze, imported as akane599/AkaDoZe @ db47ccd (squashed, no upstream history) · Integration branch: Base
- Language: mixed — 51 Java / 44 Kotlin (main) · UI: Views (XML, PreferenceFragment, material-dialogs 0.9) · screenshot tests: none
- JDK target 17 (machine JDK 21) · Gradle 8.13 · AGP 8.13.2 · Kotlin 2.0.0 · compileSdk 36 · targetSdk 36 · minSdk 23
- Modules: :app · Architecture: single-module; pure Kotlin engine/policies (access/, doze/, monitor/, service/) behind Activities + ForceDozeService; SharedPreferences + restore ledger; root via libsuperuser, Shizuku API 13.1.5 · DI/DB/Net: none / platform SQLite journal / none
- App id: com.akylas.enforcedoze · Launcher: .MainActivity · Groovy build scripts, no version catalog, repos include jcenter + jitpack
- Baseline (akadoze-2.0): full gate green · 265 JVM unit tests, 0 failing; lint against app/lint-baseline.xml; device QA pending (docs/device-test-1.11.0.md)
<!-- STACK:END -->

## How work flows here
- **The main session orchestrates; it doesn't implement.** Work becomes Sidequest tickets, executors do it in isolated worktrees on the model each ticket's category routes to (Claude, or GPT through Model Gateway), and the main session integrates after verification. Quick one-line edits to named files stay inline.
- **Routing** (Sidequest profile `android-kit`; `sidequest models` shows it live): GPT implements (GPT-6 Luna mechanical, GPT-6.1 Sol default, GPT-6 Astra hard via `coding.hard.frontier`); UI, review and escalation stay on Claude (Opus, Fable only after Opus stalls); research on Sonnet. UI is never routed to GPT.
- **Big or multi-step changes:** plan mode → `/plan-audit` (routed review ticket with the Android checklist) → pin the plan as a Sidequest story → dispatch waves.
- **UI overhaul:** `/ui-overhaul <scope>`.
- **Bugs:** every ticket's tests must pass before it's integrated; risky ones (weak tests, unchecked callers, high-stakes) also get an Opus review. `/bug-hunt [area]` hunts in existing code; `/code-review` checks a diff before you push.
- **End of a session or story:** `/wrap-up` turns corrections and decisions into rules, CLAUDE.md lines and the decision log (you approve each). Personal preferences go to auto memory ("remember that …").
- **Conventions** live in `.claude/live-rules/rules/` and are injected into every session and executor, so they're not repeated here. **Project map:** `.claude/.codebase-info/` (codebase-mapper).
- **Board:** "show the Sidequest board"; side issues mentioned mid-task get filed, not worked.
- **Plugin health / updates:** `/quartermaster:toolshed-doctor`, `/quartermaster:update-toolshed`. After a few weeks of real work: `/quartermaster:resupply`.
- **Compaction:** your last instructions are saved before compaction and re-injected after; re-read them before acting.

## Commands
- Build: `./gradlew :app:assembleDebug --console=plain -q` · Unit: `:app:testDebugUnitTest` · Lint: `:app:lintDebug`
- Screenshots: none (Views app, no screenshot framework); UI checks go through the device / android-emulator-qa.
- Device: `./gradlew :app:installDebug -q && adb shell am start -n com.akylas.enforcedoze/.MainActivity` · logs: `adb logcat -d --pid=$(adb shell pidof -s com.akylas.enforcedoze) | tail -80`
- Machine: 12 cores, ~22 GB RAM, no KVM. Gradle daemon is `-Xmx2048m`. Up to 4 Gradle jobs at once (user direction 2026-10-03, above the live rule's default of 2) and at most 3 concurrent Opus agents (usage limits). No emulator: device work needs a physical device over adb (the app needs root or Shizuku anyway).
- Emulator (not usable here, no KVM): `emulator -avd <AVD> -no-window -no-audio &` then `adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'`
