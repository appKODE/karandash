import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Convention plugin for serialization-based reporter modules (json, sarif, reviewdog).
 *
 * Applies [karandash.kmp-reporter-module] and the Kotlin serialization compiler
 * plugin, then adds kotlinx-serialization-json to both commonMain and commonTest.
 *
 * Each module must still declare its [karandash.native-exports] plugin and
 * [nativeExports] configuration block in its own build.gradle.kts.
 */
plugins {
    id("karandash.kmp-reporter-module")
}

apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.findLibrary("kotlinx-serialization-json").get())
        }
        commonTest.dependencies {
            implementation(libs.findLibrary("kotlinx-serialization-json").get())
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
