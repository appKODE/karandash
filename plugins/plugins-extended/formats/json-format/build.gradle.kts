plugins {
    id("karandash.native-exports")
    id("karandash.kmp-serialization-reporter-module")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
        }
    }
}

nativeExports {
    packageName.set("io.karandash.plugins.formats")
    moduleClass.set("JsonReportFormatModule")
    moduleType.set("FORMAT")
}
