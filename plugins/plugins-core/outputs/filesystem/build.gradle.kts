plugins {
    id("karandash.native-exports")
    id("karandash.kmp-reporter-module")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
        }
    }
}

nativeExports {
    packageName.set("io.karandash.plugins.outputs")
    moduleClass.set("FilesystemOutputModule")
    moduleType.set("OUTPUT")
}
