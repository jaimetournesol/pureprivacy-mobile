import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "ai.tournesol.pureprivacy"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.tournesol.pureprivacy"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
        // matrix-rust-sdk + tor ship arm64-v8a + x86_64 (+ 32-bit). Keep all so it
        // runs on the x86_64 emulator AND real arm64 phones.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        // tor ships as libtor.so; keep it uncompressed + extracted so we can exec it.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Encrypt the session tokens at rest (AES-256 via Android Keystore master key).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // WebView proxy override (route the Element Call WebView through embedded Tor)
    implementation("androidx.webkit:webkit:1.11.0")
    // local TLS-terminating proxy -> onion (over Tor) for the Element Call WebView
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // QR contact exchange — show my @user:onion as a code, scan a friend's to
    // start an encrypted DM. zxing core generates; journeyapps wraps the camera
    // scanner (ScanContract). Keeps the QR-pairing constraint, makes cross-box
    // connect trivial.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // The real thing: Element X's matrix-rust-sdk (E2EE, sliding sync) + JNA (UniFFI).
    implementation("org.matrix.rustcomponents:sdk-android:26.06.11")
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // Embedded Tor (libtor.so) — no Orbot dependency. 0.4.9.5 is the newest build
    // whose AAR doesn't demand compileSdk 36/37 (keeps us on stable AGP 8.6/SDK 34).
    implementation("info.guardianproject:tor-android:0.4.9.5")
}
