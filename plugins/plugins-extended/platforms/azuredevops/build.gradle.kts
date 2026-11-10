group = "io.karandash.platforms"

plugins {
    id("karandash.kmp-platform-service-module")
    id("karandash.native-exports")
    alias(libs.plugins.kotlin.serialization)
}

nativeExports {
    packageName.set("io.karandash.platforms.azuredevops")
    moduleClass.set("AzureDevOpsPlatformService")
    moduleType.set("PLATFORM_SERVICE")
}
