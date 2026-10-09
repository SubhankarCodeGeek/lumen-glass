// Lumen Glass — root build.
// A standalone Android project (sibling of "360 Community"). See docs in the library module
// and the planning bundle copied to ./docs (or the origin repo's docs/lumen-glass/).
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
}
