// Lumen Glass — the library module (single module for P1).
//
// Source layout (see docs/lumen-glass/ARCHITECTURE.md §1):
//   src/main/kotlin/.../core/      -> platform-agnostic model + maths (NO android/androidx imports)
//   src/main/kotlin/.../android/   -> Android actuals: RuntimeShader, capture, RenderEffect
//   src/main/kotlin/.../compose/   -> public Compose API (Modifier.lumenGlass, ...)
//   src/test/kotlin/...            -> JVM unit tests (maths, tier, lens, purity, shader structure)
//
// NOTE: `core/` purity is enforced by a unit test (CorePurityTest), not by the build — see the
// architecture doc. This module intentionally does NOT depend on any third-party glass library.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.securnyx360.lumenglass"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        // RuntimeShader (the effect) requires API 33+; below that it degrades to a frost fallback.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Compose only — no backdrop, no haze (Q2: we own the substrate).
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
