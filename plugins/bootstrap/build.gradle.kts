plugins {
    id("karandash.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:api"))
            api(project(":plugins:sdk"))
            implementation(project(":core:config-pipeline"))
            implementation(project(":core:platform"))
            implementation(project(":core:interpolation"))
            implementation(project(":plugins:loader"))
            implementation(project(":plugins:builtin"))
            implementation(project(":plugins:helpers"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
