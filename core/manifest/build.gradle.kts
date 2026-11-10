plugins {
    id("karandash.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:api"))
            api(project(":core:http"))
            api(project(":core:normalize"))
            api(project(":core:merge"))
            implementation(project(":core:common"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
