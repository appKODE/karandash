plugins {
    id("karandash.kmp-distributable-plugin")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":plugins:sdk"))
            implementation(project(":dsl:kts-parser"))
            implementation(project(":core:api"))

            implementation(project(":plugins:plugins-core:rules:core"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
