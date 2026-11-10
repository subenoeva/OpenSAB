/**
 * `./gradlew unitTests` — the whole unit suite, seven modules, one command.
 *
 * **Owner: the root project.** [unitTestTasks] is a literal, hand-written list: a module added to
 * settings.gradle.kts without a line here runs in nobody's suite. [checkUnitTestCoverageOfModules]
 * is what turns that omission into a build failure instead of a silent gap, and `unitTests` depends
 * on it so the guard runs wherever the suite runs, CI included.
 */
val unitTestTasks: List<String> = listOf(
    ":logging:test",
    ":logging:journal:test",
    ":sab:protocol:test",
    ":sab:session:test",
    ":domain:test",
    ":cluster:test",
    ":app:testDebugUnitTest"
)

tasks.register("unitTests") {
    group = "verification"
    description = "Runs the unit suite of every module."
    dependsOn(unitTestTasks)
    dependsOn("checkUnitTestCoverageOfModules")
}

tasks.register("checkUnitTestCoverageOfModules") {
    group = "verification"
    description = "Fails if a subproject has no unit test task wired into `unitTests`."
    // settings.gradle.kts is fully evaluated before any project build script configures, so
    // rootProject.subprojects already holds every declared module by the time this line runs; a
    // module included later in the same settings file is still on this list. Computed here (not
    // inside doLast) so the task stays configuration-cache-compatible: doLast task actions cannot
    // capture a Project/Gradle script reference, only plain values, which is exactly what `missing`
    // already is.
    val declared = unitTestTasks.map { it.substringBeforeLast(':') }.toSet()
    val missing = rootProject.subprojects
        .map { it.path }
        .filter { it != ":sab" }          // path container, not a module
        .filterNot { it in declared }
    doLast {
        if (missing.isNotEmpty()) {
            throw GradleException(
                "These modules run in nobody's unit suite: ${missing.sorted().joinToString(", ")}"
            )
        }
    }
}
