plugins {
    id("karandash.native-exports")
    id("karandash.kmp-reporter-module")
    alias(libs.plugins.kotlin.serialization)
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
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":plugins:plugins-extended:translations:polish"))
            implementation(project(":plugins:plugins-extended:translations:russian"))
            implementation(project(":test-fixtures"))
        }
    }
}

nativeExports {
    packageName.set("io.karandash.plugins.outputs")
    moduleClass.set("PlatformOutputModule")
    moduleType.set("OUTPUT")
}
