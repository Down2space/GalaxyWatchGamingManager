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
        // libadb-android is published on JitPack only; restrict JitPack to that group.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.MuntashirAkon") }
        }
    }
}

rootProject.name = "GalaxyWatchGamingManager"

// Phone management app (Phases 1-4, 6)
include(":app")
// The Wear OS companion app (Phase 5) will be added here as ":wear" — kept separate on purpose.
