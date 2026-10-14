// Lumen Glass — P1.7 harness app.
//
// This module exists ONLY to prove the clean-room shader compiles, renders, binds its uniforms, and
// that the ARCHITECTURE §4 overlap guard fires. It is not a product, not a sample of the public
// API (there isn't one yet — that is P4), and nothing depends on it.
//
// minSdk = 33 (not 21 like :library): RuntimeShader/RenderEffect require API 33, and forcing the
// render path to be unconditional makes the P1.7 result unambiguous. The library itself keeps
// minSdk 21 and gates at runtime.
plugins {
    alias(libs.plugins.android.app)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.subhankar.lumenglass.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.subhankar.lumenglass.sample"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "P1.7-harness"

        // REQUIRED for the instrumented shader compile smoke test to be discovered.
        // Without it, AGP uses a default runner that silently reports "tests: 0" instead of
        // running the AndroidX JUnit4 tests — a false green.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }

        /**
         * Build type for Macrobenchmark (P3.5).
         *
         * Macrobenchmark must measure a build that is NOT debuggable — debug builds carry extra
         * checks and are JIT-dominated, so they measure the wrong thing — but it also must be
         * **profileable** so the benchmark library can read platform frame data.
         *
         * `isMinifyEnabled = false` is deliberate and load-bearing: a minified build is not
         * reliably profileable, and the failure is not an error — the benchmark simply starts
         * producing useless numbers. If R8 is ever turned on here, re-verify against PERF.md.
         *
         * Signed with the debug key so it installs on a physical device without a release keystore.
         */
        create("benchmark") {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            // Distinguishes the benchmark build in the launcher so it is not confused with the
            // debug harness while measuring.
            versionNameSuffix = "-benchmark"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":library"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)

    // Instrumented tests (src/androidTest): the shader compile smoke test. RuntimeShader compiles
    // on the GPU, so this cannot be a JVM unit test. It lives in :app because :app is already
    // minSdk 33 while :library is deliberately minSdk 21.
    //   ./gradlew :app:connectedDebugAndroidTest
    androidTestImplementation(libs.androidx.test.junit.ext)
    androidTestImplementation(libs.androidx.test.runner)
    // Compose UI testing: drives the real UI on a device and asserts on the semantics tree AND on
    // rendered pixels (`captureToImage()`). Needed because a glass effect can be "present" in the
    // tree while rendering nothing — which is exactly the bug these tests exist to catch.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.uitest.junit4)
    debugImplementation(libs.uitest.manifest)
}
