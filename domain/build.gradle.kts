plugins {
    id("opensab.jvm.library")
    `java-test-fixtures`
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

dependencies {
    implementation(project(":logging"))
    // Flows and suspend functions are in the public signatures of SettingsRepository, MusicManager
    // and every source, so consumers need them on their compile classpath.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    testFixturesImplementation(libs.kotlinx.coroutines.core)
}
