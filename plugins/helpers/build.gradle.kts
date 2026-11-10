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
        commonMain {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.json.io)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                api(project(":plugins:sdk"))
                api(project(":core:http"))
                api(project(":core:regex-safety"))
                implementation(project(":core:api"))
                implementation(project(":core:interpolation"))
                implementation(project(":core:platform"))
            }
        }
        commonTest {
            dependencies {
                implementation(project(":plugins:plugins-extended:translations:polish"))
                implementation(project(":plugins:plugins-extended:translations:russian"))
                implementation(libs.ktor.client.mock)
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":test-fixtures"))
            }
        }
    }
}
