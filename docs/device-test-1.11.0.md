# EnforceDoze 1.11.0 — on-device test checklist

Nothing below could be run here: there is no device, emulator or KVM. Every item is a real-device check.
The Doze Monitor (menu → "Doze monitor", or long-press the quick tile) is the evidence source for most of them.
Run them on Android 16 with Shizuku (shell) first, then repeat items 1–4 with root if you have it.

**Before starting:** install over the old version, then open the app once. Settings should look the same as
before, and nothing should be reset.

1. **Self-tests under Shizuku.** Start the service, then open the Doze monitor and run "Test Doze now" and "Test sensor restriction".
   - Expect PASSED for both.
   - NOT_VERIFIED is honest if Doze was already forced by something else.
   - Test buttons should be disabled while the service is stopped.
2. **A real 30+ minute screen-off session with movement.** Lock the phone and carry it around.
   - Afterwards the Monitor session should show the verified deep-IDLE percentage, any re-forces after
     motion, sensors "restricted (verified)", maintenance windows and a coverage figure.
   - `adb shell dumpsys sensorservice | grep "Mode :"` while dozing should print RESTRICTED. It should
     print NORMAL after screen-on.
3. **Kill Shizuku mid-doze.** With the screen off for at least 2 minutes, stop Shizuku from its app or by
   rebooting it.
   - Expect a recovery-debt notification.
   - Restart Shizuku, then turn the screen on.
   - Expect the debt to clear (restored + journaled in the Monitor), and sensors NORMAL / Doze unforced.
4. **Honest settings.** In Shizuku mode, open Settings.
   - Root-only items (disable all sensors, setprop Doze, notification blocking below Android 13) should
     be disabled with a reason and tagged "Root".
   - Their saved values must be unchanged after switching back to root.
5. **Restore system state.** Doze monitor → "Restore system state". Afterwards:
   - `adb shell dumpsys sensorservice | grep "Mode :"` should print NORMAL.
   - `adb shell dumpsys deviceidle | grep mForceIdle` should print false.
6. **Mode switch.**
   - Switch root → Shizuku. Deny the Shizuku prompt: the app should revert to root with an explanation.
   - Repeat with a screen rotation while the prompt is open.
7. **Process death mid-session.** Screen off, wait 2 minutes, then crash the app:
   `adb shell am crash com.akylas.enforcedoze`, or with root `adb shell su -c 'kill -9 $(pidof com.akylas.enforcedoze)'`.
   - The service should come back by itself (START_STICKY) and reconcile.
   - Screen on: nothing should stay RESTRICTED or forced, and any debt should be shown, then restored.
   - Don't use `am force-stop`: it puts the app in the stopped state, so nothing restarts until you open it.
8. **Radios/features you use** (Wi-Fi, data, Bluetooth, location, airplane, battery saver, app suspend,
   notification block). Enable each one you rely on, do a session, then check it is back to its original
   state after unlock. Biometrics should be restored on screen-on, before unlock.
9. **Tasker/automation**, if you use it. External start/stop works only when its gate is on in Settings →
   Other apps. Privileged external control is off by default. A rejected call shows one notice per gate.
10. **Notifications (Android 13+).** Turn on "Summary notification after screen-on", grant the notification permission,
    and check that a summary appears after a session. With the permission denied, nothing should be posted.
11. **Update/boot.** Reboot with the service enabled, and install an update over it. The service should
    come back, and no leftover RESTRICTED or forced state should remain.
12. **Fresh root discovery after detached boot/update recovery (SQ-95).** On a rooted phone, select
    root mode, stop the service and leave a pending restore-ledger entry. Reboot (or update the app)
    while delaying the su grant until the restore-only probe times out and its window closes.
    - Keep the same app process alive and do not open MainActivity, Settings or Doze tunables. Verify
      the detached window does not repeatedly prompt for su.
    - Enable the service from the quick tile, the disabled notification or external ENABLE. Grant su
      on its fresh probe: expect ROOT access, pending state restored and verified Doze on screen-off,
      without needing an Activity refresh. Repeat for each enable entry point.
    - Withhold the grant instead: the new service gets at most one initial probe plus three retries
      (1/2/4 s backoff while session/restore work needs them), then settles NO_ACCESS without looping.
    - Pending real-device QA; no device was attached during the JVM verification.
13. **Honest UI (SQ-51).**
    - DUMP only (no root, Shizuku off or not permitted, DUMP granted over ADB): turning the main switch on
      shows "Not enforcing yet", and the status line reads "…on, but not enforcing: Doze sessions need
      Shizuku or root". It never shows "That's it".
    - Settings → Reset: a progress dialog, then "Reset complete" only if every step was confirmed. Without
      root/Shizuku, expect "Reset incomplete" listing each step as "couldn't be checked". With leftover
      debt, the execution mode survives the reset.
    - Reset with READ_PHONE_STATE granted (SQ-71): the app stays open through the progress dialog, and the
      report names "revoke READ_PHONE_STATE" as the last step that runs on OK. After OK the app closes or
      restarts. Reopened, settings are defaults and `adb shell dumpsys package com.akylas.enforcedoze`
      shows READ_PHONE_STATE as not granted. Rotate (or switch dark mode) while the progress dialog is up:
      the report still appears on the new screen, and OK still finishes.
    - Reset with the service running (SQ-93 / SQ-92 F4): over Shizuku, start the service, then confirm
      Settings → Reset. Expect teardown before reset commands, no 4-second UI stall behind the reset job,
      and no "Sensors/Doze may still be restricted" notification or `RECOVERY_DEBT TEARDOWN_TIMEOUT`
      journal row for queued-but-not-started teardown. A successful report still requires readbacks.
      Record elapsed time from confirmation to the report and UI responsiveness during teardown/reset;
      repeat over root and record both durations. Check notifications and the monitor journal before
      tapping OK/restarting, so restart cannot conceal a false notice. Device timing remains unverified.
    - Reset worker retirement/retry (SQ-89): with the service stopped, tap OK on the reset report just as
      another worker operation finishes; deferred revokes/restart must not hang on "Please wait".
      With a diagnostic build that throws during reset/reconciliation, expect a dismissable "Reset
      incomplete" report, unchanged preferences, and no automatic restart. After OK (also after rotating
      while the job runs), Settings → Reset can start another attempt. This exception injection is covered
      on the JVM; device timing and dialog behavior remain unverified here.
    - Re-picking the execution mode that is already active doesn't restart the service (no new
      foreground notification, no stop/start in logcat).
    - Whitelist with a failing read (e.g. no DUMP/Shizuku/root) shows "The Doze whitelist couldn't be read",
      not "Error".
    - Android 6 or 7 (API < 26), if available: Settings → Whitelist music app → Open doesn't crash.
    - A damaged record for anything other than forced Doze or motion sensors shows "Dismiss damaged
      records" on the access card and in the monitor. Confirming removes it. Cancelling keeps it.

**Device-only findings to confirm (SQ-92):**
- *Deferred watchdog timing (F5).*
  - Setup: root or Shizuku, screen off, "Keep Doze enforced" on. Trigger two non-maintenance idle exits within
    60 s so the watchdog defers (`DEFER` in the journal). Then unplug USB and let the CPU suspend.
  - Compare the scheduled re-force time with the actual one, and note how long the device was suspended.
  - Repeat with the CPU kept awake as a control.
  - Screen-on or access loss must cancel the pending re-force.
  - Only a measured late re-force justifies a wake/alarm change.
- *Mobile data on dual-SIM (A3).*
  - On a device with two active SIM/eSIM subscriptions, note:
    - the API level and OEM;
    - the default-data SIM;
    - each SIM's mobile-data switch.
  - Let AkaDoZe disable and restore mobile data. Compare `settings get global mobile_data` with each SIM's
    own switch and with the journal/ledger outcome.
  - Repeat after swapping the default-data SIM, including a swap between Doze entry and restore.
  - If the global bit and the affected SIM disagree, file it with these outputs.
- *Interrupted root command (A4, SQ-107).*
  - On root, make a root command outlast its timeout. For example, run a self-test while `su` is slowed by a
    pending Magisk prompt, or with a long-running blocking command.
  - After the timeout, `ps -A | grep -E ' su|sh$'` must show no leftover su session from AkaDoZe. The
    interrupted change must not land afterwards (check its readback).
  - The next root command opens a fresh su session and succeeds.
- *Exact-alarm access lifecycle (SQ-115), API 31+.*
  - Configure a custom Doze period whose start is a few minutes away.
  - Revoke "Alarms & reminders" for AkaDoZe in Settings. Android kills the app.
  - Grant it again, then confirm `adb shell dumpsys alarm | grep -A3 com.akylas.enforcedoze` shows a re-armed
    exact boundary. This proves the non-exported grant receiver got the system broadcast.
  - Let the boundary fire with the app in the background and the screen off. The service must start (granted).
  - Repeat with access denied: the boundary is inexact, and if the service start is denied the existing notice
    appears.
  - The master switch off means no re-arm.
- *Exact-alarm row in Settings (SQ-117), API 31+.*
  - With a custom Doze period configured, Settings shows the exact-alarm row: exact when "Alarms & reminders"
    is granted, best-effort when it is denied. Without periods (or below API 31) the row is hidden.
  - Tapping it opens AkaDoZe's own "Alarms & reminders" page. Toggle access, press Back: the row and the Main
    screen update on resume without restarting the app.
  - With TalkBack on, the row reads its title and current status, and it is reachable and activatable by swipe.
- *Sensor-only sessions and honest status (SQ-114/SQ-116/SQ-118).*
  - Setup: no root, Shizuku stopped, DUMP granted over ADB
    (`adb shell pm grant com.akylas.enforcedoze android.permission.DUMP`), "Disable motion sensors" on.
  - Main and the access card say sensor-only / natural Doze, not "forced Doze" and not "paused". They also say
    Android still decides when to idle. "Test sensor restriction" is enabled; "Test Doze now" is disabled
    with its reason.
  - Run the sensor test. Expect PASSED, and the app-UID transcript (restrict → `Mode :` readback → enable) in the
    journal. `adb shell dumpsys sensorservice` from the shell is not proof that the app UID could mutate.
  - Repeat without DUMP: passive status, both tests disabled with distinct reasons. Repeat with sensors off:
    nothing is offered as enforcement.
  - Downgrade mid-session: on Shizuku, screen off for 2+ minutes, then stop Shizuku. Expect recovery first, then
    the same session continuing as sensor-only: no new session and the same delay. A force debt notice stays
    visible until it is restored. Restart Shizuku: the session upgrades back to full forcing without a new session.
  - Screen on, charging, stop and unlock still restore sensors to NORMAL.
  - With TalkBack on, the status line, both test buttons and the debt/downgrade notices read distinctly.

**If something fails:** Doze monitor → "Share report", plus
`adb logcat -d --pid=$(adb shell pidof -s com.akylas.enforcedoze) | tail -200`.

**Rollback:** run "Restore system state" before downgrading. Downgrading doesn't undo system state.
