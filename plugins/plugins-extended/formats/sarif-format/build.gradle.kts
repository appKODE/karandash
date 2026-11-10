plugins {
    id("karandash.native-exports")
    id("karandash.kmp-serialization-reporter-module")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
            implementation(project(":plugins:plugins-extended:translations:russian"))
        }
    }
}

nativeExports {
    packageName.set("io.karandash.plugins.formats")
    moduleClass.set("SarifFormatModule")
    moduleType.set("FORMAT")
}
