import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Convention plugin that generates `NativeExports.kt` for plugin modules.
 *
 * Usage in a module's `build.gradle.kts`:
 * ```kotlin
 * plugins {
 *     id("karandash.native-exports")
 * }
 *
 * nativeExports {
 *     packageName.set("io.karandash.validators")
 *     moduleClass.set("TitleFormatRuleModule")
 *     moduleType.set("RULE")
 * }
 * ```
 *
 * The plugin registers a [GenerateNativeExportsTask] that writes the file into
 * `build/generated/nativeExports/` and wires it as an additional source directory
 * for the `nativeMain` source set.
 */

interface NativeExportsExtension {
    val packageName: Property<String>
    val moduleClass: Property<String>
    val moduleType: Property<String>
}

val ext = extensions.create<NativeExportsExtension>("nativeExports")

val generateTask = tasks.register<GenerateNativeExportsTask>("generateNativeExports") {
    packageName.set(ext.packageName)
    moduleClass.set(ext.moduleClass)
    moduleType.set(ext.moduleType)
    outputDir.set(layout.buildDirectory.dir("generated/nativeExports"))
}

pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
    kotlin.sourceSets.findByName("nativeMain")?.kotlin?.srcDir(generateTask.map { it.outputDir })
}
