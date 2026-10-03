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
* Force Doze mode immediately after screen off or after a user specified delay
* Add/remove apps or packages directly to system Doze whitelist
* Disable motion sensors to prevent Doze from kicking in during movement
* Disable Biometrics in doze mode to further improve battery life
* Tasker support to turn on/off EnforceDoze and modify other features
* Disable WiFi and mobile data completely during Doze
* Enable Doze mode on devices where OEM has disabled it
* No root mode so you can enjoy the core benefits without rooting your device
* Free, no ads and open source

Here is a table of features 

| Feature              | Non Root | Root    |
|-------------------------------|-------|--------|
| **Disable Wifi**              | Android  < 29    | ✅      |
| **Disable Mobile data**             | ❌     | ✅      |
| **Disable Biometrics**                   | ❌     | Depending on device      |
| **Disable Sensors**               | Depending on device     | ✅      | 
| **Disable All sensors**(equivalent to the dev tile)             | ❌     | ✅  |
| **Ignore disable with hotspot** | ✅ | ✅ |
| **Whitelist music app**                      | ✅     | ✅      |

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
* Root (can work with limited functionality in non-root mode)

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
