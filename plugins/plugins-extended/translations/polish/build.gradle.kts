plugins {
    id("karandash.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:api"))
            implementation(project(":plugins:sdk"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":test-fixtures"))
        }
    }
}
