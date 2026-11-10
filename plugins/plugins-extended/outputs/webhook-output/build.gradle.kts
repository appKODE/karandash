plugins {
    id("karandash.native-exports")
    id("karandash.kmp-http-reporter-module")
    alias(libs.plugins.atomicfu)
}

// Native IR transformation is built into the Kotlin compiler; keep the plugin from adding deps.
atomicfu {
    dependenciesVersion = null
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":plugins:helpers"))
        }
    }
}

nativeExports {
    packageName.set("io.karandash.plugins.outputs")
    moduleClass.set("WebhookOutputModule")
    moduleType.set("OUTPUT")
}
