group = "io.karandash.platforms"

plugins {
    id("karandash.kmp-platform-service-module")
    id("karandash.native-exports")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:platform"))
        }
    }
}

nativeExports {
    packageName.set("io.karandash.platforms.localgit")
    moduleClass.set("LocalGitProvider")
    moduleType.set("PLATFORM_SERVICE")
}
