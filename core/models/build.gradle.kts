plugins {
    id("karandash.kmp-library")
    id("karandash.publishing")
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.atomicfu)
}

// The atomicfu runtime is declared below, as in core/api, instead of injected by the plugin.
atomicfu {
    dependenciesVersion = null
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
            implementation(project(":core:normalize"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
