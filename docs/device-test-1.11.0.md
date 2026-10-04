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
12. **Honest UI (SQ-51).**
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

**If something fails:** Doze monitor → "Share report", plus
`adb logcat -d --pid=$(adb shell pidof -s com.akylas.enforcedoze) | tail -200`.

**Rollback:** run "Restore system state" before downgrading. Downgrading doesn't undo system state.
