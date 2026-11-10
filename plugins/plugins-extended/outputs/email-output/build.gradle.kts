plugins {
    id("karandash.native-exports")
    id("karandash.kmp-distributable-plugin")
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
            implementation(project(":core:api"))
            implementation(project(":plugins:sdk"))
            implementation(project(":plugins:helpers"))
        }
        nativeMain.dependencies {
            implementation(libs.kotlinx.datetime)
        }
        jvmMain.dependencies {
            implementation(libs.jakarta.mail.api)
            implementation(libs.angus.mail)
        }
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
        }
    }
}

nativeExports {
    packageName.set("io.karandash.plugins.outputs")
    moduleClass.set("EmailOutputModule")
    moduleType.set("OUTPUT")
}
