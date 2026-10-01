# MiSTer Monitor for Echo Show

Version 0.1.5 is an independent Android display client for chipster6502's MiSTer Monitor server. Intended device: Echo Show 5 first generation running LineageOS / Android 11. Minimum supported Android version: 8.0. No root, Google Play Services or ScreenScraper account required for this version.

## Install

1. Copy `MiSTer-Monitor-EchoShow-v0.1.5.apk` to the Echo Show, open it in a file manager, and allow that file manager to install unknown apps if Android asks.
2. Open **MiSTer Monitor**. On a fresh install, it discovers the server on your local network; there is no default IP address.
3. Use **Settings** to change the IP or port. Keep the Echo Show and MiSTer on the same network.
4. Tap the artwork to fill the screen; tap again to restore navigation. If artwork is missing, tapping its placeholder retries the request.

Alternatively, with network ADB enabled and the device authorized (replace `<ECHO_SHOW_IP>` with the display's current IP):

```powershell
adb connect <ECHO_SHOW_IP>:5555
adb -s <ECHO_SHOW_IP>:5555 install -r MiSTer-Monitor-EchoShow-v0.1.5.apk
adb -s <ECHO_SHOW_IP>:5555 shell am start -n org.mistermonitor.echo/.MonitorActivity
```

The APK uses a development signing certificate. Keep the original build's `.build/development.p12` if you build updates that should install over it. A build using a different signing key requires uninstalling the old app first, which clears its settings and artwork cache. This is a sideloaded prototype, not a Play Store release.

## Visual update in 0.1.3

The main page centers uncropped artwork above the game title, against a dark blurred copy of the cover. Long titles wrap and reduce the cover height as needed. Trailing English articles (The, A, An) move to the front for display without changing artwork matching. A system logo appears at the upper left for 24 supported systems; other cores show their name. There is no MiSTer Monitor logo, header or tab bar. One small three-dot button in the top-right corner opens Now playing, System, Achievements, Settings and Artwork only. Tap the artwork for an uncropped artwork-only view; tap again to restore the title.

For 3D boxes, choose **Style for Selected DBs → 3D Boxes (box3d)** in Update All's artwork settings, save and run, then use the Android app's **Settings → Clear artwork cache**. The app displays the style installed on the MiSTer; it does not generate 3D artwork.

## Start automatically after boot

Version 0.1.4 can be selected as Android's default Home app. In the app, open the three-dot menu, choose **Choose Home app**, and select **MiSTer Monitor**. Android will open it after boot (after unlocking if a lock screen is configured) and whenever Home is pressed. Selecting a Home app is a separate step from installing the APK.

The three-dot menu also includes **Android settings**, so you can change the Home app back to your regular launcher. Back returns to Now playing and does not close the app while it is the Home app. This is a launcher option, not a locked kiosk.

## Features

- Automatic UDP server discovery on fresh installs, with manual address fallback.

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

## Server discovery

The client broadcasts `MMON_DISCOVER_V1` to UDP port 51234, both globally and to active interfaces' subnet broadcast addresses. It accepts `MMON_SERVER_V1:<port>` replies and uses the reply's source IPv4 address and advertised HTTP port. A search lasts approximately 2.4 seconds and retries every 15 seconds when no server is found. One server is selected automatically; multiple distinct servers prompt a chooser. The chosen address is remembered. HTTP snapshot polling still validates the server's response.

In Settings, leave the address empty and Save to enable discovery. Entering an address switches to manual mode. Existing saved addresses are preserved when updating from earlier versions. In discovery mode, an unreachable saved address triggers rediscovery after 60 seconds; manual addresses are not replaced automatically. Searches stop when the app leaves the foreground.

UDP discovery requires a shared broadcast network. Guest Wi-Fi isolation, VLAN boundaries and firewall rules can block it; use a manual hostname or IP in those cases. No server modification or account is required.

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

The supplied APK compiled successfully, passed 31 host-side policy checks plus local UDP integration checks (request/reply, invalid responses, duplicates, multiple servers, timeout and cancellation), and passed APK v2/v3 signature verification. Its manifest targets Android 11 (API 30). Native rendering and discovery on the Echo Show still require physical-device verification; the approved browser mockups were checked separately.

A live LAN test using the same Java discovery implementation successfully found the existing MiSTer Monitor server without a configured server IP.

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

System-logo attribution is listed in `ARTWORK_CREDITS.txt`, also accessible through the app's **Artwork credits** menu. Carbon artwork is CC BY-NC-SA; converted PNGs are distributed under CC BY-NC-SA 4.0 and remain separate from the MIT source license. The original upstream notice, whose heading names 2.0 while its body names 4.0, is preserved in `res/raw/carbon_notice.txt`. The bundled artwork carries NonCommercial and ShareAlike conditions; an MIT code license does not remove them.


