plugins {
    id("karandash.kmp-library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.atomicfu)
}

atomicfu {
    dependenciesVersion = null
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":plugins:sdk"))
            api(project(":core:cache"))
            implementation(project(":core:config"))
            implementation(project(":core:crypto"))
            implementation(libs.cryptography.provider.optimal)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":test-fixtures"))
        }
    }
}
