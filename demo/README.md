# PurePrivacy — recordable demo

Tooling to screen-record the full flow on the two local emulators + test boxes:
**box setup → alice logs into box1 → bob logs into box2 → QR/connect → call**.

Targets the local dev rig: two Android emulators (`emulator-5554` = alice,
`emulator-5556` = bob) and the Docker "boxes" under `/tmp/boxes` (box1, box2).
Not product code — it drives the dev environment for the demo.

## 1. Box visual (run in a terminal you include in the recording)
```
demo/boxes-dashboard.sh
```
A live TUI: both appliance boxes, their `.onion` addresses, and each service
(Tor, tuwunel homeserver, coturn, LiveKit, lk-jwt, Caddy) with up/down status.
The boxes are headless Docker — this is their "face" for the camera.

## 2. Auto-drive the phones (hands-free)
```
demo/demo-drive.sh all       # login -> connect -> chat -> call (call stays live)
demo/demo-drive.sh hangup    # end the call
```
or run phases individually: `login | connect | chat | call | hangup`.
`reset` clears both apps back to a fresh login screen.

## Notes / honest caveats
- **QR scan can't read between two emulators** (the emulator camera shows a fixed
  virtual scene, not the other phone's screen). The `connect` phase shows alice's
  QR + opens bob's scanner, then completes the connection via the address path.
  On real phones the camera read works for real.
- Calls relay media over Tor (forced TURN relay). Both participants must be in the
  **same room** — the script connects via the DM and calls from inside it.
- Demo creds: `alice` / `bob`, password `pureprivacy123` (box1 / box2).
