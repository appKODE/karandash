plugins {
    id("karandash.native-exports")
    id("karandash.kmp-reporter-module")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
        }
    }
}

nativeExports {
    packageName.set("io.karandash.plugins.formats")
    moduleClass.set("CodeQualityFormatModule")
    moduleType.set("FORMAT")
}
