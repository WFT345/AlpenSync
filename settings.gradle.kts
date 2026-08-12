pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // All dependency repositories are declared here; modules must not declare their own.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "alpensync"

include(":app")
include(":core:auth")
include(":core:keys")
include(":core:api")
include(":core:events")
include(":core:db")
include(":module-contacts")
