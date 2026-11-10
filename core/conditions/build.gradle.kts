plugins {
    id("karandash.kmp-library")
    id("karandash.publishing")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.atomicfu)
}

atomicfu {
    dependenciesVersion = null
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            api(project(":core:api"))
            api(project(":core:cache"))
            api(project(":core:config"))
            api(project(":core:multipattern"))
            api(project(":core:models"))
            api(project(":core:common"))
            implementation(project(":core:glob"))
            implementation(project(":core:regex-safety"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
