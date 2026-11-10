import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("karandash.kmp-library")
}

/**
 * Convention plugin for built-in plugin modules that should be distributable as
 * standalone native shared libraries (.dylib/.so/.dll) loadable via [NativePluginLoader].
 *
 * Extends [karandash.kmp-library] with a sharedLib binary for each native target.
 * The base name of the shared library defaults to the project name but can be
 * overridden via the `karandash.sharedLib.baseName` project property.
 *
 * Apply this convention instead of `karandash.kmp-library` for rule, reporter,
 * provider, mutator, and comment-poster modules that should support standalone
 * distribution. Add `NativeExports.kt` in `nativeMain` to expose the C ABI.
 */
val sharedLibBaseName: String =
    project.findProperty("karandash.sharedLib.baseName")?.toString() ?: project.name

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries {
            sharedLib {
                baseName = sharedLibBaseName
            }
        }
    }
}
