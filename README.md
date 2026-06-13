# PurePrivacy Mobile

The branded PurePrivacy phone client — a modern messenger (the bar is Element X /
Signal) that talks to **your own box** over its `.onion`, with **Tor embedded in
the app** (via `arti`) so there's **no Orbot to install**. Calls use the Element
Call widget.

> **Status: Phase-2 foundation.** The shared Rust core (`core_ffi`) compiles and
> exposes a stable UniFFI contract the iOS/Android shells build against today.
> The matrix-rust-sdk + arti wiring behind that contract is the next milestone
> (see *Wiring the real client*). Architecture/decisions live in the appliance
> repo: `pureprivacy-private/docs/redesign/`.

## Why this shape (decided)

- **One Rust core, two native UIs.** `core_ffi` (matrix-rust-sdk + arti, Apache-2.0)
  is the *single* point of contact with the churny SDK; the UI is **SwiftUI on
  iOS, Compose on Android** (the Element X pattern). Brand lives in color/type/
  voice (dark-first, sunflower accent), not in fighting the platform.
- **Embedded Tor, no Orbot.** `arti` as a *client* is production-ready (unlike
  onion-service *hosting*). The app reaches the box's `.onion` directly.
- **iOS is a client, never a server.** Apple forbids a background homeserver;
  the box lives on the user's always-on desktop/Pi.

## Layout

```
core_ffi/         Rust: the shared client core (this is the whole brain)
  src/lib.rs      UniFFI contract: PpClient { connect_over_tor, sync, rooms, send… }
apps/ios/         SwiftUI shell (consumes core_ffi via the generated Swift bindings)
apps/android/     Compose shell (consumes core_ffi via the generated Kotlin bindings)
```

## Build the core

```sh
cd core_ffi
cargo test          # compiles the crate + runs the contract tests
```

UniFFI bindings (next, once the SDK is wired):
```sh
cargo run --bin uniffi-bindgen generate --library target/.../libpureprivacy_core.so \
  --language swift   --out-dir apps/ios/Generated
cargo run --bin uniffi-bindgen generate --library … --language kotlin --out-dir apps/android/…
```

## Wiring the real client (next milestone)

The `core_ffi` methods are typed stubs today. To make them real:

1. **Add the heavy deps** (pin exact versions; they're a large, version-sensitive
   compile — give it its own pass): `matrix-sdk` (`e2e-encryption`, `sqlite`,
   `rustls-tls`) and `arti-client` (`tokio`, `rustls`, `onion-service-client`,
   `experimental-api`). See the comment block in `core_ffi/Cargo.toml`.
2. **Tor routing:** run `arti` as a local SOCKS proxy and point matrix-sdk's
   reqwest at `socks5h://127.0.0.1:<port>` (socks5h so the `.onion` resolves at
   Tor), *or* the artiqwest-style custom hyper connector on `TorClient::connect`.
   `matrix_sdk::Client::builder().http_client(...)`.
3. **`connect_over_tor`:** bootstrap arti (surface `TorStage` for the UI),
   discover the homeserver at the `.onion`, log in, open the SQLite crypto store.
4. **Integration test (the milestone gate):** stand up a local tuwunel behind an
   onion; assert the core **logs in over Tor**, **cannot reach clearnet**, and
   **can reach the onion**. (Reuse the harness patterns from the appliance repo's
   federation tests.)
5. **Calls:** generate the Element Call widget URL via the SDK and host it in a
   per-platform WebView; the box runs the LiveKit + lk-jwt backend.
6. **Push:** Android = UnifiedPush/ntfy on the box; iOS = one central APNs Sygnal
   (decided). Wake-signal only; content fetched + decrypted over Tor.

## License
AGPL-3.0 (matches the appliance) for the app; `core_ffi` builds on Apache-2.0
matrix-rust-sdk — no copyleft/trademark entanglement in the SDK layer.
