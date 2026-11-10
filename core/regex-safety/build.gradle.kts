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
            implementation(libs.kotlinx.atomicfu)
            api(project(":core:cache"))
            api(project(":core:models"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
