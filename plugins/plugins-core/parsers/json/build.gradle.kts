plugins {
    id("karandash.kmp-distributable-plugin")
    alias(libs.plugins.kotlin.serialization)
}

// Unique group to avoid GAV collisions with other modules named "json" (e.g. json-format).
group = "io.karandash.parsers"

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:api"))
            implementation(project(":plugins:sdk"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
