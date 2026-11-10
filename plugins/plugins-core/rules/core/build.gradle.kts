plugins {
    id("karandash.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

// Disambiguate from top-level ":core" project which shares the same project name "core".
// Changing group prevents Gradle from treating both as io.karandash:core:* (capability conflict).
group = "io.karandash.validators"

// commonTest contains only shared test utilities (no @Test classes).
// Native test tasks need failOnNoDiscoveredTests=false (JVM already handled by root build).
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    failOnNoDiscoveredTests = false
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":plugins:sdk"))
                implementation(project(":plugins:helpers"))
                implementation(project(":core:common"))
                implementation(libs.kotlinx.serialization.json)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
