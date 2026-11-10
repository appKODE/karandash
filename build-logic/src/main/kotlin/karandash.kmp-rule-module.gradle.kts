import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Convention plugin for built-in rule modules.
 *
 * Applies [karandash.kmp-distributable-plugin] and the Kotlin serialization compiler
 * plugin, then adds the full set of dependencies shared by all rule modules:
 * kotlinx-serialization-json, core, sdk, helpers, and rules-core in commonMain;
 * kotlin("test"), kotlinx-coroutines-test and test-fixtures in commonTest.
 *
 * Modules that need additional dependencies (e.g. kotlinx-datetime for approval,
 * kotlinx-io-core for codeowners)
 * add them on top in their own build.gradle.kts.
 *
 * Each module must still declare its [karandash.native-exports] plugin and
 * [nativeExports] configuration block in its own build.gradle.kts.
 */
plugins {
    id("karandash.kmp-distributable-plugin")
}

apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.findLibrary("kotlinx-serialization-json").get())
            implementation(project(":core:api"))
            implementation(project(":core:glob"))
            implementation(project(":plugins:sdk"))
            implementation(project(":plugins:helpers"))
            implementation(project(":plugins:plugins-core:rules:core"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            implementation(project(":test-fixtures"))
            implementation(project(":core:config"))
            implementation(project(":core:validation"))
        }
    }
}
