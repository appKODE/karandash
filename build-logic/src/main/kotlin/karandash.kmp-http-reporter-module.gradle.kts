import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Convention plugin for HTTP-based reporter modules (slack, webhook).
 *
 * Applies [karandash.kmp-distributable-ktor-plugin] and the Kotlin serialization
 * compiler plugin, then adds the full set of shared dependencies: ktor client
 * (core + content-negotiation + json), kotlinx-serialization-json, core, sdk,
 * and helpers in commonMain; the Ktor mock engine and kotlin("test") in commonTest.
 *
 * Modules that need additional test dependencies (e.g. kotlinx-coroutines-test
 * for webhook) add them on top in their own build.gradle.kts.
 *
 * Each module must still declare its [karandash.native-exports] plugin and
 * [nativeExports] configuration block in its own build.gradle.kts.
 */
plugins {
    id("karandash.kmp-distributable-ktor-plugin")
}

apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.findLibrary("ktor-client-core").get())
            implementation(libs.findLibrary("ktor-client-content-negotiation").get())
            implementation(libs.findLibrary("ktor-serialization-kotlinx-json").get())
            implementation(libs.findLibrary("kotlinx-serialization-json").get())
            implementation(project(":core:api"))
            implementation(project(":plugins:sdk"))
            implementation(project(":plugins:helpers"))
        }
        commonTest.dependencies {
            implementation(libs.findLibrary("ktor-client-mock").get())
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            implementation(kotlin("test"))
        }
    }
}
