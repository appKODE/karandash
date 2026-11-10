plugins {
    id("org.jetbrains.kotlinx.kover")
}

// Per-module line-coverage floor for `koverVerify`, keyed by Gradle path in gradle/kover-floors.properties
// (the achieved level minus one point, rounded down). A module without an entry has no floor.
val floorsFile = rootProject.layout.projectDirectory.file("gradle/kover-floors.properties")
check(floorsFile.asFile.isFile) { "gradle/kover-floors.properties is missing: without it no module has a coverage floor" }

val floorLines: List<String> =
    providers
        .fileContents(floorsFile)
        .asText
        .get()
        .lines()
        .map { it.substringBefore('#').trim() }
        .filter { '=' in it }

floorLines.map { it.substringBefore('=').trim() }.forEach { key ->
    check(rootProject.findProject(key) != null) { "gradle/kover-floors.properties: '$key' is not a project in this build" }
}

val koverFloor: Int? =
    floorLines
        .firstOrNull { it.substringBefore('=').trim() == path }
        ?.substringAfter('=')
        ?.trim()
        ?.let { requireNotNull(it.toIntOrNull()) { "gradle/kover-floors.properties: floor for $path is not an integer: '$it'" } }

if (koverFloor != null) {
    kover {
        reports {
            verify {
                rule("line coverage floor") {
                    minBound(koverFloor)
                }
            }
        }
    }
}
