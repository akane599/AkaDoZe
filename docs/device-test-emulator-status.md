# Emulator QA status: docs/device-test-1.11.0.md

Live status of the emulator-runnable parts of the device checklist. This file is updated and pushed as the run goes on.

**Last update:** 2026-10-05 21:50 (+03) · **Run state:** IN PROGRESS

## Setup
- Build: `akadoze-2.0` @ 896e778, `:app:assembleDebug`
- Emulator: AVD `pixel` (sdk_gphone64_x86_64, API 36, google_apis x86_64), headless, `-read-only` (the AVD itself is not modified), KVM
- Access modes available here: **Shizuku** (v13.6.0, started over adb) and **DUMP-only** (`pm grant … DUMP`). **App root is not available**: the google_apis `su` only accepts the shell/root UIDs, so root-mode items are skipped.
- Emulator limits: no real CPU suspend, no real motion, no SIM radio or Bluetooth, no TalkBack app on google_apis images, no API < 26 image.

Legend: ✅ pass · ❌ fail (finding below) · ⚠️ partial / emulator-limited · ⏭️ skipped (device-only) · ⏳ pending · 🔄 running

## Checklist

| # | Item | Status | Notes |
|---|------|--------|-------|
| 0 | Install over old version, settings preserved | ✅ | 1.10.2 (Base 42f8e73) → 1.11.0. 11 seeded non-default prefs (mode, delay, Wi-Fi, saver, period, tunable…) byte-identical after upgrade; Shizuku grant kept |
| 1 | Self-tests under Shizuku | ✅ | buttons disabled with reason while stopped; Test Doze → PASSED; Test sensors → PASSED; afterwards `Mode : NORMAL`, `mForceIdle=false` |
| 2 | 30+ min screen-off session with movement | ⚠️ | **Enforcement ✅:** 21:08–21:39 on battery, acceleration changed every 2 min; forced IDLE + `Mode : RESTRICTED` + Wi-Fi off + saver on in all 31 one-minute samples, no idle exits, so 0 re-forces (emulator motion can't reach a restricted detector). Screen-on: `NORMAL`, `mForceIdle=false`, Wi-Fi/saver restored ✅. **Monitor card ❌:** "Deep 98% · Unknown 2%" is right, but it says "Sensors on ✗ / motion sensors were not restricted" (**F2**) and "History truncated" (**F3**) |
| 3 | Kill Shizuku mid-doze | ⚠️ | Screen off 21:40:51, `pkill shizuku_server` at 21:43:26. Debt notice "Sensors/Doze may still be restricted" ✅ and "Doze forcing stopped … still restricts motion sensors" ✅ (DUMP held, so sensor-only downgrade). Journal: ACCESS_LOST debt, RESTORE_FAILED ×3 (NO_ACCESS), sensors re-restricted via app UID. Shizuku restarted (screen still off): "Access is back", all three debts restored (VERIFY rows), full forcing resumed in the **same** session ✅. Screen on: NORMAL, unforced, Wi-Fi/saver back ✅; monitor "nothing left to restore" ✅. **But** the debt notice stayed posted after both restores until the app was opened (**F4**) |
| 4 | Honest settings (Shizuku) | 🔄 | "Disable all sensors" disabled: "Requires root — not available with Shizuku". Notification blocklist enabled (correct, API 36 ≥ 13). Auto-rotate fix disabled as "No longer used…". No setprop item in main Settings. Value-preservation on switch back to root pending |
| 5 | Restore system state | ⏳ | |
| 6 | Mode switch root → Shizuku | ⏳ | no app root here, so it may only be partial |
| 7 | Process death mid-session | ⏳ | |
| 8 | Radios/features restore | ⏳ | Wi-Fi/data/location/airplane/battery saver only |
| 9 | Tasker/automation gates | ⏳ | via `adb shell am broadcast` |
| 10 | Summary notification (13+) | ⚠️ | permission granted + setting on: "While the screen was off — Deep Doze 59% of 3 min · 27% unknown · 0 maintenance · sensors on ✗ · 0.0%/h" posted on screen-on ✅. "sensors on ✗" is wrong (F2). Denied-permission case pending |
| 11 | Update / reboot | ⏳ | |
| 12 | Fresh root discovery (SQ-95) | ⏭️ | needs app root |
| 13 | Honest UI (SQ-51 and others) | ⏳ | API < 26 sub-item skipped |
| F5 | Deferred watchdog timing | ⏭️ | needs real CPU suspend |
| A3 | Dual-SIM mobile data | ⏭️ | needs two SIMs |
| A4 | Interrupted root command | ⏭️ | needs app root |
| SQ-115 | Exact-alarm access lifecycle | ⏳ | |
| SQ-117 | Exact-alarm row in Settings | 🔄 | with a period + access granted: "Exact period timing — Exact alarms are available…" ✅; after removing the last period the row hides and the boundary alarm is cancelled ✅. Denied state pending. TalkBack skipped |
| SQ-114/116/118 | Sensor-only sessions | 🔄 | mid-session downgrade/upgrade ✅ (see item 3: same sessionId 1791225651302 throughout, no new session). Static DUMP-only checks pending. TalkBack skipped |
| SQ-126 | Cold-start "checking" (root) | ⏭️ | needs app root |

## Findings
Filed on the Sidequest board as SQ-1 (F2), SQ-2 (F1), SQ-3 (F3), SQ-4 (F4); not dispatched during the emulator run (Gradle load destabilised the emulator earlier).

- **F1 (low, monitor UI): phantom session card from pre-session events.** Before the first screen-off, journal rows carry `sessionId=0` (here two `ACCESS_CHANGED` rows written at install/first launch). `SessionAggregator.summarize` keeps `sessionId >= 0` (`monitor/SessionAggregator.kt:47`), so the monitor shows "Session from 8:52 PM, 0 minutes … Partial session, Never reached deep Doze, Sensors unverified" though no screen-off ever happened. Likely one such card per boot. Fix idea: drop `sessionId == 0` from session grouping (keep them in the journal).
- **F2 (medium, monitor/summary): every normally ended session reports "motion sensors were not restricted".** Journal for the 30-min session: `SENSORS_RESTRICTED sensor=RESTRICTED` at 85 s, then teardown `VERIFY sensor=NORMAL` at 1 910 529 ms, *then* `SCREEN_ON` at 1 910 846 ms (teardown readbacks are journaled before the SCREEN_ON row). `SessionAggregator.summarizeSession` keeps the *last* sensor observation before `end` (`SessionAggregator.kt:130-138`), which is the post-restore NORMAL, so the verdict is `NO`. The headline sensor metric (and likely the screen-on summary notification) is wrong for real sessions. Fix idea: judge restriction from observations before teardown starts (or "any verified RESTRICTED while screen off"); add a JVM test with the real event order.
- **F3 (low, monitor): false "History truncated".** `HistoryMerger.merge` sets `truncated` when the first OS idling-history entry is later than SCREEN_OFF (`HistoryMerger.kt:47`). On a fresh boot (or whenever nothing idle-related happened before the session) the OS history is just `deep-idle` at +30 s (after the Doze delay), so a complete history is flagged truncated. Needs a better signal (e.g. history non-empty before the session but missing the carry-in, or buffer at capacity).
- **F4 (low-medium, notices): "Sensors/Doze may still be restricted" outlives the debt.** After Shizuku came back the service verified all three restores (journal VERIFY FORCE_DOZE/BATTERY_SAVER/WIFI), and screen-on teardown verified NORMAL/unforced, yet notification 8802 stayed until the app was opened. `NoticeSink.cancelDebt` is only called from UI ledger checks (`DozeMonitorActivity.java:484`, `AccessCard.java:164`); service-side verified restores only clear the gate (`NoticeSink.emit` VERIFY/SENSORS_RESTORED). A user who never opens the app keeps a false alarm. Related: the session card lists "Restore failed, Restore pending" with no sign that it was later restored.
- **O1 (observation, wording):** with the screen on, the monitor reads "Deep Doze: Active / Light Doze: Active". That's the deviceidle state name (`ACTIVE` = *not* dozing), but it reads as "Doze is active". Consider "Not idle (screen/device active)".

## Log
- 20:50 emulator booted, debug APK built.
- 20:51 old 1.10.2 installed, prefs seeded, Shizuku 13.6.0 started over adb, upgraded to 1.11.0.
- 20:58 service on, both self-tests passed.
- 21:00 first item-2 attempt: forced IDLE + sensors RESTRICTED + Wi-Fi off + saver on, all within the 30 s delay ✅. Then a 2nd emulator was booted for parallel work and **both emulators hung and died** (host contention; QEMU hanging-thread errors). Not an app problem. Back to one emulator and serial runs; read-only AVD so it was re-provisioned from scratch (`install -g`, Shizuku over adb, QA prefs).
- 21:08 item 2 restarted.
- 21:40–21:45 item 3 + item 10 run; F4 found.
- 21:39 item 2 done; enforcement solid for 30 min; monitor summary wrong about sensors (F2) and truncation (F3). F1 seen again (a 9:07 phantom card, now with "Access lost" from the startup NO_ACCESS→SHELL transition).
