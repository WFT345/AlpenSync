// M1: SRP-6a (Proton variant) login, TOTP 2FA, modulus envelope verification,
// bcrypt derivations, and the Keystore-wrapped session store (ADR 0004).
// Crypto code is adapted from pcontacts' :core:crypto (GPL-3.0) — see the
// provenance header on every adapted file.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.alpensync.core.auth"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
}

dependencies {
    implementation(project(":core:api"))
    implementation(libs.bouncycastle.bcpg)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}
