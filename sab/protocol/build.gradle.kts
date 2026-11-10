plugins {
    id("opensab.jvm.library")
    alias(libs.plugins.kover)
}

dependencies {
    implementation(project(":logging"))
}
