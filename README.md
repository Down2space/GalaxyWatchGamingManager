# Galaxy Watch Gaming Manager — Phases 1–4

Native Android (Kotlin + Jetpack Compose) app for managing a **Galaxy Watch6 Classic LTE (SM-R965U)** from your phone over wireless ADB. No root, no bootloader unlock, no computer needed after the APK is installed.

> **Most important rule:** never sacrifice calling, Bluetooth pairing, LTE or stability for gaming. Every change is reviewed, confirmed, journaled and reversible, and protected/unknown packages can never be modified.

## Status by phase

| Phase | What | Status |
|---|---|---|
| 1 | Native UI + wireless ADB pairing (6-digit code, TLS, separate pair/connect ports), connect, disconnect, auto-reconnect | **Implemented** — not yet tested on a device |
| 2 | Dashboard (model, Android/Wear OS, battery, RAM, storage, Wi-Fi, ADB status, app count) + package inventory + package details | **Implemented** — not yet tested on a device |
| 3 | Protected software-management system: 4-way classifier, read-only gate, separate modifying path limited to 7 reversible templates, 9-step review/confirm sequence re-checked at execution | **Implemented** — not yet tested on a device |
| 4 | Standard / Gaming / Custom modes, persistent change journal with original state, per-change Undo, restore-all, manual restore script, calling-checklist reminder | **Implemented** — not yet tested on a device |
| 5 | Wear OS gaming launcher + visualizers (separate `:wear` module) | Not started |
| 6 | Game install/launch, performance | Not started |

**Not compiled yet.** The environment this was written in has no Android SDK and can't reach Google's Maven repository, so the code has not been built. Expect to possibly fix small compile issues on the first build — most likely around the `libadb-android` API (see "Known risks").

## Project layout

```
app/src/main/java/com/gwgm/manager/
  GwgmApp.kt                 Installs Conscrypt, wires dependencies
  MainActivity.kt
  adb/
    AdbCredentialStore.kt    RSA identity + self-signed cert; key encrypted with an Android Keystore AES-GCM key
    WatchAdbManager.kt       libadb-android connection manager
    WatchConnection.kt       pair / connect / disconnect / exec with timeouts + one-shot auto-reconnect
    AdbDiscovery.kt          mDNS discovery of _adb-tls-pairing / _adb-tls-connect
  data/
    WatchParsers.kt          Parsers for getprop, dumpsys battery, meminfo, df, wifi, pm, dumpsys package
    WatchRepository.kt       Read-only queries
    Models.kt
  safety/
    ReadOnlyCommandPolicy.kt Allowlist gate in front of every read command
    ModificationPolicy.kt    ApprovedCommand: the ONLY type the modifying path accepts (7 reversible templates)
    ChangePlanner.kt         Builds proposals (command, original state, risks, undo) and executes with re-checks + verification
    ChangeJournal.kt         Persistent log of every change and its restore command
    ProtectionRules.kt       4-way classifier (Protected / Optional-verified / User / Unknown)
  ui/                        Dashboard, Apps, Package detail, Optimize (modes + journal), Safety (checklist)
app/src/main/assets/optional_allowlist.json   Verified-optional packages (ships EMPTY on purpose)
app/src/test/...             Unit tests for parsers, protection rules and command policy
.github/workflows/build-apk.yml               Cloud build — produces the APK without a computer
```

## Building the APK

### Option A — no computer (GitHub Actions)
1. Create a private GitHub repo and upload this folder (the GitHub app or website works from a phone).
2. The **Build APK** workflow runs on every push (or run it from the Actions tab).
3. Open the finished run → **Artifacts** → download `watch-gaming-manager-debug-apk` (a zip containing `app-debug.apk`).

### Option B — Android Studio
1. Open the folder in Android Studio (Ladybug 2024.2 or newer), let Gradle sync.
2. `Build › Build App Bundle(s)/APK(s) › Build APK(s)` → `app/build/outputs/apk/debug/app-debug.apk`.
   Or from a terminal: `./gradlew assembleDebug` (tests: `./gradlew testDebugUnitTest`).

## Installing on your phone
1. Copy/download the APK to the phone and tap it.
2. Allow "Install unknown apps" for the app you opened it from (Files / Chrome) when prompted.
3. Requires Android 11+ on the phone.

## Watch setup (one time)
1. Watch: **Settings › About watch › Software information** → tap **Software version** 5 times → Developer options enabled.
2. **Settings › Developer options** → turn on **ADB debugging**, then **Wireless debugging**.
3. Watch Wi-Fi: set to **Always on** and connect to the **same network** as the phone (the watch drops Wi-Fi when it's near the phone over Bluetooth).
4. In the app → **Pair** → read the explanation → on the watch open **Wireless debugging › Pair new device** → enter the IP, **pairing** port and 6-digit code (or tap *Find pairing port*).
5. Back on the watch's **Wireless debugging** screen, note the IP:**port** (different from the pairing port) → enter it in the app → **Connect**. Accept the "allow on this network" prompt on the watch if shown.
6. Tap **Refresh** for watch info; go to **Apps → Load** for the inventory.

Wireless debugging switches off after a reboot; turn it back on and reconnect (the port changes; re-pairing usually isn't needed).

## What each mode actually does

| Mode | Commands (each shown and confirmed first) | Undo |
|---|---|---|
| Standard | Runs the undo command of every change still in effect, newest first | — |
| Gaming | `settings put global window_animation_scale 0.5` (+ transition, animator); `am kill-all`; `am start -n com.gwgm.watch/.MainActivity` once the Phase 5 watch app is installed | Original setting values restored (`settings put … <old>` or `settings delete …`); kill-all/launch need no undo |
| Custom | `pm disable-user --user 0 <pkg>` for user apps or verified-optional packages only | `pm enable --user 0 <pkg>` |

Also available from a package's detail page: **Re-enable** (only for packages in the user-disabled state 3, never system-disabled ones) and **Reinstall for this user** (`pm install-existing`) to recover packages removed by an earlier debloat.

**When restoring might need more:** if Wireless debugging was switched off (e.g. after a reboot), turn it on and reconnect first. If the app itself can't connect, **Optimize › Copy manual restore commands** gives you the commands for any other ADB tool. If the watch became unusable, a factory reset re-enables all system packages but erases watch data (the eSIM may need to be re-downloaded from your carrier).

Gaming Mode can't raise CPU/GPU clocks or change thermal limits — that needs root. Expect snappier UI and a bit more free RAM, not higher frame rates in heavy games.

## Security notes
- Pairing credentials: the RSA private key can't live inside Android Keystore because the TLS handshake is done by the bundled Conscrypt. It's stored AES-256-GCM-encrypted with a non-exportable Keystore key, in `noBackupFilesDir`; `allowBackup=false`.
- "Forget this phone's pairing" deletes the identity. Also remove the phone on the watch under *Wireless debugging › Paired devices*.
- Permissions: `INTERNET` (ADB over Wi-Fi), `ACCESS_NETWORK_STATE`/`ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (mDNS discovery). No runtime-permission prompts in this build.

## Known risks / limitations
- **libadb-android API**: written against the 3.x API (`AbsAdbConnectionManager`: `setApi`, `pair(host, port, code)`, `connect(host, port)`, `openStream("shell:…")`, `disconnect()`, `isConnected`). If Gradle can't resolve `3.0.0`, use the newest 3.x from Maven Central and adjust any renamed methods in `WatchAdbManager.kt` / `WatchConnection.kt`.
- App **labels** aren't available over plain ADB (no `aapt` on the watch) — names are derived from package ids and shown in italics.
- **Wear OS version** is estimated from the API level; One UI Watch comes from `ro.build.version.oneui` when present.
- **Dependencies** list only what `dumpsys package` states (libraries, shared UID). An empty list does not mean nothing depends on a package.
- ADB cannot touch modem firmware, bootloader, kernel, or CPU/GPU clocks. "Gaming Mode" (Phase 4) will be limited to reversible user-level settings and disabling individually verified optional packages.
- Android 16+ phones may add a local-network permission prompt for mDNS discovery; manual IP/port entry always works.
