// M2c: Room mapping store (ADR 0005 Section 3) — ProtonID <-> RawContactID
// mapping, tombstones with a deletion grace period, per-account sync state.
// Holds only IDs/hashes/timestamps: never decrypted content, never tokens
// (THREAT_MODEL.md; pcontacts ADR-0008 — SQLCipher deliberately rejected).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.alpensync.core.db"
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

    // Robolectric needs the merged manifest + resources on the test classpath
    // so it can boot a fake Android runtime and serve Room a Context.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Room: export schemas to disk so a MigrationTestHelper diff can be added the
// day the first migration exists (v1 has none; group_map lands with M2d).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // 'api' because AlpenSyncDatabase extends RoomDatabase; consumers of the
    // DAOs (e.g. :module-contacts' sync engine) need that supertype on their
    // compile classpath even to call a DAO method (pcontacts' note verbatim).
    api(libs.bundles.room)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
