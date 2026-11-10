plugins {
    id("karandash.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

// AI-tier CLI commands (stats, calibrate, test-rule, verify-policy, mutate, explain), kept out of :cli so its
// plugins-ai imports live in one module. :cli depends on this module and registers the commands; never the reverse.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.clikt)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
            implementation(project(":core:api"))
            implementation(project(":core:config"))
            implementation(project(":core:platform"))
            implementation(project(":core:config-pipeline"))
            implementation(project(":core:http"))
            implementation(project(":plugins:sdk"))
            implementation(project(":plugins:bootstrap"))
            implementation(project(":plugins:plugins-ai:ai-core"))
            implementation(project(":plugins:plugins-ai:providers"))
        }
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
