group = "io.karandash.platforms"

plugins {
    id("karandash.kmp-platform-service-module")
    id("karandash.native-exports")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:http"))
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
            implementation(libs.cryptography.provider.optimal)
        }
    }
}

nativeExports {
    packageName.set("io.karandash.platforms.github")
    moduleClass.set("GitHubPlatformService")
    moduleType.set("PLATFORM_SERVICE")
}
