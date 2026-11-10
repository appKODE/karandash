import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("karandash.kmp-distributable-plugin")
    id("karandash.native-exports")
}

apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.findLibrary("kotlinx-serialization-json").get())
            implementation(libs.findLibrary("kotlinx-coroutines-core").get())
            implementation(libs.findLibrary("kotlinx-io-core").get())
            implementation(libs.findLibrary("quickjs-kt").get())
            implementation(project(":core:api"))
            implementation(project(":core:interpolation"))
            implementation(project(":core:platform"))
            implementation(project(":plugins:sdk"))
            implementation(project(":plugins:helpers"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            implementation(project(":test-fixtures"))
        }
    }
}

nativeExports {
    packageName.set("io.karandash.scripts")
    moduleClass.set("BuiltInScriptModule")
    moduleType.set("SCRIPT")
}
