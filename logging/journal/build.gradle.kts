plugins {
    id("opensab.jvm.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

dependencies {
    // Nothing else from the project: only :app uses this module, and :logging must stay a leaf.
    implementation(libs.kotlinx.serialization.json)
}
