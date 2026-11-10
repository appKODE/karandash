import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("karandash.native-library")
}

val sharedLibBaseName: String = project.findProperty("karandash.sharedLib.baseName")?.toString()
    ?: error(
        "Module '${project.path}' applies 'karandash.native-shared-library' but does not set " +
            "'karandash.sharedLib.baseName'. Add it to the module's gradle.properties or build.gradle.kts."
    )

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries {
            sharedLib {
                baseName = sharedLibBaseName
            }
        }
    }
}
