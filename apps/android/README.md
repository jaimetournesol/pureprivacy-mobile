# PurePrivacy — Android client (Phase 2)

The branded native client: **matrix-rust-sdk** (Element X's exact engine, E2EE +
native sliding sync) over **embedded Tor** (no Orbot), in a dark + sunflower
Jetpack Compose UI. Connects to the user's own `.onion` box; messages and calls
federate box-to-box over Tor with no central server and no Google/FCM push.

## What's real here (not a demo)

- **Embedded Tor** — bundles `libtor.so` (info.guardianproject:tor-android 0.4.9.5),
  exec'd with our own torrc (SOCKS5h + HTTP-tunnel on loopback). `TorManager` parses
  the bootstrap log and exposes the proxy. No Orbot, no VPN.
- **matrix-rust-sdk** — `org.matrix.rustcomponents:sdk-android:26.06.11` (the same
  prebuilt engine Element X ships). `matrix/MatrixRepo` does login/restore, native
  sliding sync, room list (dynamic adapters + diff listener), per-room timeline, and
  send — all routed through embedded Tor via `ClientBuilder.proxy(...)`. E2EE
  (Megolm) is transparent. Sessions persist and are **restored** on relaunch.
- **QR mutual-consent contacts** — show your code / scan a friend's (zxing). A scan
  (a) records the peer's box onion in account data, which your **desktop box** reads
  and folds into its federation allowlist (so the two boxes start federating), and
  (b) converges on **exactly one E2EE DM**, named after the other person. The chat
  goes live only once **both** sides have scanned. (`MatrixRepo.startChat` /
  `recordPairing` / `autoAcceptMutual`.)
- **Voice/video calls over Tor** — Element Call in a WebView, fully tunneled over
  Tor (`net/TorNet.kt` + `ElementCallActivity.kt`). Incoming calls ring full-screen
  over the lock screen with Answer/Decline (`IncomingCallActivity`); hang-up ends
  both sides. Media is force-relayed through the box's coturn over Tor.
- **Background sync without push** — a foreground service (`PpSyncService`) keeps
  sync + Tor alive off-screen and posts message/call/invite notifications. No FCM.
- **Compose UI** — login (onion box + user/pass, Tor status badge), chat list,
  chat (bubbles + composer), call. Dark-first, sunflower accent, system-back handled.

## Verified

- **2026-06-13, two emulators + two onion boxes** — alice@box1 and bob@box2 logged
  in over each phone's embedded Tor and exchanged messages **cross-install**
  (federated over Tor), in real time.
- **2026-06-14, calls over Tor** — Element Call connects over Tor (one box, then
  **two-way cross-install** alice ↔ bob), with media forced through the coturn TURN
  relay over Tor (`"connectionType": "turn"`). Full findings in
  [`../../docs/element-call-over-onion-requirements.md`](../../docs/element-call-over-onion-requirements.md).

## Build

```
cd apps/android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk  (~78 MB: matrix_sdk_ffi + libtor + jna)
```
Toolchain: Gradle 8.9, AGP 8.6.1, Kotlin 2.3.0 (matches the SDK's stdlib), JDK 17,
compileSdk 34, minSdk 26. ABIs: arm64-v8a (phones) + x86_64 (emulator).

## Run

1. Stand up a [PurePrivacy desktop](../../../pureprivacy-desktop) box and note its
   `.onion`.
2. **Log in** — enter the onion (`:8008` is assumed if no port), your username and
   password. The app waits for embedded Tor, then connects.
3. **Add a contact** — tap *new chat* → *Show my code* / *Scan a code* (or type a
   `@name:onion`). When both people have scanned, the DM goes live; box-to-box
   federation is auto-allowlisted from the scan.
4. **Call** — open a paired chat and start a call; the other phone rings full-screen.

> **Media is best on real devices.** The whole call path is proven over Tor, but
> media-over-Tor on the emulator is flaky — use two real phones for a clean
> voice/video test.

## How the Element Call bridge works (the hard part)

Chromium's WebView refuses `.onion` hostnames (RFC 7686) and blocks mixed
content / Private Network Access, so the WebView only ever talks to `127.0.0.1`:

- Element Call **itself** is served from a local proxy (origin `127.0.0.1` =
  secure + private, so `getUserMedia` works).
- Local proxies/forwarders (`TorNet`) tunnel the box's homeserver client API,
  lk-jwt, and the wss SFU to the onion over Tor, rewriting `.onion`↔`localhost`
  both ways (so the call membership we publish still carries the real onion focus).
- A localhost TURN bridge forwards to the box's coturn over Tor; an injected patch
  rewrites the SFU's `turn:<onion>` ICE server to it and sets
  `iceTransportPolicy = 'relay'`, so all RTP rides Tor.
- For a cross-install call, the focus box (the peer's) is discovered from the call
  state and bridges to it are stood up dynamically.

The box side must serve these endpoints over **TLS on the onion** (Caddy: lk-jwt
`:8443`, client API `:8009`, wss SFU `:7443`) and map onion `:80`→tuwunel — see
[`pureprivacy-desktop`](../../../pureprivacy-desktop). The full requirements +
findings are in [`../../docs/element-call-over-onion-requirements.md`](../../docs/element-call-over-onion-requirements.md).

## Not yet wired

- E2EE (per-participant-key) calls — the first call connect uses the room's
  unencrypted-call mode; encrypted calls are a follow-on toggle.
- Key-verification UI, attachments, richer presence; iOS client (per the plan).
