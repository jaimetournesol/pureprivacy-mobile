# PurePrivacy — Android client (Phase 2)

The branded native client: **matrix-rust-sdk** (Element X's exact engine, E2EE +
native sliding sync) over **embedded Tor** (no Orbot), in a dark + sunflower
Jetpack Compose UI. Connects to the user's own `.onion` box; messages federate
box-to-box over Tor with no central server.

## What's real here (not a demo)

- **Embedded Tor** — bundles `libtor.so` (info.guardianproject:tor-android 0.4.9.5),
  exec'd with our own torrc (SOCKS + HTTP-tunnel on loopback). `TorManager` parses
  the bootstrap log and exposes a `socks5h://` proxy. No Orbot, no VPN.
- **matrix-rust-sdk** — `org.matrix.rustcomponents:sdk-android:26.06.11` (the same
  prebuilt engine Element X ships). `MatrixRepo` does login, native sliding sync,
  room list (dynamic adapters + diff listener), per-room timeline (diff listener),
  and send — all routed through the embedded Tor via `ClientBuilder.proxy(...)`.
  Sessions are persisted (SharedPreferences) and **restored** on relaunch.
- **Compose UI** — login (onion box + user/pass, Tor status badge), room list,
  chat (bubbles + composer). Dark-first, sunflower accent, system-back handled.

## Verified (2026-06-13, two emulators + two onion boxes)

alice@box1 and bob@box2 — two separate `.onion` homeservers — logged in over each
phone's **embedded Tor**, and exchanged messages **cross-install** (federated over
Tor). Live-received messages appear in real time. Screenshots/recording in the
session log.

## Build

```
cd apps/android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk  (~78 MB: matrix_sdk_ffi + libtor + jna)
```
Toolchain: Gradle 8.9, AGP 8.6.1, Kotlin 2.3.0 (matches the SDK's stdlib), JDK 17,
compileSdk 34, minSdk 26. ABIs: arm64-v8a (phones) + x86_64 (emulator).

## Login

Enter your box's onion (e.g. `abcd…xyz.onion`; `:8008` is assumed if no port),
your username and password. The app waits for embedded Tor, then connects.

## Not yet wired

- **Element Call over the onion** (voice/video) — needs the box to serve lk-jwt
  over HTTPS and the call WebView to trust the box's onion cert; see
  `../../docs/element-call-over-onion-requirements.md`. The media path itself is
  already proven (TURN-relay-at-onion).
- New-chat / invite UI (rooms are created/invited server-side for now), encrypted-
  room key verification UI, attachments, push (iOS APNs Sygnal per the plan).
