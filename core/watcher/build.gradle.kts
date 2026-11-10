plugins {
    id("karandash.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:logger"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
            implementation(kotlin("test"))
            implementation(libs.kotlinx.io.core)
        }
    }
}
