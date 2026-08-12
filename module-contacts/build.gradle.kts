// M2b: offline contacts pipeline — vCard card decryption/merge/projection
// (ADR 0005 Section 1, package `vcard/`). Pure JVM + ez-vcard in vcard/ and
// sync/ so those stay unit-testable without an emulator.
// M2d: the Android side lands — writer/ (ContactsContract ops, chunking,
// apply), sync/ gains the engine + SyncAdapter + WorkManager poker, account/
// gains the authenticator stub (ADR 0005 Sections 1/5/6).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.alpensync.contacts"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // Robolectric needs the merged manifest + resources on the test classpath
    // so android.net.Uri / ContentProviderOperation / an in-memory Room DB
    // work under plain `testDebugUnitTest` (same pattern as :core:db).
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            // Same collision set as :app — the androidTest APK bundles the
            // BouncyCastle/freemarker/jspecify jars' JPMS + OSGi metadata,
            // which the Android runtime never reads.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/*/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/versions/*/module-info.*"
            excludes += "/META-INF/{LICENSE.md,NOTICE.md}"
        }
    }
}

dependencies {
    implementation(project(":core:api"))
    implementation(project(":core:auth"))
    implementation(project(":core:db"))
    implementation(project(":core:keys"))
    implementation(libs.ezvcard)
    // M2d: the periodic requestSync poker (ADR 0005 Section 5 — WorkManager
    // only pokes the SyncAdapter; the adapter does the work). Pin matches
    // pcontacts @ bf9b0c5.
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.bouncycastle.bcpg)
    testImplementation(libs.bouncycastle.bcprov)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}
