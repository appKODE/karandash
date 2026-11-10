plugins {
    id("karandash.kmp-library")
    id("karandash.publishing")
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
