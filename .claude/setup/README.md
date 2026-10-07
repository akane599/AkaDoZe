# Setting up AkaDoZe's Claude Code workspace on another PC

Everything project-specific is in git: `CLAUDE.md`, `.claude/settings.json` (permissions, hooks, plugins),
`.claude/kit/`, `.claude/live-rules/`, `.claude/skills/`, `.claude/commands/`, `.claude/.codebase-info/` and
`.claude/quartermaster/crap.json`. This page covers what lives outside the repo: tools, plugins, the Sidequest
routing profile and the machine-local settings.

The reference machine (2026-10-07) runs Ubuntu with Claude Code 2.1.292, JDK 21, Node 26, Python 3.14, 12 cores,
15 GB RAM and KVM.

## 1. Tools

| Tool | Why | Install |
|---|---|---|
| JDK 21 | Gradle (the project targets 17) | distro `openjdk-21-jdk` |
| Android SDK + `ANDROID_HOME` | build, emulator | Android Studio or cmdline-tools; never create `local.properties` |
| Node.js | Sidequest, Quartermaster, Codebase Mapper | nodejs.org or nvm |
| Python 3 + Pillow | kit hooks, visual-qa contact sheets | distro `python3`, `pip install --user pillow` |
| lizard | CRAP gate (`quartermaster crap`) | `pipx install lizard` (or `uv tool install lizard`) |
| jq | PostToolUse format hook | distro `jq` |
| ktlint (optional) | format-on-edit for `.kt` when `.claude/kit/format-on-edit` exists | ktlint releases |

Emulator AVDs used by the `visual-qa` skill and `CLAUDE.md` (KVM required):

```bash
sdkmanager "emulator" "platform-tools" \
  "system-images;android-36;google_apis;x86_64" \
  "system-images;android-35;google_apis;x86_64" \
  "system-images;android-23;google_apis;x86_64"
avdmanager create avd -n pixel       -k "system-images;android-36;google_apis;x86_64" -d pixel_8
avdmanager create avd -n pixel-api35 -k "system-images;android-35;google_apis;x86_64" -d pixel
avdmanager create avd -n amber-api23 -k "system-images;android-23;google_apis;x86_64" -d pixel
```

(`batstats-api36` and `batstats16k` are only needed for battery-stats experiments.)

## 2. Plugins

Open Claude Code in the repo and trust the folder. `.claude/settings.json` declares the `eigenwise-toolshed` and
`claude-plugins-official` marketplaces and enables the plugins the workflow needs: sidequest, model-gateway,
live-rules, codebase-mapper, quartermaster, context7, claude-security, security-guidance, session-report and
jdtls-lsp. Accept the install prompt, or run `/plugin` and install them, then `/reload-plugins`.

Three optional helpers came from a local marketplace on the reference machine and are not in git. Add them only if
you want them, then enable them in `.claude/settings.local.json`:
- android-kmp-playbook: https://github.com/rezaiyan/android-kmp-claude-playbook
- droidforge: https://github.com/SUDARSHANCHAUDHARI/DroidForge
- kotlin-lsp-android: a wrapper around JetBrains kotlin-lsp with a longer startup timeout. The official kotlin-lsp
  plugin works too.

## 3. Machine-local settings

`.claude/settings.local.json` is git-ignored. Copy `settings.local.example.json` to it, then adjust:
- The `env` block sends Claude Code through Model Gateway (`ANTHROPIC_BASE_URL=http://127.0.0.1:18764`), which is what
  routes `coding.*` tickets to GPT. Start the gateway with the model-gateway plugin first. Without the gateway,
  delete the `env` block so Claude Code talks to Anthropic directly, and check `sidequest models` to see where the
  GPT categories resolve. A category without a live route needs its route edited before dispatch.
- Drop the `@android-local` plugins you didn't install.
- The example's `permissions.allow` lists only commands that can't be widened by flags. Broad prefixes such as
  `Bash(rg:*)` (`--pre` runs a program), `Bash(git diff:*)` / `git log` / `git show` (`--output` writes files),
  `Bash(./gradlew:*)` (`--init-script`) or `Bash(adb:*)` pre-approve much more than they look like; add them per
  machine only if you accept that. They are deliberately not in the tracked `.claude/settings.json`.

## 4. Sidequest routing profile

The `android-kit` profile (which category runs on which model) is stored per machine, so a snapshot is kept here:

```bash
python3 .claude/setup/restore-sidequest-profile.py --project "$PWD"
```

It creates `android-kit` from Sidequest's built-in `coding` profile, sets all 19 categories (name, description,
contract, route, fallback, read-only) and points this project's board at it. It is safe to re-run. After a routing
change on any machine, refresh the snapshot with
`node <sidequest>/bin/sidequest.js profile get android-kit --json > .claude/setup/sidequest-android-kit.json` and
commit it. Board tickets and history stay on the
machine that made them.

## 5. Check

```bash
bash .claude/kit/gradle-check.sh :app:verifyRoborazziDebug :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
node ~/.claude/plugins/cache/eigenwise-toolshed/quartermaster/*/bin/quartermaster.js crap --json   # exit 1 = only legacy offenders
emulator -accel-check
```

Then ask Claude to "show the Sidequest board"; it should list the `android-kit` categories.
