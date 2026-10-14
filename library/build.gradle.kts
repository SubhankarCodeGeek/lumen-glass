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
    `maven-publish`
}

android {
    namespace = "com.subhankar.lumenglass"
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.subhankar.lumenglass"
            artifactId = "lumenglass"
            version = "0.1.1"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("Lumen Glass")
                description.set("A physically accurate, clean-room Liquid Glass rendering library for Android & Jetpack Compose.")
                url.set("https://github.com/SubhankarCodeGeek/lumen-glass")
                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("subhankar")
                        name.set("Subhankar")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/SubhankarCodeGeek/lumen-glass.git")
                    developerConnection.set("scm:git:ssh://github.com/SubhankarCodeGeek/lumen-glass.git")
                    url.set("https://github.com/SubhankarCodeGeek/lumen-glass")
                }
            }
        }
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
