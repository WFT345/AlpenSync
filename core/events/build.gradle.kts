// M0: intentionally empty — event-stream poller + WorkManager scheduler land
// at M3 (plan Section 6). The module skeleton is the M0 deliverable.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.alpensync.core.events"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
