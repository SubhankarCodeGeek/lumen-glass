// Lumen Glass — root build.
// A standalone Android project (sibling of "360 Community"). See docs in the library module
// and the planning bundle copied to ./docs (or the origin repo's docs/lumen-glass/).
plugins {
    // Declared here (apply false) so version resolution happens once, from the version catalog.
    // The P1.7 harness app requests com.android.application; declaring it here stops Gradle from
    // seeing AGP "already on the classpath with an unknown version".
    alias(libs.plugins.android.app) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
}
