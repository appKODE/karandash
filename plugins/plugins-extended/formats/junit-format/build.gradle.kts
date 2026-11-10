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
            implementation(project(":plugins:plugins-extended:translations:russian"))
        }
    }
}

nativeExports {
    packageName.set("io.karandash.plugins.formats")
    moduleClass.set("JunitFormatModule")
    moduleType.set("FORMAT")
}
