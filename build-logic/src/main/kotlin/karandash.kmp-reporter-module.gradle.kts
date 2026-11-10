/**
 * Convention plugin for built-in reporter modules that do not use Ktor.
 *
 * Applies [karandash.kmp-distributable-plugin] and adds the standard reporter
 * dependencies: core, sdk, helpers, and kotlin("test") for tests.
 *
 * Modules that need additional dependencies (e.g. kotlinx-serialization for JSON/SARIF
 * reporters, or Ktor for webhook/slack reporters) add them on top in their own
 * build.gradle.kts.
 */
plugins {
    id("karandash.kmp-distributable-plugin")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:api"))
            implementation(project(":core:platform"))
            implementation(project(":plugins:sdk"))
            implementation(project(":plugins:helpers"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
