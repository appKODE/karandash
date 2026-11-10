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
    sourceSets {
        commonMain.dependencies {
            api(project(":core:api"))
            api(project(":core:config"))
            api(project(":core:conditions"))
            implementation(project(":core:common"))
            implementation(project(":core:merge"))
            implementation(project(":core:crypto"))
            implementation(project(":core:state"))
            implementation(project(":plugins:sdk"))
            implementation(project(":core:file"))
            implementation(project(":core:glob"))
            implementation(project(":core:platform"))
            implementation(project(":core:http"))
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":test-fixtures"))
        }
    }
}
