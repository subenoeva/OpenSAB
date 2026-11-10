plugins {
    id("opensab.jvm.library")
    `java-test-fixtures`
    alias(libs.plugins.kover)
}

dependencies {
    // :sab:session re-exports :sab:protocol with api(...), but :cluster also speaks the wire types
    // directly (ProjectionFrame, IconId, ColorId in LiveScreenPresets), so it declares both.
    api(project(":domain"))
    api(project(":sab:session"))
    api(project(":sab:protocol"))
    implementation(project(":logging"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(testFixtures(project(":domain")))
    testImplementation(testFixtures(project(":sab:session")))

    testFixturesImplementation(project(":domain"))
    testFixturesImplementation(project(":sab:session"))
    testFixturesImplementation(project(":sab:protocol"))
    testFixturesImplementation(testFixtures(project(":domain")))
    testFixturesImplementation(libs.kotlinx.coroutines.core)
}
