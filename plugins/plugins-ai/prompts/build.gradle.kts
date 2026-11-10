plugins {
    id("karandash.kmp-ktor-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
            implementation(project(":core:api"))
            implementation(project(":plugins:sdk"))
            implementation(project(":plugins:helpers"))
            implementation(project(":plugins:plugins-ai:ai-core"))
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
            implementation(project(":test-fixtures"))
        }
    }
}
