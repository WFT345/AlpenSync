plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.alpensync"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.alpensync"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m1"
    }

    buildTypes {
        release {
            // Minification is exercised from M0 so the pcontacts lesson
            // (R8 silently breaking vCard/crypto code) can never sneak up on us.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No signing config yet: signed + reproducible releases land at M4
            // (plan Section 6). Release APKs built now are unsigned, for CI
            // minification verification only — never distributed.
        }
        debug {
            // Defaults; no debug-only keys, no debug-only logging of secrets (Rule 1).
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // BouncyCastle/jspecify ship JPMS + OSGi metadata in the
            // multi-release section of their jars; the Android runtime
            // never reads META-INF/versions content, so drop it.
            excludes += "/META-INF/versions/*/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/versions/*/module-info.*"
            // The three BouncyCastle jars each carry the same license file.
            excludes += "/META-INF/{LICENSE.md,NOTICE.md}"
        }
    }
}

dependencies {
    // M1 debug login screen (plan Section 6 acceptance): the app shell now
    // wires the real core — SRP login (:core:auth), the typed API client
    // (:core:api), and the keyring unlock (:core:keys). The pinned Proton
    // SRP signing key rides into the APK via :core:auth's AAR.
    implementation(project(":core:auth"))
    implementation(project(":core:api"))
    implementation(project(":core:keys"))
    // M2d: account creation + the debug "Sync now" wiring consume the
    // contacts sync machinery (account constants, SyncScheduler, bootstrap).
    implementation(project(":module-contacts"))
    // Dispatchers.IO/withContext for the screen's suspend calls. Same pin
    // the core modules already use — no new artifact is introduced.
    implementation(libs.kotlinx.coroutines.core)
    // The HV3 JS-bridge message parser (:app's human-verification sheet)
    // parses page-controlled JSON fail-closed. Same pin :core:api already
    // ships — no new artifact enters the dependency graph.
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
