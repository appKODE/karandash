plugins {
    id("karandash.kmp-library")
    id("karandash.publishing")
    alias(libs.plugins.atomicfu)
}

atomicfu {
    dependenciesVersion = null
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
