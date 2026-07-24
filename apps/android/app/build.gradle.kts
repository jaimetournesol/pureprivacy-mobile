import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing. The key + passwords live in a gitignored `keystore.properties` (which
// points at the durable keystore under ~/Tournesol/_special-project) — NEVER committed. It's
// the same cert every existing install + past release was signed with, so release APKs update
// in place. Absent (fresh clone / CI without the secret) -> release stays unsigned; debug
// builds are unaffected, so the repo still compiles for anyone.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "ai.tournesol.pureprivacy"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.tournesol.pureprivacy"
        minSdk = 26
        // Play requires a recent target. API 35 (Android 15) enforces edge-to-edge, so every
        // non-Scaffold full-screen composable applies systemBarsPadding() (see MainActivity).
        targetSdk = 35
        versionCode = 42
        versionName = "0.1.41"
        // matrix-rust-sdk + tor ship arm64-v8a + x86_64 (+ 32-bit). Keep all so it
        // runs on the x86_64 emulator AND real arm64 phones.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // Sign with our durable release key when the secret is present, so the published
            // APK installs/updates cleanly on every existing device. No secret -> unsigned.
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
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
    // Process-level lifecycle (ProcessLifecycleOwner) — drives the passcode auto-lock so it
    // only fires when the WHOLE app backgrounds, not on in-app activity hops (calls/pickers).
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")
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
    // Continuous Backup Sync (feature G): WorkManager runs periodic/expedited sync passes
    // under Wi-Fi/battery constraints; DocumentFile walks a persisted OPEN_DOCUMENT_TREE folder.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
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

    // Embedded Tor (libtor.so) — no Orbot dependency. Bumped to a current-series tor
    // (0.4.9.6, needs compileSdk 36 / Android 16). Older tor (0.4.8.x) is EOL and dropped
    // from the network. The newest AARs (0.4.9.8+) require compileSdk 37 (a preview SDK) —
    // deferred until 37 is a stable, non-preview platform.
    implementation("info.guardianproject:tor-android:0.4.9.6")
}
