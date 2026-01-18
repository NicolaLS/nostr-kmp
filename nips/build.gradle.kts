/**
 * Aggregate project for all NIP modules.
 *
 * This project has no source code but provides lifecycle tasks that aggregate
 * the same tasks across all NIP subprojects:
 *
 * - ./gradlew :nips:build              - Build all NIP modules
 * - ./gradlew :nips:check              - Run checks on all NIP modules
 * - ./gradlew :nips:publishToMavenLocal - Publish all NIP modules to Maven Local
 * - ./gradlew :nips:clean              - Clean all NIP modules
 */

// List of NIP subprojects to aggregate
val nipProjects = listOf(
    ":nips:nip01",
    ":nips:nip04",
    ":nips:nip42",
    ":nips:nip44",
    ":nips:nip45",
    ":nips:nip47",
)

// Aggregate common lifecycle tasks
listOf("build", "check", "clean").forEach { taskName ->
    tasks.register(taskName) {
        group = "build"
        description = "Runs '$taskName' on all NIP modules"
        dependsOn(nipProjects.map { "$it:$taskName" })
    }
}

// Aggregate test tasks
tasks.register("allTests") {
    group = "verification"
    description = "Runs all tests on all NIP modules"
    dependsOn(nipProjects.map { "$it:allTests" })
}

tasks.register("jvmTest") {
    group = "verification"
    description = "Runs JVM tests on all NIP modules"
    dependsOn(nipProjects.map { "$it:jvmTest" })
}

// Aggregate publish tasks
tasks.register("publishToMavenLocal") {
    group = "publishing"
    description = "Publishes all NIP modules to Maven Local"
    dependsOn(nipProjects.map { "$it:publishToMavenLocal" })
}
