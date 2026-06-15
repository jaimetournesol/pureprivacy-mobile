# PurePrivacy Mobile

The branded PurePrivacy phone client — a privacy-first Matrix messenger built on
**Element X's exact engine** (`matrix-rust-sdk`, E2EE + native sliding sync) that
runs an **embedded Tor inside the app** and talks **only to your own personal
`.onion` box** (the [PurePrivacy desktop](../pureprivacy-desktop) appliance).
No central server, no Google/FCM push, no Orbot, no VPN.

> **Status: Phase-2 client, in development.** The Android client is real and working
> (login, contacts, chat, and voice/video calls all run over Tor — see below). iOS
> is a stub. Architecture/decisions live in the appliance repo:
> `pureprivacy-private/docs/redesign/`.

## What it is

- **Your box, over Tor, only.** The app reaches the user's always-on desktop/Pi
  homeserver at its `.onion`. Every request — login, sync, messages, calls — is
  routed through an **embedded Tor** the app runs itself (`socks5h://` +
  HTTP-tunnel on loopback). There is no clearnet account traffic and no third
  party in the path.
- **No push gateway.** PurePrivacy can't use FCM/APNs-style push without leaking to
  clearnet, so a **foreground service** keeps the matrix-rust-sdk sync (and Tor)
  alive in the background and posts local notifications for messages, calls, and
  invites.
- **One core, native UI.** matrix-rust-sdk (the engine Element X ships) does login,
  encryption, sliding sync, room list, and timeline; the UI is Jetpack Compose
  (dark-first, sunflower accent). iOS would follow the same pattern in SwiftUI.

## Core flows that work (verified)

- **Login over Tor.** Enter your box's onion + username + password; the app waits
  for embedded Tor to bootstrap, then connects and signs in. Sessions persist and
  are restored on relaunch.
- **QR mutual-consent contact exchange.** Two people scan each other's codes. A
  scan does two things:
  1. **Pairs the two boxes over federation.** The phone records the peer's box
     onion in its Matrix account data; the peer's desktop box reads that and folds
     the onion into its Caddy fed-proxy allowlist — so the boxes will now federate.
     (tuwunel has no allowlist of its own; the box enforces "only paired peers" one
     layer up.)
  2. **Creates exactly one E2EE DM,** named after the other person. A conversation
     only goes live once **both** sides have scanned (mutual consent): the
     lexicographically-smaller user id creates + invites, the other waits and
     auto-joins when the invite federates over Tor — no duplicate rooms, no race.
- **Live E2EE chat over Tor.** Messages send/receive in real time, federated
  box-to-box over Tor. Encryption (Megolm) is handled by the SDK transparently.
- **Voice/video calls over Tor.** Element Call runs in a WebView, tunneled end to
  end over Tor (lk-jwt token service + LiveKit SFU + coturn TURN, all TLS on the
  onion). A peer call triggers a **full-screen incoming-call ring** (wakes the
  phone over the lock screen, Answer/Decline). Hanging up ends the call on both
  sides; a caller who gives up before answer registers as a missed call.

## Architecture (how the parts fit)

- **Embedded Tor** (`tor/TorManager.kt`) — execs the `libtor.so` shipped by
  `tor-android` with our torrc, exposing a SOCKS5h port and an HTTP-tunnel port on
  loopback. The matrix-rust-sdk client is built with `.proxy(socks5h://…)`, so
  every Matrix request resolves and connects at Tor.
- **matrix-rust-sdk** (`matrix/MatrixRepo.kt`) — login/restore, native sliding
  sync, room list (dynamic adapters), per-room timeline, send. The QR pairing
  consent + mutual auto-accept logic lives here too.
- **Element Call bridge** (`net/TorNet.kt` + `ElementCallActivity.kt`) — Chromium's
  WebView refuses `.onion` hostnames (RFC 7686), so the WebView only ever sees
  `127.0.0.1`: local proxies/forwarders tunnel the box's call endpoints (homeserver
  client API, lk-jwt, the wss SFU) to the onion over Tor, rewriting `.onion`↔
  `localhost` in both directions. Element Call itself is served from a local proxy
  too, so its origin is `127.0.0.1` (a secure, private origin where `getUserMedia`
  works). Call media is forced through a **localhost TURN relay** that bridges to
  the box's coturn over Tor (`iceTransportPolicy = 'relay'`), so RTP rides Tor with
  no direct-UDP leak. For a cross-install call the bridge discovers the call's focus
  box (the peer's box) from the call state and dynamically stands up bridges to it.
- **Foreground service** (`PpSyncService.kt`) — keeps sync alive off-screen; turns
  `MatrixRepo`'s notification stream into Android notifications (status / message /
  full-screen call).

## Build & run (Android)

```sh
cd apps/android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Needs the Android SDK and an emulator or device, plus a running
[PurePrivacy desktop](../pureprivacy-desktop) box to pair with. See
[`apps/android/README.md`](apps/android/README.md) for the full toolchain and the
QR-pairing/call walkthrough.

## Honest caveats

- **Call media is best on real devices.** The full call path is proven over Tor,
  but media-over-Tor on the Android **emulator** is flaky — use two real phones for
  a reliable voice/video test.
- This is an **in-development Phase-2 client.** iOS is not built yet; some niceties
  (attachments, key-verification UI, richer presence) are still to come.

## Layout

```
apps/android/     Compose client — the working Phase-2 app (matrix-rust-sdk + embedded Tor)
apps/ios/         stub (SwiftUI shell to come — same one-core/native-UI pattern)
core_ffi/         earlier shared-Rust-core experiment (superseded by the prebuilt SDK above)
docs/             Element Call over Tor requirements + findings
```

## License

AGPL-3.0 (matches the appliance) for the app; it builds on the Apache-2.0
matrix-rust-sdk.
