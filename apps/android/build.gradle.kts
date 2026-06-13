plugins {
    id("com.android.application") version "8.6.1" apply false
    // matrix-rust-sdk 26.06.x ships kotlin-stdlib 2.3.0 metadata — match the compiler.
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
}
