// Red contract tests ("tests that define works").
//
// These tests are EXPECTED TO FAIL until the behaviours they describe land.
// They compile as part of `build` (so they never rot), but they are NOT executed by `check`/`build`:
// the default `jvmTest` task is disabled and the tests run only through `contractTest`.
//
//   ./gradlew :plugins:integration-tests:contracts:contractTest
//   ./gradlew :plugins:integration-tests:contracts:macosArm64Test -PcontractTest.native=true
//
// When a change turns a test green, move that test into the owning module's regular test source set.
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    id("karandash.kmp-library")
    id("karandash.all-rules-modules")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.core)
            implementation(libs.clikt)
            implementation(project(":cli"))
            implementation(project(":core:api"))
            implementation(project(":core:models"))
            implementation(project(":core:config"))
            implementation(project(":core:config-pipeline"))
            implementation(project(":core:validation"))
            implementation(project(":core:http"))
            implementation(project(":core:cache"))
            implementation(project(":core:state"))
            implementation(project(":plugins:sdk"))
            implementation(project(":plugins:builtin"))
            implementation(project(":plugins:bootstrap"))
            implementation(project(":plugins:loader"))
            implementation(project(":test-fixtures"))
            implementation(project(":plugins:plugins-ai:ai-core"))
            implementation(project(":plugins:plugins-ai:providers"))
            implementation(project(":plugins:plugins-ai:rules:ai"))
            implementation(project(":plugins:plugins-ai:analyzers:review-comments"))
            implementation(project(":plugins:plugins-core:platforms:gitlab"))
            implementation(project(":plugins:plugins-core:platforms:github"))
            implementation(project(":plugins:plugins-core:parsers:yaml"))
            implementation(project(":plugins:plugins-core:outputs:platform-output"))
            implementation(project(":plugins:plugins-extended:formats:json-format"))
            implementation(project(":plugins:helpers"))
            implementation(project(":core:crypto"))
            implementation(project(":plugins:plugins-core:platforms:bitbucket"))
            implementation(project(":plugins:plugins-extended:platforms:azuredevops"))
            implementation(project(":plugins:plugins-extended:platforms:gerrit"))
            implementation(project(":plugins:plugins-extended:platforms:gitea"))
            implementation(project(":plugins:plugins-extended:platforms:awscodecommit"))
            implementation(libs.kotlinx.io.core)
        }
    }
}

val jvmTestTask = tasks.named<Test>("jvmTest")

// Red tests must not break `check`/`build`: compile them, never run them there.
jvmTestTask.configure { enabled = false }

// Native runs are off the gate until measured; `-PcontractTest.native=true` runs them (e.g. macosArm64Test).
tasks.withType<KotlinNativeTest>().configureEach {
    enabled = providers.gradleProperty("contractTest.native").map { it.toBoolean() }.getOrElse(false)
}

tasks.register<Test>("contractTest") {
    group = "verification"
    description = "Runs red contract tests (expected to fail until the behaviours they describe land)."
    testClassesDirs = jvmTestTask.get().testClassesDirs
    classpath = jvmTestTask.get().classpath
    workingDir = rootProject.projectDir
    // `~` resolves from $HOME first on the JVM too; keep trusted keys and plugin dirs out of the real home.
    environment(
        "HOME",
        layout.buildDirectory
            .dir("contract-home")
            .get()
            .asFile.absolutePath
    )
    useJUnit()
    ignoreFailures = providers.gradleProperty("contractTest.ignoreFailures").map { it.toBoolean() }.getOrElse(false)
    dependsOn("jvmTestClasses")
}
