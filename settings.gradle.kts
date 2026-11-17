pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GalaxyWatchGamingManager"

// Phone management app (Phases 1-4, 6)
include(":app")
// The Wear OS companion app (Phase 5) will be added here as ":wear" — kept separate on purpose.
