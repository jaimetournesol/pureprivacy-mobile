# PurePrivacy Android (Compose)

The Android shell. Consumes `core_ffi` via generated Kotlin bindings (UniFFI);
the Rust core is cross-compiled to `aarch64-linux-android` (cargo-ndk) and
shipped in `jniLibs/`. This target is UI + platform glue (foreground service for
sync, UnifiedPush distributor for push).

Not scaffolded yet — needs the Android SDK/NDK. UX spec:
`pureprivacy-private/docs/redesign/2026-06-ux-design.md` (§6).
