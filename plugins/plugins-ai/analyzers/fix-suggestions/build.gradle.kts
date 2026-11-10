plugins {
    id("karandash.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":core:api"))
            implementation(project(":core:normalize"))
            implementation(project(":plugins:sdk"))
            implementation(project(":plugins:helpers"))
            implementation(project(":plugins:plugins-ai:ai-core"))
            implementation(project(":plugins:plugins-ai:providers"))
            implementation(project(":plugins:plugins-ai:prompts"))
        }
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
