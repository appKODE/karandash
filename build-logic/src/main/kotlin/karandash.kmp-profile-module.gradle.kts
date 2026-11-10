/**
 * Convention plugin for built-in profile modules.
 *
 * Applies [karandash.kmp-distributable-plugin] and adds the standard profile
 * dependencies: core and sdk in commonMain; kotlin("test") in commonTest.
 *
 * All profile modules are identical in their dependency structure - this plugin
 * captures that shared baseline so each module's build file is minimal.
 */
plugins {
    id("karandash.kmp-distributable-plugin")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:api"))
            implementation(project(":plugins:sdk"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
