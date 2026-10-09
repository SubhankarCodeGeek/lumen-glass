pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lumen-glass"

// Single library module for P1 (see docs/lumen-glass/ARCHITECTURE.md §1).
// Splits into real submodules (core / android / compose / sample) only at P5 (packaging).
include(":library")
