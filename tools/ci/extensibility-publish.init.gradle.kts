// Init script for tools/ci/verify-extensibility.sh.
//
// Publishes :plugins:sdk plus every project it (transitively) depends on into <root>/build/probe-repo,
// unsigned, so tools/examples/extensibility-probe can compile against the SDK exactly as a third party
// would - through Maven coordinates, never through project(...) dependencies.
//
//   ./gradlew --init-script tools/ci/extensibility-publish.init.gradle.kts publishSdkToProbeRepo
//
// publishSdkToMavenLocal does the same into ~/.m2 - what projects from `karandash plugin create` resolve
// (tools/integration-tests/plugin-create-test.sh).
//
// Releases go to Maven Central through the karandash.publishing convention (publishToMavenCentral), not this script.
//
// Only the JVM and root (Gradle metadata) publications are published; native klibs are not needed
// by a JVM consumer and would cost a full native build.

import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.publish.PublishingExtension
import org.gradle.plugins.signing.Sign

val sdkPath = ":plugins:sdk"
val apiConfigurations = setOf("commonMainApi", "commonMainImplementation", "jvmMainApi", "jvmMainImplementation")
val publications = listOf("KotlinMultiplatform", "Jvm")

// Included builds (build-logic) see this init script too; only touch the main build.
if (gradle.parent == null) {
    gradle.beforeProject {
        // maven-publish is cheap and inert unless a publish task runs; applying it everywhere avoids having to
        // know the SDK's dependency closure before projects are evaluated.
        pluginManager.apply("maven-publish")
        val probeRepo = rootDir.resolve("build/probe-repo")
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "probeRepo"
                    url = probeRepo.toURI()
                }
            }
        }
        // The probe repo is a local throwaway; never sign, even when signing keys are configured.
        tasks.withType<Sign>().configureEach { enabled = false }
        // core:api generates a version file into commonMain; sources jars read that dir without declaring it.
        afterEvaluate {
            if (tasks.findByName("generateVersionFile") != null) {
                tasks.matching { it.name.endsWith("sourcesJar", ignoreCase = true) }.configureEach { dependsOn("generateVersionFile") }
            }
        }
    }

    gradle.rootProject {
        val root = this
        val targets = mapOf("ProbeRepo" to "ProbeRepoRepository", "MavenLocal" to "MavenLocal")
        targets.forEach { (target, suffix) ->
            tasks.register("publishSdkTo$target") {
                group = "publishing"
                description = "Publishes the plugin SDK and its project dependency closure to $target (unsigned)."
                dependsOn(
                    root.provider {
                        projectClosure(root.project(sdkPath)).flatMap { p -> publications.map { "${p.path}:publish${it}PublicationTo$suffix" } }
                    },
                )
            }
        }
    }
}

// ProjectDependency.getPath() exists from Gradle 8.11 and getDependencyProject() is gone in 9.x, while the examples
// build on 8.5 and the repo on 9.x; this script has to compile under both, hence the reflective lookup.
fun ProjectDependency.targetProject(from: Project): Project {
    val methods = ProjectDependency::class.java.methods
    val byPath = methods.firstOrNull { it.name == "getPath" && it.parameterCount == 0 }
    if (byPath != null) return from.project(byPath.invoke(this) as String)
    return methods.first { it.name == "getDependencyProject" }.invoke(this) as Project
}

fun projectClosure(start: Project): Set<Project> {
    val seen = linkedSetOf<Project>()
    val queue = ArrayDeque(listOf(start))
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (seen.add(current)) {
            current.configurations
                .filter { it.name in apiConfigurations }
                .flatMap { it.dependencies.withType(ProjectDependency::class.java) }
                .forEach { queue.add(it.targetProject(current)) }
        }
    }
    return seen
}
