plugins {
    id("karandash.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            api(project(":core:platform"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
