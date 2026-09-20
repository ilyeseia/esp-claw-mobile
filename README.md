# ESP-Claw Mobile

Android app (Java + local web UI, no third-party dependencies) to chat with and control an
ESP-Claw device over the local network.

- **Chat** – real-time chat through the device's `/ws/webim` WebSocket and `POST /api/webim/send`
  (same protocol as the built-in Web Chat). Image attach (uploaded to `/inbox/webim/`), history kept on the phone.
- **Control** – device status (`/api/status`, `/api/webim/status`), quick prompts, remote restart
  (`POST /api/restart`), and the full device panel (config, files, ...) embedded from `http://<device>/`.
- **Settings** – device address (default `192.168.1.76`), language (العربية / English / Français), theme.
  - **Find device on network**: probes the phone's /24 for `GET /api/webim/status` and lists the ESP-Claw devices found.
  - **Background notifications** (opt-in): a foreground service (`ChatService`) keeps its own WebSocket
    (`MiniWebSocket`, no third-party lib) open while the app is hidden and posts a notification for each
    assistant reply; replies are queued and merged into the chat when the app returns.

## Install

Copy `dist/ESP-Claw-1.1.0.apk` to the phone and open it (allow "install unknown apps"),
or with a phone in USB debugging mode: `adb install -r dist/ESP-Claw-1.1.0.apk`.
The phone must be on the same Wi-Fi network as the device.

## Build

Requires the Android SDK (platform 36.1, build-tools 36.1.0) and JDK 17+.

```bash
export JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"   # or any JDK 17+
./gradlew assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` (git-ignored) must contain `sdk.dir=<path to Android SDK>`.

## Layout

- `app/src/main/java/com/espclaw/mobile/MainActivity.java` – WebView host + native HTTP bridge
  (bypasses CORS; the WebSocket is opened directly by the page).
- `app/src/main/assets/app/index.html` – the whole UI (HTML/CSS/JS).
- `MiniWebSocket.java`, `DeviceScanner.java` – pure-Java (no Android imports), testable on a PC with `javac`/`java`.
- `ChatService.java` – background connection + notifications.

## Notes

- The device API has no authentication; use it only on a trusted network.
- The APK is signed with the local debug key (fine for sideloading). For distribution, sign a release build with your own keystore.
- Cleartext HTTP/WS is enabled on purpose: the device serves plain `http://` and `ws://`.
