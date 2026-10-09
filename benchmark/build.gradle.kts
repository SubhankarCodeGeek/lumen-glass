// Lumen Glass — Macrobenchmark module (P3.5).
//
// This module exists solely to measure frame timing for PLAN.md §6.1, which prescribes
// "Macrobenchmark + GPU frame timing". It is not shipped and nothing depends on it.
//
// It targets `:app` and is a *test-only* module (com.android.test), which is what lets it run
// against the app's `benchmark` build type without being part of the app.
plugins {
    alias(libs.plugins.android.benchmark)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.subhankar.lumenglass.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        targetSdk = 36
        // Tells AGP which module's app to install and drive.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    targetProjectPath = ":app"

    // NOTE: no `testBuildType` here — it is not part of the AGP 8.x public DSL.
    //
    // A `benchmark` build type IS declared, though, so that a `connectedBenchmarkAndroidTest` task
    // exists for the CLI. Without it the module only has `debug`, and running that produces:
    //   AssertionError: ERRORS (not suppressed): DEBUGGABLE
    // which is Macrobenchmark correctly refusing to measure a debuggable build — the failure that
    // led here. `matchingFallbacks` is required because :app also has a `benchmark` build type and
    // :library (debug/release only) must still resolve.
    buildTypes {
        create("benchmark") {
            matchingFallbacks += listOf("release")
            // MUST be signed or installation fails with INSTALL_PARSE_FAILED_NO_CERTIFICATES
            // ("Attempt to get length of null array" — i.e. no signing block in the APK at all).
            // A test APK is not shipped, so the debug key is the right choice.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // REQUIRED for Macrobenchmark: runs the benchmark in its own process, separate from the
    // target app, so the measurement is not perturbed by the app's own instrumentation.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.runner)
    // `AndroidJUnit4` (the @RunWith runner) lives in androidx.test.ext:junit, which is NOT pulled
    // in transitively by the benchmark artifacts — without it the runner import is unresolved.
    implementation(libs.androidx.test.junit.ext)
    implementation(libs.androidx.uiautomator)
    implementation(libs.junit)
}
