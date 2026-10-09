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

// P1.7 harness app — proves the shader compiles/renders/binds on a device and that the
// ARCHITECTURE §4 overlap guard fires. Not a product, not the public API (P4).
include(":app")

// P3.5 Macrobenchmark module — frame timing for PLAN §6.1. Test-only; nothing depends on it.
include(":benchmark")
