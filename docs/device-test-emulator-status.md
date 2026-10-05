# Emulator QA status: docs/device-test-1.11.0.md

Live status of the emulator-runnable parts of the device checklist. This file is updated and pushed as the run goes on.

**Last update:** 2026-10-05 20:55 (+03) · **Run state:** IN PROGRESS

## Setup
- Build: `akadoze-2.0` @ 896e778, `:app:assembleDebug`
- Emulator: AVD `pixel` (sdk_gphone64_x86_64, API 36, google_apis x86_64), headless, `-read-only` (the AVD itself is not modified), KVM
- Access modes available here: **Shizuku** (v13.6.0, started over adb) and **DUMP-only** (`pm grant … DUMP`). **App root is not available**: the google_apis `su` only accepts the shell/root UIDs, so root-mode items are skipped.
- Emulator limits: no real CPU suspend, no real motion, no SIM radio or Bluetooth, no TalkBack app on google_apis images, no API < 26 image.

Legend: ✅ pass · ❌ fail (finding below) · ⚠️ partial / emulator-limited · ⏭️ skipped (device-only) · ⏳ pending · 🔄 running

## Checklist

| # | Item | Status | Notes |
|---|------|--------|-------|
| 0 | Install over old version, settings preserved | ⏳ | |
| 1 | Self-tests under Shizuku | ⏳ | |
| 2 | 30+ min screen-off session with movement | ⏳ | movement simulated through emulator sensors |
| 3 | Kill Shizuku mid-doze | ⏳ | |
| 4 | Honest settings (Shizuku) | ⏳ | |
| 5 | Restore system state | ⏳ | |
| 6 | Mode switch root → Shizuku | ⏳ | no app root here, so it may only be partial |
| 7 | Process death mid-session | ⏳ | |
| 8 | Radios/features restore | ⏳ | Wi-Fi/data/location/airplane/battery saver only |
| 9 | Tasker/automation gates | ⏳ | via `adb shell am broadcast` |
| 10 | Summary notification (13+) | ⏳ | |
| 11 | Update / reboot | ⏳ | |
| 12 | Fresh root discovery (SQ-95) | ⏭️ | needs app root |
| 13 | Honest UI (SQ-51 and others) | ⏳ | API < 26 sub-item skipped |
| F5 | Deferred watchdog timing | ⏭️ | needs real CPU suspend |
| A3 | Dual-SIM mobile data | ⏭️ | needs two SIMs |
| A4 | Interrupted root command | ⏭️ | needs app root |
| SQ-115 | Exact-alarm access lifecycle | ⏳ | |
| SQ-117 | Exact-alarm row in Settings | ⏳ | TalkBack sub-item skipped |
| SQ-114/116/118 | Sensor-only sessions | ⏳ | TalkBack sub-item skipped |
| SQ-126 | Cold-start "checking" (root) | ⏭️ | needs app root |

## Findings
_None yet._

## Log
- 20:50 emulator booted, debug APK built.
