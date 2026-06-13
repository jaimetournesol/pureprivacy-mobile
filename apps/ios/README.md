# PurePrivacy iOS (SwiftUI)

The iOS shell. Consumes `core_ffi` via generated Swift bindings (UniFFI) — all
networking/crypto/Tor lives in the Rust core; this target is UI + platform glue
(Keychain, NotificationServiceExtension for APNs push, CallKit/PushKit later).

Not scaffolded yet — needs Xcode. Build the XCFramework from `core_ffi`
(`cargo build --target aarch64-apple-ios …`) and generate Swift bindings, then
add an Xcode project here. UX spec: `pureprivacy-private/docs/redesign/2026-06-ux-design.md` (§6).
