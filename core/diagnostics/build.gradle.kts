import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("karandash.kmp-library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.atomicfu)
}

// Disable automatic dependency injection by the atomicfu plugin to prevent it from
// adding `api` deps to native test source sets (linuxArm64Test, linuxX64Test), which
// the Kotlin compiler warns about. Native IR transformation is built into the Kotlin
// compiler and does not need the plugin's dep management.
atomicfu {
    dependenciesVersion = null
}

kotlin {
    // psapi.h (GetProcessMemoryInfo) is not part of Kotlin/Native's platform.windows, so MinGW gets its own binding.
    targets.withType<KotlinNativeTarget>().matching { it.konanTarget.family == Family.MINGW }.configureEach {
        compilations.getByName("main").cinterops.create("psapi") {
            defFile(project.file("src/nativeInterop/cinterop/psapi.def"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:api"))
            implementation(project(":core:models"))
            implementation(project(":core:file"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
