// M1: keyring unlock — user keys via mailbox-password bcrypt derivation,
// address keys via Token decrypt under the user key (ADR 0004 Section 6).
// BouncyCastle usage adapted from pcontacts' :core:crypto (GPL-3.0).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.alpensync.core.keys"
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
    implementation(project(":core:auth"))
    implementation(libs.bouncycastle.bcpg)
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.junit)
}
