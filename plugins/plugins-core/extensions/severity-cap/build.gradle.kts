import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("karandash.kmp-distributable-plugin")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:api"))
            implementation(project(":plugins:sdk"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
