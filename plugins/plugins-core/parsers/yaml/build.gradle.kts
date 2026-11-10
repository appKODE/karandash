plugins {
    id("karandash.kmp-distributable-plugin")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:api"))
            implementation(project(":plugins:sdk"))
            implementation(libs.yamlkt)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
