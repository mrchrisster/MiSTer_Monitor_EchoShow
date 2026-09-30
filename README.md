# MiSTer Monitor for Echo Show

Version 0.1.4 is an independent Android display client for chipster6502's MiSTer Monitor server. Intended device: Echo Show 5 first generation running LineageOS / Android 11. Minimum supported Android version: 8.0. No root, Google Play Services or ScreenScraper account required for this version.

## Install

1. Copy `MiSTer-Monitor-EchoShow-v0.1.4.apk` to the Echo Show, open it in a file manager, and allow that file manager to install unknown apps if Android asks.
2. Open **MiSTer Monitor**. It connects to `http://192.168.100.133:8081` by default.
3. Use **Settings** to change the IP or port. Keep the Echo Show and MiSTer on the same network.
4. Tap the artwork to fill the screen; tap again to restore navigation. If artwork is missing, tapping its placeholder retries the request.

Alternatively, with USB debugging enabled and the device authorized:

```powershell
adb connect 192.168.100.140:5555
adb -s 192.168.100.140:5555 install -r MiSTer-Monitor-EchoShow-v0.1.4.apk
adb -s 192.168.100.140:5555 shell am start -n org.mistermonitor.echo/.MonitorActivity
```

The APK uses a development signing certificate. Keep the original build's `.build/development.p12` if you build updates that should install over it. A build using a different signing key requires uninstalling the old app first, which clears its settings and artwork cache. This is a sideloaded prototype, not a Play Store release.

## Visual update in 0.1.3

The main page centers uncropped artwork above the game title, against a dark blurred copy of the cover. Long titles wrap and reduce the cover height as needed. Trailing English articles (The, A, An) move to the front for display without changing artwork matching. A system logo appears at the upper left for 24 supported systems; other cores show their name. There is no MiSTer Monitor logo, header or tab bar. One small three-dot button in the top-right corner opens Now playing, System, Achievements, Settings and Artwork only. Tap the artwork for an uncropped artwork-only view; tap again to restore the title.

For 3D boxes, choose **Style for Selected DBs → 3D Boxes (box3d)** in Update All's artwork settings, save and run, then use the Android app's **Settings → Clear artwork cache**. The app displays the style installed on the MiSTer; it does not generate 3D artwork.

## Start automatically after boot

Version 0.1.4 can be selected as Android's default Home app. In the app, open the three-dot menu, choose **Choose Home app**, and select **MiSTer Monitor**. Android will open it after boot (after unlocking if a lock screen is configured) and whenever Home is pressed. Selecting a Home app is a separate step from installing the APK.

The three-dot menu also includes **Android settings**, so you can change the Home app back to your regular launcher. Back returns to Now playing and does not close the app while it is the Home app. This is a launcher option, not a locked kiosk.

## Features

- Landscape fullscreen dashboard with current game and system.
- Two-second status polling and automatic reconnection.
- MiSTer artwork-pack images loaded asynchronously. There is no direct ScreenScraper fallback yet.
- Aspect-ratio-preserving artwork display and fullscreen artwork mode.
- Local artwork cache, limited to approximately 100 MB; Android may evict cache files.
- Stale artwork rejected when a game changes during download.
- CPU, memory, SD free space, uptime and network details, refreshed approximately every ten seconds.
- Optional RetroAchievements summary and unlock popup, using credentials already configured on the MiSTer. This client does not record achievements. Trophy browsing is not implemented yet.
- After three minutes offline, a dimmed clock uses the Android system's time and timezone. Touch wakes the dashboard; the MiSTer returning online restores normal brightness.

The app keeps the screen awake while it is visible, including standby. It does not power the panel off, wake a sleeping Android device, or bypass the Android lock screen. When selected as the default Home app, it opens after boot and when Home is pressed. Exiting or switching apps stops polling. The standby setting can be disabled.

## Artwork and slow lookups

Install artwork packs through Update All's **Extra Content → Game Artwork DBs** for systems you use. `/media/artwork` returns 404 when it cannot find a pack image. A large CHD may make hash-based lookup slow on the MiSTer. This app displays the title immediately, limits artwork retries, and never explicitly calls the expensive `/status/rom/details` endpoint.

If a game has no pack image, version 0.1.3 displays a placeholder. ScreenScraper artwork fallback and enriched game metadata are future work, not included features.

## Build from source

This source uses only Android framework classes and Java. Install a JDK 17 and the official Android SDK **Platform 30** and **Build Tools 35.0.0**. Then run:

```powershell
python build.py --jdk "C:\path\to\jdk-17" --platform "C:\path\to\sdk\platforms\android-30" --build-tools "C:\path\to\sdk\build-tools\35.0.0"
```

The script runs host-side policy tests, compiles resources and Java, converts bytecode with D8, zip-aligns, signs, and verifies the APK. Output is written beside this source folder. It does not require Android Studio or Gradle. Linux/macOS builds can use corresponding official SDK tools with the same script.

If Windows sandbox filesystem restrictions prevent javac from reading SDK archives, pass `--ecj C:\path\to\ecj-3.37.0.jar` to use the Eclipse Java compiler instead. The supplied APK was compiled with ECJ 3.37.0.

## Validation

The supplied APK compiled successfully, passed 25 host-side policy checks, and passed APK v2/v3 signature verification. Its manifest targets Android 11 (API 30). Earlier live checks against `192.168.100.133:8081` confirmed the expected snapshot and system-stat schemas. Version 0.1.4's native layout still requires physical-device verification; the approved browser mockups were checked separately.

Build and signing validation can be performed on a computer. Actual rendering, touchscreen operation, brightness behavior and long-running stability require installation on the Echo Show. The interface is custom drawn for touchscreen use; full accessibility navigation is not implemented in this prototype.

Suggested device checks:

1. Load a game: title appears promptly; artwork appears if the MiSTer pack contains it.
2. Switch games during an artwork request: the previous cover must not appear on the new game.
3. Open System and confirm values against the MiSTer API.
4. Disconnect the MiSTer: the client shows offline and dims after three minutes; reconnect it and confirm recovery.
5. Change the configured address, then restore it; verify that cached state from another server does not appear.
6. Close and reopen the app; confirm settings persist.

## Attribution

This is original Android client code communicating with the documented HTTP API. No ESP32 firmware or Python server source is bundled. The upstream server remains installed and updated separately:

https://github.com/chipster6502/MiSTer_monitor

This client source is provided under the MIT license in `LICENSE`.

System-logo attribution is listed in `ARTWORK_CREDITS.txt`; logo assets are separate from the MIT source license.

