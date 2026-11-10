plugins {
    id("karandash.kmp-library")
    id("karandash.publishing")
    alias(libs.plugins.binary.compatibility.validator)
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
