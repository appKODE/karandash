plugins {
    id("karandash.kmp-library")
    id("karandash.all-rules-modules")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:api"))

            api(project(":core:config"))
            api(project(":core:config-pipeline"))

            api(project(":core:validation"))
            api(project(":plugins:sdk"))
            implementation(project(":core:http"))
            implementation(project(":core:platform"))
            implementation(libs.kotlinx.io.core)

            api(libs.ktor.client.mock)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
