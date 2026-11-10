plugins {
    id("karandash.native-exports")
    id("karandash.kmp-reporter-module")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
        }
    }
}

nativeExports {
    packageName.set("io.karandash.plugins.outputs")
    moduleClass.set("StdoutOutputModule")
    moduleType.set("OUTPUT")
}
