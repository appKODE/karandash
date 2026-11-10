plugins {
    id("karandash.kmp-library")
    id("karandash.publishing")
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.atomicfu)
}

// Matches core/api: no automatic atomicfu dependency injection (avoids duplicate deps on native test source sets).
atomicfu {
    dependenciesVersion = null
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:models"))
            implementation(project(":core:normalize"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
