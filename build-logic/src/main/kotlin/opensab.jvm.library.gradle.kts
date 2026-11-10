plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    add("testImplementation", libs.findLibrary("junit").get())
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
