// AlpenSync root build script.
// Plan Rule 11: all versions come from gradle/libs.versions.toml (exact pins).

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.dependencycheck)
    alias(libs.plugins.ksp) apply false
}

// --- detekt (plan Rule 7 + Rule 16: mechanical simplicity limits, enforced in CI) ---
// Runs across every module; `./gradlew detekt` must stay green.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    // Configure the plugin's own `detekt` task in every module so that
    // `./gradlew detekt` (which runs the task by name in ALL projects) uses
    // the root ruleset everywhere. Empty modules are skipped as NO-SOURCE.
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        config.setFrom(rootProject.file("detekt.yml"))
        reports {
            xml.required.set(false)
            sarif.required.set(false)
            txt.required.set(false)
            html.required.set(true)
            html.outputLocation.set(rootProject.layout.buildDirectory.file("reports/detekt/${project.name}.html"))
        }
    }
}

// The detekt plugin already registers a root `detekt` task (NO-SOURCE at M0 —
// the root has no Kotlin). Per-module wiring above does the real work.
tasks.named("detekt") {
    description = "Runs detekt over all modules with the root detekt.yml ruleset."
}

// --- OWASP dependency-check (plan Rule 7: dependency vulnerability scanning) ---
// Runs in CI (`.github/workflows/ci.yml`, job `dependency-scan`).
// Requires an NVD API key (free from https://nvd.nist.gov/developers/request-an-api-key)
// provided as repo secret NVD_API_KEY; without it the NVD feed download is
// rate-limited to the point of being unusable. See DEPENDENCIES.md.
dependencyCheck {
    formats = listOf("HTML", "JSON")
    // High-severity findings fail the scan (plan Rule 7).
    failBuildOnCVSS = 7.0f
    System.getenv("NVD_API_KEY")?.let { nvd.apiKey = it }
}
