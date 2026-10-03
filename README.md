# EnforceDoze
EnforceDoze is a fork of [ForceDoze](https://github.com/theblixguy/ForceDoze) which is not maintained anymore. Thanks to [@theblixguy](https://github.com/theblixguy) for all his work on this.

EnforceDoze allows you to forcefully enable Doze right after you turn off your screen, and on top of that, it also disables motion sensors so Doze stays active even if your device is not stationary while screen off. Doze will only deactivate periodically to execute maintenance jobs (like getting notifications, etc), otherwise it will remain active as long as your screen is off. This brings a lot more battery savings than standard Doze functionality, because even with screen off and Doze enabled, Doze is still periodically checking for movement, and disabling motion sensing improves battery life further.

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/com.akylas.enforcedoze/)
[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png"
     alt="Get it on IzzyOnDroid"
     height="80">](https://apt.izzysoft.de/fdroid/index/apk/com.akylas.enforcedoze)

Or download the latest APK from the [Releases Section](https://github.com/farfromrefug/EnforceDoze/releases/latest).

   
<h2 align="center">Enjoying Enforcedoze?</h2>
<p align="center">Please consider making a small donation to help fund the project. Developing an application, especially one that is open source and completely free, takes a lot of time and effort.
<br>
<br>
<div align="center">
<a href="https://github.com/sponsors/farfromrefug">:heart: Sponsor</a>
</div>
<hr>

## Coverage:
 * LifeHacker: https://lifehacker.com/how-to-squeeze-more-battery-out-of-your-phone-with-andr-1791336715
 
# Features
* Force Doze mode immediately after screen off or after a user specified delay, and verify it by reading the Doze state back
* Disable motion sensors (verified) so Doze stays active even when the phone moves
* Every system change is recorded first and put back on screen-on; anything that couldn't be restored is shown and can be restored with one tap
* Doze Monitor: what happened while the screen was off (deep-idle time, re-forces, maintenance windows, problems), self-tests and a shareable report
* Works with Shizuku (no root needed) or root; features that need more access are shown disabled with the reason
* Add/remove apps or packages directly to system Doze whitelist
* Disable Biometrics, WiFi, mobile data, Bluetooth and location during Doze, suspend selected apps or block their notifications
* Keep the network on while a hotspot is active or a music app is playing
* Tasker support to turn on/off EnforceDoze and modify other features (each kind of external control has its own switch)
* Enable Doze mode on devices where OEM has disabled it (root)
* Free, no ads and open source

## Shizuku vs root

EnforceDoze runs its system commands through one of three access levels. The access card on the main screen
shows the current one and what is missing. Settings that the current level can't perform are shown
disabled with the reason, and your saved choices are never changed by this.

- **Root**: `su` (Magisk, KernelSU…), or Shizuku started as root.
- **Shizuku (shell)**: Shizuku started over ADB or wireless debugging. It runs as the shell user, and no
  root is needed.
- **No privileged access**: only what you granted with ADB (`DUMP`, `WRITE_SECURE_SETTINGS`). Doze sessions don't
  run at this level. EnforceDoze can still read the Doze state, apply Doze tunables, and undo its own sensor change
  if Shizuku stops. The access card lists the exact commands.

| Feature | Shizuku (shell) | Root | No privileged access |
|---|---|---|---|
| Force Doze after screen-off | ✅ | ✅ | ❌ |
| Verify Doze state / Doze Monitor evidence | ✅ | ✅ | with `DUMP` |
| Restrict motion sensors | ✅ | ✅ | ❌ |
| Doze tunables | ✅ | ✅ | with `WRITE_SECURE_SETTINGS` |
| Disable biometrics in Doze | ✅ | ✅ | ❌ |
| Battery saver, Wi-Fi, mobile data, Bluetooth, location off in Doze | ✅ | ✅ | ❌ |
| Airplane mode in Doze | Android 11+ | Android 11+ | ❌ |
| Suspend selected apps in Doze | Android 7+ | ✅ (Android 6: disable) | ❌ |
| Block notifications of selected apps | Android 13+ | ✅ (Android 6–12: unverified) | ❌ |
| Doze whitelist editing, whitelist current app | ✅ | ✅ | ❌ |
| Disable **all** sensors (sensor privacy) | ❌ | ✅ | ❌ |
| Enable Doze on unsupported devices (`setprop`) | ❌ | ✅ | ❌ |

Every change is read back from the system before EnforceDoze reports it as applied, and it is recorded first so
it can be undone. A value that can't be confirmed is reported as **unverified**, never as success. If access is
lost while dozing (for example, Shizuku stops), the change stays listed as **recovery debt**, and EnforceDoze
restores it as soon as access returns. You can also use **Restore system state** in the Doze Monitor.

> While motion sensors are restricted, other apps receive no motion data: step counters and pocket detection
> pause until the screen comes back on.

## Permissions

* `android.permission.QUERY_ALL_PACKAGES`: needed to select apps to doze
* `android.permission.ACCESS_NETWORK_STATE`: used to detect hotspot state to not disable wifi/data if hostpot is enabled
* `android.permission.READ_PHONE_STATE`: to check if mobile data need to be disabled on Doze if configured
* `android.permission.ACCESS_WIFI_STATE`: to check if WiFi needs to be disabled on Doze if configured
* `android.permission.CHANGE_WIFI_STATE`: to disable WiFi on Doze if configured

[//]: # (# Download )

[//]: # (Play Store link: https://play.google.com/store/apps/details?id=com.akylas.enforcedoze&hl=en)

## Android
### Requirements for compiling source code and running the app:

* Android 6.0 (Marshmallow) SDK platform
* Android smartphone running 6.0 (Marshmallow)
* Android Studio
* Shizuku or root on the device (without either, Doze sessions don't run; see "Shizuku vs root")

# License

This code is licensed under GPL v3

### Having issues, suggestions and feedback?

You can,
- [Create an issue here](https://github.com/farfromrefug/EnforceDoze/issues)

### Languages: [<img align="right" src="https://hosted.weblate.org/widgets/enforcedoze/-/287x66-white.png" alt="Übersetzungsstatus" />](https://hosted.weblate.org/engage/enforcedoze/?utm_source=widget)

[<img src="https://hosted.weblate.org/widgets/enforcedoze/-/multi-auto.svg" alt="Übersetzungsstatus" />](https://hosted.weblate.org/engage/enforcedoze/)

The Translations are hosted by [Weblate.org](https://hosted.weblate.org/engage/enforcedoze/).


<p align="center">
  <a href="https://raw.githubusercontent.com/farfromrefug/sponsorkit/main/sponsors.svg">
	<img src='https://raw.githubusercontent.com/farfromrefug/sponsorkit/main/sponsors.svg'/>
  </a>
</p>
