plugins {
    id("karandash.kmp-library")
    id("karandash.publishing")
    alias(libs.plugins.kotlin.serialization)
}

configurations.configureEach {
    exclude(group = "dev.whyoleg.cryptography", module = "cryptography-provider-cryptokit")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.cryptography.provider.optimal)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":core:api"))
            implementation(project(":core:logger"))
            implementation(project(":core:platform"))
            implementation(project(":core:file"))
        }
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
