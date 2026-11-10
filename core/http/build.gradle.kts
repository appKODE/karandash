plugins {
    id("karandash.kmp-ktor-library")
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
            api(project(":core:crypto"))
            api(project(":core:logger"))
            api(project(":core:common"))
            api(project(":core:cache"))
            api(project(":core:models"))
            implementation(project(":core:multipattern"))
            implementation(project(":core:platform"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        // The CaBundleOverride actual lives in posixMain, above the leaf sets the convention plugin wires curl into.
        findByName("posixMain")?.dependencies {
            implementation(libs.ktor.client.curl)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(project(":test-fixtures"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
