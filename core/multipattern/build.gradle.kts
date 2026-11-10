plugins {
    id("karandash.kmp-library")
    id("karandash.publishing")
    alias(libs.plugins.atomicfu)
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.kotlin.serialization)
}

atomicfu {
    dependenciesVersion = null
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.atomicfu)
            api(project(":core:api"))
            api(project(":core:regex-safety"))
            api(project(":core:cache"))
            implementation(libs.pomsky.kt.lib)
            implementation(libs.pomsky.kt.decompiler)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
            implementation(kotlin("test"))
            implementation(libs.kotlinx.io.core)
        }
    }
}
