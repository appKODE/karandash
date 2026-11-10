import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Convention plugin for built-in platform service modules.
 *
 * Applies [karandash.kmp-distributable-ktor-plugin] and the Kotlin serialization plugin,
 * then adds the full set of dependencies needed by consolidated platform modules:
 * coroutines, datetime, serialization, ktor client (core + content-negotiation + json),
 * core API, SDK, helpers, and utils.
 *
 * Also wires up the standard test dependencies: kotlin("test"), test-fixtures,
 * ktor mock engine, ktor client libs, coroutines-test, and serialization-json.
 *
 * Each platform module is self-contained (client + models + config + credentials +
 * provider + mutator + comment-poster logic in one module).
 */
plugins {
    id("karandash.kmp-distributable-ktor-plugin")
}

apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.findLibrary("kotlinx-coroutines-core").get())
            implementation(libs.findLibrary("kotlinx-datetime").get())
            implementation(libs.findLibrary("kotlinx-serialization-json").get())
            implementation(libs.findLibrary("ktor-client-core").get())
            implementation(libs.findLibrary("ktor-client-content-negotiation").get())
            implementation(libs.findLibrary("ktor-serialization-kotlinx-json").get())
            implementation(project(":core:api"))
            implementation(project(":plugins:sdk"))
            implementation(project(":plugins:helpers"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":test-fixtures"))
            implementation(libs.findLibrary("ktor-client-mock").get())
            implementation(libs.findLibrary("ktor-client-core").get())
            implementation(libs.findLibrary("ktor-client-content-negotiation").get())
            implementation(libs.findLibrary("ktor-serialization-kotlinx-json").get())
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            implementation(libs.findLibrary("kotlinx-serialization-json").get())
        }
    }
}
