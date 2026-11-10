plugins {
    id("karandash.kmp-library")
    id("karandash.publishing")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:merge"))
            api(project(":core:cache"))
            api(project(":core:normalize"))
            api(project(":core:common"))
            implementation(project(":core:platform"))
            implementation(project(":core:glob"))
            implementation(project(":core:models"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":plugins:sdk"))
            implementation(project(":test-fixtures"))
            implementation(kotlin("test"))
        }
    }
}
