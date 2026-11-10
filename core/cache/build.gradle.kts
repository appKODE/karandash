plugins {
    id("karandash.kmp-library")
    id("karandash.publishing")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.atomicfu)
}

atomicfu {
    dependenciesVersion = null
}

configurations.configureEach {
    exclude(group = "dev.whyoleg.cryptography", module = "cryptography-provider-cryptokit")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:api"))
            api(project(":core:crypto"))
            api(project(":core:logger"))
            api(project(":core:file"))
            api(project(":core:common"))
            implementation(project(":core:models"))
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
