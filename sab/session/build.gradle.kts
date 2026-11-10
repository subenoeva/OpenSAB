plugins {
    id("opensab.jvm.library")
    `java-test-fixtures`
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

dependencies {
    implementation(project(":logging"))
    // api, not implementation: RequestIdGenerator, Receipt, DashboardMode and
    // StateFlow<RenderState> are in the public signatures of ScreenProjector, InputDispatcher and
    // AssignmentManager, so every consumer needs them on its compile classpath.
    api(project(":sab:protocol"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    testFixturesImplementation(project(":sab:protocol"))
    testFixturesImplementation(libs.kotlinx.coroutines.core)
}
