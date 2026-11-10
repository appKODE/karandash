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
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(project(":core:api"))
            implementation(project(":core:cache"))
            implementation(project(":core:glob"))
            implementation(project(":core:normalize"))
            implementation(project(":core:validation"))
            implementation(project(":core:ai-pipeline"))
            implementation(project(":core:crypto"))
            implementation(project(":plugins:sdk"))
            implementation(project(":plugins:helpers"))
            api(project(":plugins:plugins-ai:ai-core"))
            implementation(project(":plugins:plugins-ai:providers"))
            implementation(project(":plugins:plugins-ai:prompts"))
            implementation(project(":core:file"))
        }
        commonTest.dependencies {
            implementation(project(":plugins:plugins-extended:translations:polish"))
            implementation(project(":plugins:plugins-extended:translations:russian"))
            implementation(project(":test-fixtures"))
            implementation(project(":plugins:plugins-extended:enrichment:local-git-enrichment"))
            implementation(project(":plugins:plugins-core:parsers:yaml"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
        }
    }
}
