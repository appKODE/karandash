plugins {
    id("karandash.kmp-ktor-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:api"))
            api(project(":core:config"))
            implementation(project(":core:models"))
            implementation(project(":core:normalize"))
            implementation(project(":core:interpolation"))
            implementation(project(":core:platform"))
            implementation(project(":core:glob"))
            implementation(project(":core:cache"))
            implementation(project(":core:merge"))
            implementation(project(":core:conditions"))
            implementation(project(":core:manifest"))
            implementation(project(":core:http"))
            implementation(project(":core:validation"))
            implementation(project(":core:logger"))
            implementation(project(":core:common"))
            implementation(project(":core:crypto"))
            implementation(project(":plugins:sdk"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.yamlkt)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":dsl:kts-parser"))
            // Tests reference concrete plugin types for integration testing
            implementation(project(":plugins:plugins-core:parsers:yaml"))
            implementation(project(":plugins:plugins-core:parsers:json"))
            implementation(project(":plugins:plugins-core:rules:title-format"))
            implementation(project(":plugins:plugins-core:rules:draft"))
            implementation(project(":plugins:plugins-core:rules:size-limits"))
            implementation(project(":plugins:plugins-core:profiles:minimal"))
        }
    }
}
