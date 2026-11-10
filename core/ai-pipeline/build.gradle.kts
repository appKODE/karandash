plugins {
    id("karandash.kmp-library")
    alias(libs.plugins.atomicfu)
}

// Native IR transformation is built into the Kotlin compiler; keep the plugin from adding deps.
atomicfu {
    dependenciesVersion = null
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:api"))
            api(project(":plugins:sdk"))
            implementation(project(":core:interpolation"))
            implementation(project(":core:platform"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
