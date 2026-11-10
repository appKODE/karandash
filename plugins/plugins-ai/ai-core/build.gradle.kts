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
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
            implementation(project(":core:api"))
            implementation(project(":core:common"))
            implementation(project(":core:glob"))
            implementation(project(":core:cache"))
            implementation(project(":core:file"))
            implementation(project(":core:platform"))
            api(project(":core:state"))
            implementation(project(":plugins:sdk"))
            implementation(project(":plugins:helpers"))
            implementation(project(":core:multipattern"))
            implementation(project(":core:regex-safety"))
            implementation(project(":core:normalize"))
            implementation(project(":core:crypto"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":test-fixtures"))
            implementation(project(":plugins:plugins-extended:translations:russian"))
        }
    }
}
