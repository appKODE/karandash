import org.gradle.api.tasks.Copy
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    id("karandash.kmp-ktor-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries {
            executable {
                entryPoint = "io.karandash.cli.main"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.clikt)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.mordant)
            implementation(libs.yamlkt)
            implementation(libs.kotlinx.io.core)
            implementation(project(":dsl:kts-parser"))
            implementation(project(":core:api"))
            implementation(project(":core:glob"))
            implementation(project(":core:platform"))
            implementation(project(":core:detect"))
            implementation(project(":core:config"))
            implementation(project(":core:validation"))
            implementation(project(":core:cache"))
            implementation(project(":core:ai-pipeline"))
            implementation(project(":core:interpolation"))
            implementation(project(":core:manifest"))
            implementation(project(":core:config-pipeline"))
            implementation(project(":core:state"))
            implementation(project(":core:watcher"))
            implementation(project(":core:crash-handler"))
            implementation(project(":core:diagnostics"))
            implementation(project(":core:crypto"))
            implementation(project(":core:download"))
            implementation(project(":plugins:inspector"))
            implementation(project(":plugins:helpers"))
            implementation(project(":plugins:loader"))
            implementation(project(":plugins:builtin"))
            implementation(project(":plugins:bootstrap"))
            implementation(project(":plugins:registry"))
            // AI-tier commands (stats, calibrate, test-rule, verify-policy, mutate, explain). api: cli's own commands
            // extend SuspendCliktCommand and use ExitCodes, which live there; modules on :cli keep seeing them.
            api(project(":cli-ai"))

            // Direct CLI imports from plugin modules (not transitively exposed by plugins:builtin).
            // No plugins-ai edge: AI-tier calls go through :cli-ai (AiHostBindings); the build guard enforces it.
            implementation(project(":plugins:plugins-core:parsers:yaml"))
            implementation(project(":plugins:plugins-core:parsers:json"))
            implementation(project(":plugins:plugins-core:parsers:kts"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
            // Tests directly reference concrete plugin module classes
            implementation(project(":plugins:builtin"))
            implementation(project(":plugins:plugins-core:platforms:gitlab"))
            implementation(project(":plugins:plugins-core:outputs:platform-output"))
            implementation(project(":plugins:plugins-core:platforms:github"))
            implementation(project(":plugins:plugins-core:platforms:bitbucket"))
            implementation(project(":plugins:plugins-core:platforms:local-git"))
            implementation(project(":plugins:plugins-extended:platforms:azuredevops"))
            implementation(project(":plugins:plugins-extended:platforms:gerrit"))
            implementation(project(":plugins:plugins-extended:platforms:gitea"))
            implementation(project(":plugins:plugins-extended:platforms:awscodecommit"))
            implementation(project(":plugins:plugins-extended:platforms:jenkins"))
            implementation(project(":plugins:plugins-core:profiles:minimal"))
            implementation(project(":plugins:plugins-core:rules:core"))
            implementation(project(":plugins:plugins-core:rules:title-format"))
            implementation(project(":plugins:plugins-core:rules:draft"))
            implementation(project(":plugins:plugins-core:rules:size-limits"))
        }
        commonTest.dependencies {
            implementation(project(":core:crypto"))
            implementation(project(":test-fixtures"))
            // AiContextQualityBaseConfigTest reads ai-core's context-quality config; test scope only
            implementation(project(":plugins:plugins-ai:ai-core"))
        }
    }
}

// Set test working directory to karandash root so that projects/ configs
// are under CWD (required by config pipeline's path traversal security check).
// JVM test tasks (Test) and native test tasks (KotlinNativeTest) need this separately -
// withType<Test> only covers JVM; native binaries use a different task type.
tasks.withType<Test>().configureEach {
    workingDir = rootProject.projectDir
    // `./gradlew :cli:jvmTest -Dkarandash.updateGolden=true` regenerates compat golden files.
    systemProperty("karandash.updateGolden", providers.systemProperty("karandash.updateGolden").getOrElse("false"))
}
tasks.withType<KotlinNativeTest>().configureEach {
    workingDir = rootProject.projectDir.absolutePath
}

val shadowJarTask = tasks.register<Copy>("shadowJar") {
    group = "build"
    description = "Build standalone CLI JAR via :cli-app and copy it to cli/build/libs for compatibility."
    dependsOn(":cli-app:shadowJar")
    from(project(":cli-app").layout.buildDirectory.file("libs/karandash.jar"))
    into(layout.buildDirectory.dir("libs"))
}
