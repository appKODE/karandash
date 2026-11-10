import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("karandash.lint")
    id("dev.detekt")
}

kotlin {
    macosArm64()
    macosX64()
    linuxX64()
    mingwX64()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    sourceSets {
        val nativeMain by creating { dependsOn(commonMain.get()) }

        macosArm64Main { dependsOn(nativeMain) }
        macosX64Main { dependsOn(nativeMain) }
        linuxX64Main { dependsOn(nativeMain) }
        mingwX64Main { dependsOn(nativeMain) }
    }
}

configure<dev.detekt.gradle.extensions.DetektExtension> {
    config.setFrom(rootProject.files("detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
    ignoredBuildTypes = listOf("release")
    source.setFrom(
        "src/commonMain/kotlin",
        "src/nativeMain/kotlin",
    )
}
