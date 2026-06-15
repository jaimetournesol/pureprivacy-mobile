# PurePrivacy iOS (SwiftUI)

The iOS shell — **not started yet** (stub). The working client today is
[`../android`](../android), which builds on the prebuilt `matrix-rust-sdk`
(sdk-android) + embedded Tor rather than the older `core_ffi` experiment; iOS will
most likely follow the same one-engine / native-UI pattern (the matrix-rust-sdk
ships an analogous Swift package).

This target is UI + platform glue (SwiftUI; Keychain; a background sync path that
avoids clearnet push — Tor over Tor, no FCM/APNs content). Needs Xcode to scaffold.
UX spec: `pureprivacy-private/docs/redesign/2026-06-ux-design.md` (§6).
